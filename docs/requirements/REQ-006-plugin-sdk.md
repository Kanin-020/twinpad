# REQ-006 — Integración vía SDK Twinpad (plugin modelo B)

| | |
|---|---|
| ID | REQ-006 |
| Title | Integración vía SDK Twinpad (modelo B) |
| Version | 0.1.0 |
| Status | `Draft` |
| Priority | `Must` |
| Owner | Eng lead |
| Refines | CTX scope item "Integración por plugin (SDK Twinpad)" y CTX-C7 |
| Implemented by | `-` |
| Last updated | 2026-10-17 |

## 1. Objective

La visión de la entrevista es que "cualquier app puede integrarse vía plugin". Para juegos que pueden modificar su código, el SDK Twinpad es el camino oficial: el juego integra la librería y Twinpad Core detecta que hay un juego compatible corriendo y empieza el flujo de datos.

## 2. Description

Twinpad debe proveer un SDK Twinpad que un Juego / Aplicación huesped pueda integrar para: anunciar su presencia al Servidor PC (detección de juego compatible), publicar el contenido de su Segunda pantalla y recibir/consumir inputs del Gamepad HID según la Signature HID acordada. Con el SDK, el juego obtiene el flujo completo: Pairing → detección → Segunda pantalla → inputs.

## 3. Actors

| Actor | Role in this requirement |
|---|---|
| Juego / Aplicación huesped | Integra el SDK y publica contenido/declaraciones |
| Plugin | El SDK mismo, en su rol de integración (modelo B) |
| Twinpad Core | Detecta el juego compatible y coordina el flujo |
| PC Host | Ejecuta Core y el juego integrado |

## 4. Preconditions

- Servidor Twinpad activo (REQ-001).
- Juego con SDK integrado, en ejecución en el PC.

## 5. Behavior expected (summary)

- Un juego con SDK integrado es detectado automáticamente por Twinpad Core al ejecutarse.
- El juego puede publicar contenido de Segunda pantalla a través del SDK.
- Si declara capacidad `audio`, el juego puede entregar PCM real mediante una fuente callback del SDK y Twinpad lo reproduce en Android sin que la fuente retrase la publicación de frames.
- El juego recibe los inputs del gamepad a través del canal acordado con el SDK.

## 6. Acceptance criteria

- [ ] AC-1: Dado un juego con SDK integrado, cuando se ejecuta con el servidor activo, entonces Twinpad Core lo detecta y la app Android lo muestra como juego compatible disponible.
- [ ] AC-2: Dado un juego detectado y sesión activa, cuando el juego publica un frame de segunda pantalla vía SDK, entonces llega al celular (sujeto a REQ-004).
- [ ] AC-3: Dado un juego detectado, cuando el juego consulta el estado de controles vía SDK, entonces obtiene la Signature HID declarada y los eventos de input correspondientes.
- [ ] AC-4: Dado un juego vivo con capacidad `audio`, cuando su fuente callback publica PCM `s16le`, entonces el Android reproduce ese audio; ante congestión se descartan chunks PCM antiguos sin bloquear la publicación de frames, y sin fuente PCM el teléfono permanece en silencio.

## 7. Non-functional concerns

- NFR-002: los inputs consumidos vía SDK no agregan latencia perceptible sobre el canal HID.

## 8. Edge cases and limits

- Dos juegos con SDK ejecutándose simultáneamente en el mismo PC.
- Juego con SDK que crashea: la sesión no debe romperse.

## 9. Dependencies

| On | Type | Why |
|---|---|---|
| REQ-001 | blocking | El servidor coordina la detección |
| REQ-004 / REQ-005 | soft | Canales de salida que el SDK alimenta |
| CTX-C5 | blocking | Signature HID como contrato de inputs |

## 10. Related specifications

- `-` until assigned

## 11. Notes and open questions

- Definir lenguajes/runtimes soportados por el SDK en v1 — owner: Eng lead — 2026-10-07

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
| 0.2.0 | 2026-10-17 | Owner + Twinpad team (CR-003) | Añade AC-4 para publicación de PCM desde SDK y silencio si no existe fuente |
