# Change Request — CR-003 — PCM de audio del juego vía SDK Twinpad

| | |
|---|---|
| ID | CR-003 |
| Title | Publicar PCM del juego por el SDK Twinpad |
| Type | `New behavior / Scope change` |
| Status | `Approved` |
| Author | Twinpad team |
| Created | 2026-10-17 |
| Decided | 2026-10-17 |
| Decided by | Product owner (usuario) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-005, SPEC-006, REQ-006, DES-006 (si se asigna) |
| Sections | SPEC-005 §3/§4/§7; SPEC-006 §4/§7/§9; SDK API |
| Current version → new version | SPEC-005 1.0.0 → 1.1.0; SPEC-006 1.0.0 → 1.1.0; REQ-006 0.1.0 → 0.2.0 |

## 2. Requested change

**Current:** SPEC-005 §3 requiere audio disponible desde el Plugin, pero SPEC-006 y la API actual de `TwinpadGame` no definen mensaje ni método para entregar PCM; el SDK actual solo anuncia juegos y publica frames.

**Approved:** El SDK ofrece una fuente callback consumida en un hilo dedicado. Usa una segunda conexión TCP localhost separada del canal de frames (puerto 8422) con NDJSON `game_audio_start/chunk/stop`, asociada al `game_id` anunciado por la conexión primaria. `game_audio_start` declara `rate`, `channels`, `sample_fmt=s16le`; cada chunk lleva `seq`, `ts` monotónico del proceso, y PCM `data` base64url con ≤16 KiB decodificados; `game_audio_stop` finaliza la fuente. Una sola fuente por juego/capability `audio`; formatos válidos 8k–192k, mono/estéreo. El callback alimenta una cola acotada de 8 chunks; al llenarse se descarta PCM antiguo, sin bloquear el hilo que publica frames. El Server valida vida/capability/duplicados y reenvía únicamente el audio del juego actualmente seleccionado por el stream de video. En la sesión de Android, el escritor serializado prioriza video ante cada límite de mensaje y drena audio pendiente solo cuando video no espera. Sin fuente no se anuncia audio. Cierre del proceso o conexión detiene la fuente con `audio_stop` cuando el socket de sesión siga vivo; no rompe pairing.

## 3. Motivation

El binario del servidor actualmente genera un seno sintético de 440 Hz de forma perpetua para cada sesión, aunque el juego no haya enviado audio. Esto causa el beep reportado y no transmite el audio del juego. SPEC-005 §3 identifica el Plugin como origen y SPEC-005 §5 exige silencio cuando no existe `audio_start` vigente; la API del SDK todavía carece de entrega PCM.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-006 | Sí | Añadido AC-4 de entrega PCM desde SDK | Actualizado a 0.2.0 |
| SPEC-005 | Sí | Origen, IPC separado, cola drop-old y prioridad de video | Actualizado a 1.1.0 |
| SPEC-006 | Sí | Segunda conexión localhost de audio y ciclo de vida | Actualizado a 1.1.0 |
| ARCH | Sí | SDK → Server por IPC separado; salida de sesión prioriza video | Sin cambio de límites: contrato reflejado en SPEC-005/006 |
| DES-006 | Posible | El diseño IPC/SDK necesitará detalles | Crear o actualizar DES después de acordar el contrato |
| TEST | Sí | TEST-036 (contrato/SDK/server), TEST-037 (audio session/prioridad) |
| Code | Sí | Core protocol, SDK source callback, listener audio, cola drop-old y session writer prioritization |
| Data / migrations | No | No hay almacenamiento persistente | Ninguna |
| Integrations / contracts | Sí | Segunda conexión IPC en 127.0.0.1:8422 con mensajes `game_audio_*` |

## 5. Options considered

1. Plugin del juego envía PCM al SDK (solicitada por el owner) — corresponde al origen indicado en SPEC-005 §3 y permite conocer exactamente qué juego produce audio.
2. Do nothing beyond removing the synthetic tone — elimina el beep incorrecto, pero el stream real sigue sin implementarse.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** El Product owner aprobó explícitamente callback dedicado, TCP IPC separado, mensajes `game_audio_*`, máximo 16 KiB por chunk, cola drop-old de 8 chunks y prioridad video antes de cada mensaje. No se requiere mantener TCP compartido entre IPC y frames, ni aceptar head-of-line blocking del productor.
- **Rollout:** Cambios documentales y de implementación juntos en esta iteración; audio compatible opt-in por capability `audio`.

## 7. Decisiones aprobadas por el owner

- Fuente callback dedicada; máximo una fuente PCM por juego.
- Segunda conexión TCP localhost de audio en puerto 8422; mensajes NDJSON separados `game_audio_start/chunk/stop` y `game_id`.
- PCM `s16le`, 8k–192k, mono/estéreo, ≤16 KiB por chunk decodificado.
- Cola de ocho chunks; drop-old sin bloqueo del hilo productor de frames.
- Server acepta audio solo para juego vivo con capability `audio`; audio solo del juego actualmente seleccionado para video.
- Mensajes de sesión Android: video antes de audio en cada frontera de mensaje; pendiente el mensaje en curso no se interrumpe.
- Fin de callback o cierre del proceso/socket emite `audio_stop` best-effort si la sesión sigue activa.
- Timestamp IPC son milisegundos monotónicos de origen; el server genera el timestamp de envío de sesión según SPEC-005.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-17 | Twinpad team | Opened from report of continuous synthetic beep and missing SDK PCM path |
| 1.0.0 | 2026-10-17 | Product owner | Approved callback API, independent audio IPC and drop-old queue with video priority |
