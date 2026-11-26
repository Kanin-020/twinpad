# Change Request (CR) — CR-122 — Twinpad

| | | |
|---|---|---|
| ID | CR-122 |
| Title | Descubrimiento BT HID más rápido: registro que no muere en silencio, último host primero y reintento de enlace sin reprogramarse |
| Type | `Behavior change` |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-26 |
| Decided | 2026-11-26 |
| Decided by | Owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-004 (§8.3, §16, changelog) |
| Sections | SPEC-004 §8.3 |
| Current version → new version | SPEC-004 1.15.0 → 1.16.0 |

## 2. Requested change

El owner reporta que tras CR-120 «lo del steam sigue tardando mucho» en
detectar el teléfono como control. Revisión del camino BT HID en
`android/hid`:

**Current:**

```text
- start(): adapter.isEnabled / adapter.name fuera de try (SecurityException
  si BLUETOOTH_CONNECT aún no está concedido) y setName(ADAPTER_NAME) en
  cada arranque aunque el nombre ya sea ese.
- registerApp(): se ignora su return. Si el SO lo rechaza sin callback (otra
  app ocupa el rol HID Device), no se programa ningún reintento y el gamepad
  no aparece hasta reiniciar la app.
- connectToBondedHost(): se conecta a todos los emparejados clásicos en el
  orden que devuelve el SO; el host que funcionó la última vez no tiene
  prioridad.
- scheduleConnectRetry(): cada DISCONNECTED de la ronda cancela y reprograma
  el reintento (1,5 s → 8 s) incrementando intentos, así que N emparejados
  alargan la ronda y empujan el backoff al tope.
- onPermissionGranted(): desregistra y recrea el canal en cada apertura de la
  Activity (que pide BLUETOOTH_CONNECT aunque ya esté concedido). Medido en el
  teléfono del owner: la conexión HID se abría y el rearme la tiraba, dejando
  el registro anterior ocupado en el SO y 5/10/20 s de reintentos.
- connectToBondedHost(): incluye los emparejados AUDIO_VIDEO (radio del coche,
  auriculares); no aceptan HID y cada uno gasta un page timeout por ronda.
```

**Proposed (SPEC-004 §8.3):**

```text
- Trigger: el canal BT HID se arranca al abrir si no está operativo (y se
  rearma al entrar en Linked, ante Failure o al concederse BLUETOOTH_CONNECT).
- Si el SO rechaza el registro (return false) o lo pierde, reintento con
  backoff 5 s → 20 s, sin límite.
- Con el perfil registrado se intenta conectar con todos los emparejados
  clásicos, empezando por el último host que conectó (recordado entre
  ejecuciones); un enlace caído se reintenta con backoff 1,5 s → 8 s sin
  reprogramar el reintento ya pendiente.
- El rearme del canal (al concederse el permiso) no recrea nada: reintenta
  el registro o el enlace pendiente del canal existente.
- Si el rechazo del registro llega sin callback con el canal en IDLE/FAILED,
  el rol puede estar en un registro huérfano de la propia app (force-stop no
  lo libera): se libera con unregisterApp (mismo UID) y se reintenta a los
  250 ms, sin tocar el backoff de 5-20 s, que queda para otra app.
- Se omiten los emparejados AUDIO_VIDEO mientras quede algún candidato.
- El nombre del adaptador pasa a «Twinpad» solo si es distinto.
```

## 3. Motivation

Implementation discovery sobre el reporte del owner. Los tres primeros puntos
son defectos demostrables por lectura del código (camino sin reintento, orden
arbitrario y churn de reintentos); el cuarto añade robustez para cuando el
permiso llega después del arranque.

## 4. Impact analysis

| Area | Impact |
|---|---|
| SPEC-004 §8.3 | Trigger del canal y algoritmo de descubrimiento del host documentados; Signature, descriptor y reportes sin cambio |
| SPEC-004 §2/§4 | Sin cambio: Report Map, bits y `seq` intactos |
| Wire / protocolo | Sin cambio: sigue siendo HID estándar del SO |
| Permisos | Sin cambio: se usa `BLUETOOTH_CONNECT` ya pedido al abrir |
| Tests | TEST-0215 nuevo (`HidHostOrder`); TEST-0127 (HandoverPolicy) sin cambio |
| Código | `hid/BluetoothHidGamepad` (`retryNow`, liberación del registro huérfano, filtro de clase), `hid/HidHostOrder`, `app/pairing/GamepadBtSwitcher` (rearme idempotente) |
| Datos | Nueva preferencia local `last_hid_host` (mismo `SharedPreferences` del nombre original) |
| Banco físico | Medido el 2026-11-26 en el teléfono del owner (2511FPC34G) con el PC `fedora`: app abierta → `/dev/input/js0` en el SO del PC en 0,96 s (antes: rondas de 12,8 s + 8 s y registro rechazado cada 5-20 s) |

## 5. Options considered

1. **Arreglar los puntos anteriores y documentarlos (elegida).** Son defectos
   acotados y verificables sin cambiar el contrato HID.
2. Conectar solo al último host — descartada: rompe CR-120 (un PC nuevo o
   re-emparejado dejaría de detectarse).
3. Reducir el page timeout de conexión — no es configurable desde la API de
   Android.
4. Do nothing — el camino puede quedarse sin reintento y el orden depende de
   los emparejados.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** el owner pide que la detección deje de tardar; estos cambios
  eliminan los caminos que dejaban el canal sin armar o castigaban la ronda.
- **Rollout:** misma release; sin migración (la preferencia nueva se crea sola).

## 7. Execution checklist

- [x] Target document(s) updated (SPEC-004 1.16.0 §8.3/§16/changelog)
- [x] Dependent documents updated (nota en este CR)
- [x] Tests added/updated (TEST-0215)
- [x] `testing/traceability.md` rows updated (incluida la tabla de historial de CR)
- [x] Indexes (`docs/process/change-requests/_index.md`) updated
- [x] Code change applied

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-26 | Buffy (agente) | Abierto y aprobado con el reporte del owner; código y SPEC aplicados |
| 0.2.0 | 2026-11-26 | Buffy (agente) | Banco físico: el enlace se abría y el rearme lo tiraba; se añade rearme idempotente, liberación del registro huérfano y filtro AUDIO_VIDEO (SPEC-004 1.17.0) |
