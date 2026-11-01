# Change Request (CR) — CR-055 — Twinpad

| | |
|---|---|
| ID | CR-055 |
| Title | Heartbeat de sesión `ping`/`pong` (S3) y expiración de sesiones zombi |
| Type | New behavior |
| Status | `Applied (owner-requested)` |
| Author | Buffy (agente) |
| Created | 2026-11-01 |
| Decided | 2026-11-01 |
| Decided by | Owner (usuario) — «Implementa el primer CR» sobre el plan de cuellos de botella de conexión |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-001 |
| Sections | §2 (definición Heartbeat), §4.3 (nuevo), §6, §7 (paso 12), §9 (nota), §12 (fila), §13 (2 escenarios), §14, §16, Changelog |
| Current version → new version | `1.0.0 → 1.1.0` |

## 2. Requested change

**Current:**

```text
SPEC-001 v1.0.0 no define ningún tráfico periódico en S3. §6 (S3) declara:
«corte de conexión (cualquier extremo) → S1», pero el único detectable hoy es
el EOF del TCP. §14: «un reinicio del servidor elimina todas las ligaduras».
En código: server/src/main.rs resetea el timeout de lectura a indefinido y
usa EOF como única señal de fin; la app hace lo mismo (soTimeout=0). Un
teléfono que pierde la red sin cerrar el TCP deja la sesión activa en el
registro del Servidor para siempre (sesión zombi); ambos extremos pueden
creer «Linked» 15–60 s (RTO de TCP) o más tras un corte real.
```

**Proposed:**

```text
§2 — añadir definición:

- **Heartbeat**: intercambio periódico `ping`/`pong` de §4.3 durante S3 que
  mantiene viva la ligadura y hace observable un corte silencioso (sin EOF)
  en cualquier extremo.

§4 — añadir sección (tras §4.2):

### 4.3 Heartbeat de sesión (S3)

El Servidor envía `ping` por el socket de control cada 5 000 ms y la app
responde `pong`. Ambos mensajes son líneas UTF-8 JSON + LF, como §4.2.

| Message | Direction | Field | Type | Constraints | Required |
|---|---|---|---|---|---|
| `ping` | servidor → app | `type` | string | fijo `ping` | yes |
| | | `t` | integer (uint64) | contador de pings del Servidor; inicia en 0 y aumenta en uno por cada `ping` | yes |
| `pong` | app → servidor | `type` | string | fijo `pong` | yes |
| | | `t` | integer (uint64) | eco exacto del `t` del `ping` respondido | yes |

Reglas del heartbeat:

- **HB1:** el Servidor emite `ping` solo en S3, nunca durante el Handshake (§7).
- **HB2:** por cada `ping` recibido la app envía exactamente un `pong` (eco de `t`).
- **HB3:** un `pong` aceptado requiere `t` estrictamente mayor que el último
  `pong` aceptado y menor o igual que el último `ping` enviado. Un `pong`
  fuera de ese rango se descarta sin cerrar la sesión y no refresca la vida.
- **HB4:** si el Servidor no acepta ningún `pong` válido durante 15 000 ms,
  declara la sesión caída: transición S3→S1 (§6) con los mismos side effects
  que un corte de conexión, incluido el cierre de su canal media asociado
  (invariante «Pairing activo» de SPEC-003).
- **HB5:** si la app no recibe ninguna línea del Servidor durante 15 000 ms
  estando en S3, declara la sesión caída: transición S3→S1 (§6), idéntica a
  un corte de conexión.
- **HB6:** la expiración del heartbeat no emite `pair_error` ni ningún
  código E (§9): su comportamiento observable es el de un corte de conexión.

§6 — tras la tabla de estados añadir:

En S3 el heartbeat de §4.3 (HB1–HB6) está activo en ambos extremos; su
expiración produce S3→S1 con los side effects de «corte de conexión»,
incluido el cierre del canal media asociado (SPEC-003).

§7 — añadir paso:

12. Con ambos extremos en S3, el heartbeat de §4.3 queda activo: el Servidor
    envía `ping` (`t=0`) y la app responde `pong`.

§9 — tras «Sin respuesta a `pair_challenge` en 10 s …» añadir:

El heartbeat (§4.3 HB4–HB6) no genera códigos E: su expiración equivale a un
corte de conexión en S3 (§6).

§12 — añadir fila:

| Mensajes de heartbeat (`ping`/`pong`) | este documento §4.3 | añadir/quitar tipos exige CR |

§13 — añadir escenarios:

Scenario: Heartbeat mantiene viva la sesión ociosa
  Given una sesión S3 sin tráfico de medios ni inputs
  When el Servidor envía pings cada 5 s y la app responde pong
  Then la sesión permanece en S3 (HB1–HB2)

Scenario: Teléfono desaparece sin EOF (sesión zombi)
  Given una sesión S3 activa
  When el celular pierde la red sin cerrar el TCP (sin EOF hacia el servidor)
  Then el Servidor deja de aceptar pongs y a los 15 000 ms declara la sesión
  caída (HB4), cerrando la ligadura y su canal media

§14 — añadir invariante:

- El heartbeat de §4.3 no consume ni renueva tokens (R2/R3 intactos) y no
  modifica `session_id` ni `session_key`; solo mantiene viva la ligadura de S3.

§16 — añadir filas:

| Aspect | Verified by |
|---|---|
| HB1–HB3 (emisión, eco de `t` y validación del rango) | TEST-0120, TEST-0122 |
| HB4 expiración server-side (sesión zombi sin EOF) | TEST-0120, TEST-0121 |
| HB5 silencio del Servidor percibido por la app | TEST-0122 |

Cabecera: Version 1.1.0, Last updated 2026-11-01, Verified by
«TEST-001 … TEST-009, TEST-0120 … TEST-0122». Changelog:
«1.1.0 — 2026-11-01 — Buffy (agente) — CR-055: heartbeat ping/pong en S3
(§4.3, HB1–HB6) con expiración de sesión zombi; TEST-0120/0121/0122».
```

