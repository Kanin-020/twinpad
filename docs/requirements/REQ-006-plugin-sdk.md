# REQ-006 — Integración vía SDK Twinpad (plugin modelo B)

| | |
|---|---|
| ID | REQ-006 |
| Title | Integración vía SDK Twinpad (modelo B) |
| Version | 0.3.0 |
| Status | `Draft` |
| Priority | `Must` |
| Owner | Eng lead |
| Refines | CTX scope item "Integración por plugin (SDK Twinpad)" y CTX-C7 |
| Implemented by | `-` |
| Last updated | 2026-10-18 |

## 1. Objective

La visión de la entrevista es que "cualquier app puede integrarse vía plugin". Para juegos que pueden modificar su código, el SDK Twinpad es el camino oficial: el juego integra la librería y Twinpad Core detecta que hay un juego compatible corriendo y empieza el flujo de datos.

## 2. Description

Twinpad provee un SDK headless que un Juego / Aplicación huesped integra para anunciar un Plugin, publicar audio y Framebuffers de la Segunda pantalla, y consultar mediante polling el último estado de inputs de la sesión que Android vinculó con ese Plugin. La integración host del Plugin inicia o reutiliza el Servidor PC y presenta el QR de Pairing. El Servidor PC retransmite/adapta medios e inputs; no captura ni genera contenido audiovisual. Android elige el Plugin destino de su sesión.

## 3. Actors

| Actor | Role in this requirement |
|---|---|
| Juego / Aplicación huesped | Integra el SDK y aloja la integración host del Plugin |
| Plugin | Anuncia su presencia, publica medios y consume inputs usando SDK Twinpad |
| Integración host del Plugin | Inicia/reutiliza el Servidor PC y presenta el QR de vinculación |
| Twinpad Core | Detecta el juego compatible y coordina el flujo |
| PC Host | Ejecuta Core y el juego integrado |

## 4. Preconditions

- La integración host del Plugin SDK solicita iniciar o reutilizar el Servidor PC y presenta el QR.
- Juego con SDK integrado, en ejecución en el PC; el SDK anuncia el Plugin tras conectarse.

## 5. Behavior expected (summary)

- La integración host inicia/reutiliza el Servidor PC y presenta el QR; el SDK conectado anuncia automáticamente su Plugin y Twinpad lo lista como vivo.
- El juego puede publicar contenido de Segunda pantalla a través del SDK.
- Si declara capacidad `audio`, el juego puede entregar PCM real mediante una fuente callback del SDK y Twinpad lo reproduce en Android sin que la fuente retrase la publicación de frames.
- Android elige a qué Plugin vivo vincular su sesión; el Plugin recibe el estado más reciente de inputs del teléfono mediante polling del SDK.

## 6. Acceptance criteria

- [ ] AC-1: Dado la integración host de un Plugin SDK, cuando solicita iniciar/reutilizar el Servidor PC y presenta el QR, entonces tras Pairing el SDK conectado anuncia el Plugin y la app Android recibe su identidad en la lista de Plugins vivos.
- [ ] AC-2: Dado una sesión Android y uno o más Plugins vivos, cuando Android elige uno (automáticamente si solo hay uno), entonces el Servidor confirma la selección y retransmite únicamente los medios de ese Plugin.
- [ ] AC-3: Dado un Plugin seleccionado y un `input_report` válido recibido desde Android, cuando el juego consulta el SDK, entonces obtiene el estado completo más reciente de la Signature HID; los estados intermedios pueden coalescerse.
- [ ] AC-4: Dado un Plugin vivo con capacidad `audio`, cuando publica PCM `s16le`, entonces el Android de la sesión que lo seleccionó reproduce ese audio; ante congestión se descartan chunks PCM antiguos sin bloquear la publicación de frames, y sin fuente PCM permanece en silencio.
- [ ] AC-5: Dado un Plugin seleccionado que deja de estar vivo, cuando el Servidor publica la lista vigente, entonces Android deja de presentar medios de esa fuente y actualiza la selección según SPEC-002/SPEC-006.
- [ ] AC-6: Dado un host que inicia el Servidor PC para su Plugin, cuando el proceso no existe, entonces se inicia; si ya está activo, se reutiliza y el host presenta el QR vigente sin duplicar procesos.

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

- SPEC-006 — detección, ciclo de inicio host, selección y SDK.
- SPEC-002 — Pairing y selección Android.

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
| 0.3.0 | 2026-10-18 | Product owner + Twinpad team (CR-010) | Plugin origina A/V; el host inicia/reutiliza Server y presenta QR; Android elige destino y SDK consulta por polling input; Servidor solo retransmite/adapta |
