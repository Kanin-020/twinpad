# REQ-002 — Pairing PC–celular vía QR

| | |
|---|---|
| ID | REQ-002 |
| Title | Pairing PC–celular vía QR |
| Version | 0.1.0 |
| Status | `Draft` |
| Priority | `Must` |
| Owner | Eng lead |
| Refines | CTX scope item "Vinculación PC ↔ celular (pairing mediante QR)" |
| Implemented by | `-` |
| Last updated | 2026-10-07 |

## 1. Objective

El flujo definido en la entrevista exige que conectar el celular sea un paso único y simple ("abres la app en el celular → escanea QR → se vincula"), sin configuración manual de IPs ni puertos. Sin esto, la barrera de entrada de Twinpad sería comparable a la fricción que el proyecto busca eliminar.

## 2. Description

El usuario debe poder vincular su Android device con el PC Host escaneando un QR de vinculación que el servidor muestra, de modo que quede establecida una sesión autenticada entre ambos. La sesión debe ser reutilizable en conexiones posteriores sin repetir todo el proceso, salvo que el usuario la revoque.

## 3. Actors

| Actor | Role in this requirement |
|---|---|
| Jugador / Usuario PC | Muestra el QR en la PC y escanea con el celular |
| PC Host | Genera y muestra el QR de vinculación; acepta la sesión |
| Android device | Escanea el QR e inicia la sesión con el servidor |

## 4. Preconditions

- Servidor Twinpad activo (REQ-001).
- PC y celular en la misma red LAN o con Bluetooth disponible (CTX-C2).

## 5. Behavior expected (summary)

- El PC muestra un QR que contiene todo lo necesario para conectarse.
- El celular, tras escanearlo, queda vinculado y autenticado con ese PC.
- Solo dispositivos con el token del QR pueden establecer sesión (NFR-004).

## 6. Acceptance criteria

- [ ] AC-1: Dado el servidor activo, cuando el usuario escanea el QR con la app Twinpad, entonces queda establecida una sesión autenticada entre ambos dispositivos sin ingresar IP/puerto manualmente.
- [ ] AC-2: Dado un dispositivo que no escaneó el QR, cuando intenta conectarse directamente al servidor, entonces es rechazado (NFR-004).
- [ ] AC-3: Dado un QR mostrado hace más del tiempo de validez definido en su SPEC, cuando se escanea, entonces la vinculación es rechazada con un mensaje de "QR expirado, generar uno nuevo".

## 7. Non-functional concerns

- NFR-004: autenticación mutua de la sesión a partir del QR.

## 8. Edge cases and limits

- Múltiples celulares escaneando el mismo QR (ver alcance multi-dispositivo, NFR-006).
- Celular fuera de la LAN al escanear (red distinta).

## 9. Dependencies

| On | Type | Why |
|---|---|---|
| REQ-001 | blocking | El servidor debe estar activo para mostrar el QR |
| CTX-C2 | blocking | Medio de conexión disponible |

## 10. Related specifications

- `-` until assigned

## 11. Notes and open questions

- Definir tiempo de validez y caducidad del QR — owner: Eng lead — 2026-10-07

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