## 3. Motivation

Revisión de cuellos de botella de conexión solicitada por el owner para una
conexión «100% estable». El análisis de SPEC-001/003 y del código real
(`server/src/main.rs` — «EOF es la única señal de fin»; `TcpPairingClient.kt`
— `soTimeout=0`) mostró que no existe detección de conexión muerta en
ningún extremo: un corte sin EOF (AP que ignora, batería agotada, app
congelada) deja sesiones zombi indefinidas en el registro del Servidor
(clave y credencial media retenidas) y hace que ambos extremos discrepen
durante el RTO de TCP (15–60 s) sobre si la sesión sigue viva. Esto degrada
NFR-006 (multi-sesión) y los flujos de recuperación de SPEC-003 §8.2 /
SPEC-005 §8.3, que asumen detección oportuna del corte.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-002 | no | el heartbeat es mantenimiento de la sesión ya exigida («sesión reutilizable» §2) | none |
| SPEC-001 | yes | §2/§4.3/§6/§7/§9/§12/§13/§14/§16 + changelog | update (1.1.0) |
| SPEC-002 | no | la transición U4→U1 «corte de conexión» ya existe; el trigger ahora puede ser silencioso (HB5), sin cambio de comportamiento observable | none |
| SPEC-003 | no | su invariante «V1/V2 requieren Pairing activo» ya obliga al cierre del canal media al expirar la ligadura | none |
| ARCH | no | sin cambio de fronteras; el heartbeat va por el socket de control existente | none |
| DES-001 | yes | realización: política pura de liveness en `core/` (P10, ENG §5) + hilo heartbeat en el adaptador servidor; reloj inyectado | update |
| DES-004 | no | — | none |
| TEST-0120/0121/0122 (nuevos) | yes | TEST-0120 core (política pura), TEST-0121 servidor loopback (zombi sin EOF), TEST-0122 Android (eco pong + watchdog de silencio) | add rows |
| Code | yes | `core/` (codec ping/pong + política de liveness), `server/` (heartbeat por sesión + cierre), `android/` (pong + watchdog) | done esta iteración |
| Data / migrations | no | — | none |
| Integrations / contracts | yes | dos tipos de mensaje nuevos en el socket de control; Servidor y app se despliegan juntos (misma regla de despliegue coordinado que CR-014/SPEC-003 §15); un cliente antiguo responde E4 al primer `ping` y la sesión termina — aceptado | none (schema `twinpad.v1` sin bump) |

## 5. Options considered

1. **Heartbeat NDJSON `ping`/`pong` (propuesto)** — determinista, portable
   (misma frontera NDJSON que el resto del contrato), detecta además app
   congelada (no solo red caída) y dispara la limpieza existente de
   `registry.remove` + cierre de canal media.
2. SO_KEEPALIVE de TCP — descartado: Android no expone `TCP_KEEPIDLE` en
   `java.net.Socket` (umbral por defecto 2 h), los umbrales ajustados son
   no portables, y no detecta un proceso congelado con el socket abierto.
3. Do nothing — las sesiones zombi persisten indefinidas (fuga de claves y
   credenciales media en `registry.rs`), NFR-006 se degrada con sesiones
   ficticias y la recuperación de SPEC-003 §8.2 hereda la demora del RTO.

## 6. Decision

- **Outcome:** Approved.
- **Rationale:** añade la detección de corte silencioso que SPEC-001 §6 ya
  asume pero ningún extremo puede observar hoy; reutiliza el socket de
  control y la limpieza de sesión existentes, sin tocar Handshake, tokens ni
  el canal media. El owner ordenó su implementación directa (2026-11-01).
- **Rollout:** mismo release; Servidor y app Android actualizados juntos.

## 7. Execution checklist

- [x] Target document(s) updated (SPEC-001 1.1.0, changelog con CR-055)
- [x] Dependent documents updated (DES-001)
- [x] Tests added/updated (TEST-0120, TEST-0121, TEST-0122)
- [x] `testing/traceability.md` rows updated (including CR history table)
- [x] Indexes (`_index.md`) updated
- [x] Code change merged (if any)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-01 | Buffy (agente) | Opened y aprobado en la misma iteración (orden directa del owner) |
| 0.2.0 | 2026-11-01 | Buffy (agente) | Applied: SPEC-001 1.1.0, DES-001 0.2.0, core/server/android implementados; TEST-0120/0121/0122 verdes |
