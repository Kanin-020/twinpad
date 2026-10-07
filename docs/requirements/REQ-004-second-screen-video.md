# REQ-004 — Segunda pantalla vía framebuffer al celular

| | |
|---|---|
| ID | REQ-004 |
| Title | Segunda pantalla con framebuffer hacia Android |
| Version | 0.1.0 |
| Status | `Draft` |
| Priority | `Must` |
| Owner | Eng lead |
| Refines | CTX scope item "Transmisión de una segunda pantalla desde la PC al celular" |
| Implemented by | `-` |
| Last updated | 2026-10-07 |

## 1. Objective

La información secundaria del juego (mapas, inventario, estadísticas) debe poder verse sin abandonar la vista principal ni superponer ventanas (CTX §1). La Segunda pantalla en el celular es la respuesta de Twinpad, y la decisión de la entrevista es transmitirla como framebuffers/imágenes (CTX-C6), genérica para cualquier juego integrado.

## 2. Description

El PC Host debe poder transmitir a la app Twinpad una Segunda pantalla en forma de Framebuffers, y el celular debe mostrarla en su pantalla como vista dedicada de la sesión. El contenido proviene del Juego / Aplicación huesped a través de un Plugin (REQ-006/REQ-007); Twinpad transporta y muestra, no define el contenido.

## 3. Actors

| Actor | Role in this requirement |
|---|---|
| PC Host | Captura/transmite los framebuffers de la Segunda pantalla |
| Android device | Muestra la Segunda pantalla en la app Twinpad |
| Juego / Aplicación huesped | Origina el contenido mostrado |
| Plugin | Define qué se muestra (vía SDK o config externa) |

## 4. Preconditions

- Sesión establecida (REQ-002).
- Un juego compatible en ejecución (detectado vía su Plugin).

## 5. Behavior expected (summary)

- El celular muestra la Segunda pantalla del juego integrado en ejecución.
- La imagen se actualiza de forma continua y fluida durante la sesión.
- Si no hay juego compatible corriendo, no hay Segunda pantalla activa (la app lo indica).

## 6. Acceptance criteria

- [ ] AC-1: Dado un juego integrado en ejecución y sesión activa, cuando el juego actualiza su contenido de segunda pantalla, entonces el celular lo refleja sin intervención del usuario.
- [ ] AC-2: Dado sesión activa y flujo de video en curso, cuando se mide la latencia de visualización, entonces cumple NFR-001.
- [ ] AC-3: Dado sesión activa sin juego compatible corriendo, cuando el usuario abre la app, entonces ve el estado "sin segunda pantalla activa" en lugar de una pantalla congelada.

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

- `-` until assigned

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
