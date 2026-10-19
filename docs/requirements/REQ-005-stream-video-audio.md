# REQ-005 — Transmisión de video y audio

| | |
|---|---|
| ID | REQ-005 |
| Title | Transmisión de video y audio |
| Version | 0.2.0 |
| Status | `Draft` |
| Priority | `Must` |
| Owner | Eng lead |
| Refines | CTX scope items "Transmisión de segunda pantalla" y "Retransmisión de audio y video publicados por un Plugin" |
| Implemented by | `-` |
| Last updated | 2026-10-18 |

## 1. Objective

El must-have de la v1 definido en la entrevista incluye "entrada/salida de audio y video": la Segunda pantalla no es solo imagen; el audio del juego debe poder escuchararse en el celular y los streams deben ser estables durante una sesión de juego completa.

## 2. Description

El Plugin del juego origina video (Framebuffers de la Segunda pantalla, REQ-004) y audio; el Servidor PC puede adaptar sus formatos y los retransmite al Android device durante la sesión. Mantiene sincronización razonable y se recupera de interrupciones transitorias de red sin re-vinculación (Pairing). El Server no captura ni sintetiza contenido audiovisual.

## 3. Actors

| Actor | Role in this requirement |
|---|---|
| Plugin | Publica video y audio del juego |
| Servidor PC | Adapta y retransmite video/audio del Plugin seleccionado |
| PC Host / integración host del Plugin | Inicia/reutiliza el Servidor PC, presenta el QR y ejecuta el Plugin |
| Juego / Aplicación huesped | Origina el contenido audiovisual vía Plugin |
| Android device | Reproduce video y audio en la app |

## 4. Preconditions

- El host del Plugin ha iniciado o reutilizado el Servidor PC y presenta el QR de vinculación.
- Sesión establecida (REQ-002) y Plugin seleccionado que publica uno o ambos medios.

## 5. Behavior expected (summary)

- El celular reproduce el audio y muestra video del Plugin seleccionado para su sesión.
- El Server no captura/sintetiza medios; adapta y retransmite los publicados por el Plugin.
- El host inicia/reutiliza el Servidor PC una sola vez para presentar el QR; un corte transitorio de red no rompe la sesión y se reanuda sin re-pairing.
- Video y audio permanecen sincronizados dentro de la tolerancia definida en SPEC.

## 6. Acceptance criteria

- [ ] AC-1: Dado una sesión activa con un Plugin seleccionado, cuando el Plugin publica video y/o audio, entonces el Android device recibe y presenta/reproduce únicamente los medios publicados por ese Plugin.
- [ ] AC-2: Dado una pérdida de red menor a 10 s, cuando la red se restablece, entonces el stream se reanuda automáticamente sin volver a escanear el QR (SPEC-003 §8.2).
- [ ] AC-3: Dado un stream de video y audio sincronizados, cuando se mide el desfase A/V, entonces se mantiene dentro de ±150 ms (SPEC-005 §6).

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

- SPEC-002 — selección de Plugin desde Android.
- SPEC-003 — transporte y presentación de video.
- SPEC-005 — transporte y reproducción de audio.
- SPEC-006 — anuncio, liveness y selección de Plugin.

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
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Twinpad team | Initial draft |
| 0.2.0 | 2026-10-18 | Product owner + Twinpad team (CR-010) | Plugin publica medios; Server adapta/retransmite los medios de la fuente seleccionada y no captura ni sintetiza |
