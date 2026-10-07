# REQ-003 — Emulación Gamepad vía Bluetooth HID nativo

| | |
|---|---|
| ID | REQ-003 |
| Title | Emulación Gamepad Bluetooth HID nativo |
| Version | 0.1.0 |
| Status | `Draft` |
| Priority | `Must` |
| Owner | Eng lead |
| Refines | CTX scope item "Emulación del celular como gamepad HID" |
| Implemented by | `-` |
| Last updated | 2026-10-07 |

## 1. Objective

El PC debe ver el celular como un control estándar para que cualquier juego que acepte gamepads funcione sin modificación (CTX §1, CTX-C3, CTX-A3, CTX-A5). Esta es la capacidad que hace a Twinpad útil incluso con juegos que nunca integrarán el SDK.

## 2. Description

El Android device debe presentarse ante el PC Host como un Gamepad HID estándar (conectado vía Bluetooth), de modo que el sistema operativo de la PC lo reconozca como un control físico y cualquier aplicación pueda consumir sus inputs sin software adicional. Los inputs provienen de los controles táctiles de la app y de los inputs físicos del teléfono (gyro, acelerómetro, botones, vibración de feedback).

## 3. Actors

| Actor | Role in this requirement |
|---|---|
| Android device | Actúa como Gamepad HID; captura y envía inputs |
| PC Host | Recibe el dispositivo HID como control estándar del sistema |
| Juego / Aplicación huesped | Consume los inputs como los de un gamepad común |

## 4. Preconditions

- Sesión establecida vía Pairing (REQ-002) o emparejamiento Bluetooth previo.
- Bluetooth disponible en ambos extremos (CTX-C2).

## 5. Behavior expected (summary)

- El SO de la PC lista el celular como un gamepad disponible, igual que un control físico conectado por cable/USB dongle.
- Los inputs táctiles y físicos del celular llegan a la PC como inputs de ese gamepad.
- El juego puede vibrar el celular como feedback (rumble del gamepad).

## 6. Acceptance criteria

- [ ] AC-1: Dado el celular vinculado, cuando se abre el panel de controles de juego del SO de la PC, entonces el celular aparece como gamepad conectado.
- [ ] AC-2: Dado el gamepad visible, cuando el usuario presiona un botón táctil en la app, entonces el juego recibe el input correspondiente.
- [ ] AC-3: Dado un juego que emite rumble, cuando dispara el evento de vibración, entonces el celular vibra.

## 7. Non-functional concerns

- NFR-002: latencia p95 ≤ 15 ms de input HID.

## 8. Edge cases and limits

- Pérdida de conexión Bluetooth a mitad de sesión (comportamiento del gamepad en la PC).
- Dispositivos sin soporte HID device role (CTX-A2).
- Conflicto con otros gamepads físicos conectados.

## 9. Dependencies

| On | Type | Why |
|---|---|---|
| REQ-002 | blocking | La sesión autenticada habilita el flujo de datos |
| CTX-C1 (Android 12+) | blocking | Soporte HID device role estable |
| CTX-C5 | blocking | Signature HID definida (2 joysticks, 10–12 botones, triggers L/R, gyro) |

## 10. Related specifications

- `-` until assigned

## 11. Notes and open questions

- Confirmar cobertura real de HID device role por dispositivo (encuesta de dispositivos) — owner: Eng lead — 2026-10-07

## Definition of Done

- [ ] Every acceptance criterion is objective and binary-pass.
- [ ] Actors exist in `product/context.md` §5.
- [ ] Terms used are defined in the glossary.
- [ ] At least one SPEC implements this REQ (when Status is `Implemented`).
- [ ] No implementation details (tech, frameworks, endpoints, schemas).
- [ ] Traceability row exists in `testing/traceability.md`.
- [ ] No placeholders; Status updated.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Twinpad team | Initial draft |
