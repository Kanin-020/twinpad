# REQ-004 — Segunda pantalla vía framebuffer al celular

| | |
|---|---|
| ID | REQ-004 |
| Title | Segunda pantalla con framebuffer hacia Android |
| Version | 0.2.0 |
| Status | `Draft` |
| Priority | `Must` |
| Owner | Eng lead |
| Refines | CTX scope item "Transmisión de una segunda pantalla desde la PC al celular" |
| Implemented by | `-` |
| Last updated | 2026-10-18 |

## 1. Objective

La información secundaria del juego (mapas, inventario, estadísticas) debe poder verse sin abandonar la vista principal ni superponer ventanas (CTX §1). La Segunda pantalla en el celular es la respuesta de Twinpad, y la decisión de la entrevista es transmitirla como framebuffers/imágenes (CTX-C6), genérica para cualquier juego integrado.

## 2. Description

El Plugin del Juego / Aplicación huesped origina y publica la Segunda pantalla como Framebuffers. Twinpad los transporta y adapta al stream Android, pero no captura escritorio, genera contenido ni renderiza la fuente; el Android device muestra la imagen como vista dedicada de la sesión.

## 3. Actors

| Actor | Role in this requirement |
|---|---|
| Plugin | Publica los framebuffers de la Segunda pantalla |
| Servidor PC | Adapta y retransmite los framebuffers del Plugin |
| PC Host | Ejecuta el Servidor PC y el Plugin |
| Android device | Muestra la Segunda pantalla en la app Twinpad |
| Juego / Aplicación huesped | Origina el contenido mostrado vía Plugin |

## 4. Preconditions

- El host del Plugin ha iniciado o reutilizado el Servidor PC y presenta el QR de vinculación.
- Sesión establecida (REQ-002) y un Plugin vivo con capacidad de publicar video.

## 5. Behavior expected (summary)

- El celular muestra la Segunda pantalla del Plugin que haya seleccionado para esa sesión.
- La imagen se actualiza de forma continua y fluida durante la sesión; el Servidor PC no captura ni genera imágenes.
- Si no hay Plugin seleccionado con video vivo, no hay Segunda pantalla activa (la app lo indica).

## 6. Acceptance criteria

- [ ] AC-1: Dado un Plugin seleccionado y una sesión activa, cuando el Plugin publica un Framebuffer, entonces el Android device presenta ese Framebuffer sin captura de escritorio ni contenido generado por el Servidor PC.
- [ ] AC-2: Dado una sesión activa y un flujo de video publicado por el Plugin, cuando se mide la latencia de visualización, entonces cumple NFR-001.
- [ ] AC-3: Dado una sesión activa sin Plugin seleccionado con video vivo, cuando la app muestra la vista de Segunda pantalla, entonces indica "sin segunda pantalla activa" y no presenta un Frame congelado ni contenido generado por el Servidor PC.

## 7. Non-functional concerns

- NFR-001: latencia p95 ≤ 60 ms de la Segunda pantalla.
- NFR-005: costo de CPU del renderizado en el Android.
- NFR-007: consumo energético en sesión prolongada.

## 8. Edge cases and limits

- Cambio de juego compatible en mitad de sesión.
- Red LAN saturada (degradación definida en SPEC: fps/resolución).
- Orientación del teléfono (vertical/horizontal) durante la sesión.

## 9. Dependencies

| On | Type | Why |
|---|---|---|
| REQ-002 | blocking | Transporte disponible |
| REQ-005 | soft | El stream de video comparte infraestructura con audio |
| REQ-006 / REQ-007 | soft | Sin Plugin no hay contenido que mostrar |

## 10. Related specifications

- SPEC-002 — selección de Plugin desde Android.
- SPEC-003 — transporte y presentación de video.
- SPEC-006 — anuncio, liveness y selección de Plugin.

## 11. Notes and open questions

- Definir política de degradación (resolución vs fps) bajo red saturada — owner: Eng lead — 2026-10-07

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
| 0.2.0 | 2026-10-18 | Product owner + Twinpad team (CR-010) | Plugin origina la fuente; Server adapta/retransmite y no captura escritorio ni genera demo |
