# Change Request — CR-003 — PCM de audio del juego vía SDK Twinpad

| | |
|---|---|
| ID | CR-003 |
| Title | Publicar PCM del juego por el SDK Twinpad |
| Type | `New behavior / Scope change` |
| Status | `Open` |
| Author | Twinpad team |
| Created | 2026-10-17 |
| Decided | — |
| Decided by | — |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-005, SPEC-006, REQ-006, DES-006 (si se asigna) |
| Sections | SPEC-005 §3/§4/§7; SPEC-006 §4/§7/§9; SDK API |
| Current version → new version | Sin cambios hasta aprobación |

## 2. Requested change

**Current:** SPEC-005 §3 requiere audio disponible desde el Plugin, pero SPEC-006 y la API actual de `TwinpadGame` no definen mensaje ni método para entregar PCM; el SDK actual solo anuncia juegos y publica frames.

**Proposed:** Extender el SDK Twinpad para que el Plugin del juego pueda publicar PCM `s16le` con `rate` y `channels` conforme a SPEC-005 §4. Definir el framing IPC, límites y tamaño de chunk, orden, timestamps, sincronía de múltiples productores, comportamiento ante backpressure, comienzo/fin/cambio de formato, liveness y prioridad frente a frames antes de implementarlo. El servidor solo reenviará audio que haya recibido de una fuente viva; en ausencia de PCM real permanecerá en silencio.

## 3. Motivation

El binario del servidor actualmente genera un seno sintético de 440 Hz de forma perpetua para cada sesión, aunque el juego no haya enviado audio. Esto causa el beep reportado y no transmite el audio del juego. SPEC-005 §3 identifica el Plugin como origen y SPEC-005 §5 exige silencio cuando no existe `audio_start` vigente; la API del SDK todavía carece de entrega PCM.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-006 | Sí | Capacidad existente `audio` debe corresponder a una API de entrega | Precisar acceptance criteria y alcance |
| SPEC-005 | Sí | El origen y el flujo principal deben alinearse con la disponibilidad de fuente real | Definir flujo de ausencia y entrega del Plugin |
| SPEC-006 | Sí | IPC SDK actual solo incluye anuncio y frames | Definir mensaje/ciclo de audio |
| ARCH | Sí | Captura y envío de audio se asignarán entre SDK/Core/Server | Actualizar responsabilidades si cambia la frontera |
| DES-006 | Posible | El diseño IPC/SDK necesitará detalles | Crear o actualizar DES después de acordar el contrato |
| TEST | Sí | Requiere tests de API, contrato IPC y sesión E2E | Reservar TEST ID al aceptar diseño |
| Code | Sí | SDK, core protocol, server/game registry, session forwarding y tests | Implementar en cambio separado tras aprobación |
| Data / migrations | No | No hay almacenamiento persistente | Ninguna |
| Integrations / contracts | Sí | Se amplía el protocolo IPC local del SDK | Versionado/compatibilidad por definir |

## 5. Options considered

1. Plugin del juego envía PCM al SDK (solicitada por el owner) — corresponde al origen indicado en SPEC-005 §3 y permite conocer exactamente qué juego produce audio.
2. Do nothing beyond removing the synthetic tone — elimina el beep incorrecto, pero el stream real sigue sin implementarse.

## 6. Decision

- **Outcome:** `Deferred`
- **Rationale:** El owner eligió el origen SDK, pero el contrato de PCM sigue sin especificar framing IPC, fragmentación, lifecycle y backpressure. No se debe inventar esos comportamientos ni modificar un SPEC Approved sin decisión explícita. Se implementará el silencio cuando no haya fuente real como corrección acotada; la entrega real queda bloqueada hasta aprobar esta CR con las decisiones pendientes.
- **Rollout:** Tras aprobación, actualizar los documentos contractuales y luego implementar la ampliación del SDK en una iteración separada.

## 7. Decisiones pendientes para aprobar

- API del SDK: método bloqueante de escritura, callback de fuente o producer thread.
- Si `audio_start/chunk/stop` del SPEC se reutilizan en IPC o se define un mensaje IPC `game_audio` separado.
- Tamaño/fragmentación PCM sobre IPC y política de backpressure.
- Un único productor de audio por juego frente a selección/mezcla de varios.
- Semántica de `AudioStop` en cierre/crash y reconexión del SDK.
- Coordinación/sincronía con el stream de video `game_frame`.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-17 | Twinpad team | Opened from report of continuous synthetic beep and missing SDK PCM path |
