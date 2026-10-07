# REQ-005 — Transmisión de video y audio

| | |
|---|---|
| ID | REQ-005 |
| Title | Transmisión de video y audio |
| Version | 0.1.0 |
| Status | `Draft` |
| Priority | `Must` |
| Owner | Eng lead |
| Refines | CTX scope items "Transmisión de segunda pantalla" y "Captura y transmisión de audio y video" |
| Implemented by | `-` |
| Last updated | 2026-10-07 |

## 1. Objective

El must-have de la v1 definido en la entrevista incluye "entrada/salida de audio y video": la Segunda pantalla no es solo imagen; el audio del juego debe poder escuchararse en el celular y los streams deben ser estables durante una sesión de juego completa.

## 2. Description

Twinpad debe transmitir de forma continua video (Framebuffers de la Segunda pantalla, REQ-004) y audio desde el PC Host al Android device durante la sesión, manteniendo sincronización razonable entre ambos y recuperándose de interrupciones transitorias de la red sin requerir re-vinculación (Pairing).

## 3. Actors

| Actor | Role in this requirement |
|---|---|
| PC Host | Captura y transmite video y audio |
| Android device | Reproduce video y audio en la app |
| Juego / Aplicación huesped | Origina el contenido audiovisual |

## 4. Preconditions

- Sesión establecida (REQ-002).
- Juego integrado en ejecución transmitiendo contenido.

## 5. Behavior expected (summary)

- El celular reproduce el audio del juego integrado mientras muestra la Segunda pantalla.
- Un corte transitorio de red no rompe la sesión: se reanuda sin re-pairing.
- Video y audio permanecen sincronizados dentro de la tolerancia definida en SPEC.

## 6. Acceptance criteria

- [ ] AC-1: Dado sesión activa, cuando el juego emite audio y video, entonces el celular reproduce ambos durante toda la sesión.
- [ ] AC-2: Dado una pérdida de red menor (< umbral definido en SPEC), cuando la red se restablece, entonces el stream se reanuda automáticamente sin volver a escanear el QR.
- [ ] AC-3: Dado un stream en curso, cuando se mide el desfase audio-video, entonces se mantiene dentro de la tolerancia definida en el SPEC correspondiente.

## 7. Non-functional concerns

- NFR-001: latencia del video.
- NFR-006: sesión múltiple sin degradación.
- NFR-007: eficiencia energética en reproducción prolongada.

## 8. Edge cases and limits

- Juego que emite solo audio sin segunda pantalla, y viceversa.
- Dispositivo de audio por defecto cambiado a mitad de sesión en la PC.

## 9. Dependencies

| On | Type | Why |
|---|---|---|
| REQ-002 | blocking | Transporte disponible |
| REQ-004 | soft | El video usa el mismo transporte y pipeline |

## 10. Related specifications

- `-` until assigned

## 11. Notes and open questions

- Elegir códec y estrategia de jitter buffer — owner: Eng lead — 2026-10-07

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
|---|---|---|
| 0.1.0 | 2026-10-07 | Twinpad team | Initial draft |
