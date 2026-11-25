# Change Request (CR) — CR-115 — Twinpad

| | |
|---|---|
| ID | CR-115 |
| Title | No enviar audio del teléfono a la PC mientras haya audio Bluetooth conectado |
| Type | Behavior change |
| Status | Approved |
| Author | Buffy (agente) |
| Created | 2026-11-25 |
| Decided | 2026-11-25 |
| Decided by | Product owner (chat) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-005 (§5, §7), DES-004 (captura del micrófono), REQ-005 (AC de audio) |
| Sections | SPEC-005 §5 (salidas), §7 (flujo), §12 (errores: sin cambios); DES-004 (demanda del micrófono) |
| Current version → new version | `1.4.0 → 1.5.0` (a fijar por el PO) |

## 2. Requested change

**Current:**

```text
SPEC-005 §7 + CR-035/CR-090: con juego «cemu» seleccionado, `mic_request on=true`
y el interruptor «Enviar micrófono al GamePad» activado, la app captura el
micrófono (AudioRecord 32 kHz mono s16) y envía `mic_chunk` a la PC; el juego
decide qué hacer con ese PCM.
```

**Proposed:**

```text
SPEC-005 §5 (nueva precondición de salida): la app NO envía audio del teléfono
hacia la PC mientras exista una salida de audio Bluetooth conectada en el
teléfono (A2DP, manos libres/SCO o LE Audio: auricular o altavoz). Sin audio BT,
el comportamiento es el actual (gate por `mic_request` + interruptor).
La regla se reevalúa en caliente: al conectarse el audio BT se detiene la
captura vigente; al desconectarse, se reanuda si el resto del gate se cumple.

v0.2.0 (decisión del owner, chat 2026-11-25): además del micrófono, la
REPRODUCCIÓN de la sesión en el teléfono no puede salir por Bluetooth. Con
cualquier salida BT conectada, el `AudioTrack` de Twinpad se fija al altavoz
del teléfono; sin BT se deja la salida del sistema.
```

Motivo dado por el owner (2026-11-25): «Actualmente si hay conexión BT se usa el PC como si fuera bocina, se evita ese comportamiento».

## 3. Motivation

Con audio Bluetooth conectado, el audio del teléfono termina reproduciéndose en la PC como si fuera una bocina Bluetooth. El único camino de audio teléfono → PC en el wire es el micrófono (`mic_chunk`, SPEC-005 §4 / CR-035 / CR-090), así que la supresión se aplica a esa captura.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-005 | yes | Nuevo AC: ningún audio del teléfono hacia la PC con audio BT conectado | revisar y añadir AC |
| SPEC-005 | yes | Precondición de salida en §5/§7 | update vía este CR |
| SPEC-004 | no | El mando BT HID no es una salida de audio; el input no cambia | none |
| DES-004 | yes | La demanda del micrófono consulta la ruta de audio del teléfono | update |
| Code | yes | `ui/MicDemandController` + `ui/MicDemand.isBluetoothAudioOutputType/hasBluetoothAudioRoute`; `PairingActivity` registra `AudioDeviceCallback` y delega la demanda. v0.2.0: `ui/PhoneAudioRoute` decide la salida y `AudioPlayback` recrea el `AudioTrack` con `setPreferredDevice(altavoz)` | implementado |
| Tests | yes | TEST-0206 (gate puro + tipos de salida BT) y TEST-0208 (ruta de reproducción) | añadidos |
| Observabilidad | yes | El interruptor «Enviar micrófono al GamePad» sigue encendido: la app no captura, no se avisa en UI (ver OQ-2) | decidir OQ-2 |

## 5. Options considered

1. Suprimir la captura del micrófono mientras haya salida de audio BT (elegida): el wire no cambia y la regla es determinista.
2. Silenciar solo el PCM (enviar silencio): mantiene el stream pero no evita que la PC reproduzca el audio del teléfono — no cumple el objetivo.
3. No hacer nada: el PC sigue actuando como bocina.

## 6. Decision

- **Outcome:** aprobada por el product owner el 2026-11-25 con la lectura «cualquier audio del teléfono, el PC actúa como bocina Bluetooth». v0.2.0: la supresión cubre las dos vías del teléfono hacia el PC — el micrófono (`mic_chunk`) y la reproducción local de la sesión (ruta fija al altavoz).
- **Rationale:** evitar que la PC reproduzca el audio del teléfono cuando hay una salida Bluetooth activa.
- **Rollout:** cambio local de la app; sin cambios de wire ni de servidor.

## 7. Open questions

- **OQ-1:** ¿la supresión debe cubrir también sesiones donde el audio BT aparece con `mic_request on=true` en curso? (implementado: sí, se reevalúa en caliente; la ruta de reproducción también).
- **OQ-2:** ¿la UI debe indicar por qué no se envía el micrófono (p. ej. en el drawer) o basta el silencio? (implementación actual: sin aviso).

## 8. Execution checklist

- [x] Code change applied (`MicDemandController`, `MicDemand`, `PairingActivity`, `PhoneAudioRoute`, `AudioPlayback`)
- [x] Tests added (TEST-0206, TEST-0208) and `testing/traceability.md` rows
- [ ] Target document(s) updated (version bumped, changelog entry references this CR ID) — pendiente: el agente no modifica SPECs
- [ ] Dependent documents updated (DES-004, REQ-005)
- [ ] Indexes (`_index.md`) updated

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-25 | Buffy (agente) | Opened and decided (owner chat) |
| 0.2.0 | 2026-11-25 | Buffy (agente) | v0.2.0: se añade la ruta de reproducción al altavoz con BT (TEST-0208); el alcance son las dos vías de audio de la app (micrófono y reproducción), no el audio de otras apps |
