# Change Request — CR-048 — Estabilidad del stream de video: pacing de presentación y envío desacoplado

| | |
|---|---|
| ID | CR-048 |
| Title | Estabilidad del stream de video (tirones a 60 fps sin déficit de fps) |
| Type | `Defect fix` |
| Status | `Applied` |
| Author | Twinpad team (agente) |
| Created | 2026-10-31 |
| Decided | 2026-10-31 |
| Decided by | Product owner (conversación; petición directa de estabilidad) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | DES-004 |
| Sections | DES-004 ruta de captura/readback del DRC y consumer Android del stream |
| Current version → new version | DES-004 1.11.0 → 1.12.0 |

## 2. Requested change

**Current (al abrir el CR):**

```text
El FrameLoop de Cemu hace pixel-convert → scale → encode → base64 → SendLine
bloqueante con el mutex del socket IPC en el MISMO hilo: la backpressure/varianza
TCP estira y comprime la cadencia de encode. En Android, H264SurfaceDecoder
presenta cada buffer con releaseOutputBuffer(index, render=true) inmediato:
cuando la red entrega N frames juntos, la vista los muestra back-to-back y
luego se congela hasta el próximo group (tirones aunque fps/bitrate medidos
coincidan; reflejado en el addon de stage MEDIA_CODEC y presentación).
```

**Applied (2026-10-31):**

```text
Cemu: las líneas game_frame pasan por una cola acotada (drop-old, máx 4)
consumida por un hilo de envío dedicado (VideoSendLoop); las métricas de
envío (IPC_SEND, FRAME_SENT, lastFrameSentMs) registran el envío real.
El wire y las métricas existentes no cambian de forma.
Android: el decoder mide el intervalo de llegada de las AU (EMA α=1/4,
clamp 4–100 ms) y programa la presentación con releaseOutputBuffer(index,
renderTimestampNs) cuando un buffer sale ≥2 ms antes del siguiente slot;
en caso contrario se presenta de inmediato y el ancla se resincroniza al
reloj actual (nunca acumulativa). El pacing usa SOLO el reloj de llegada del
teléfono (CR-029 intacto: no depende de metadata de fps del wire).
```

## 3. Motivation

El owner reporta tirones visibles a 60 fps con bitrate/fps-llegados/fps-renderizados
coincidiendo, y latencia ocasional alta de la imagen. Medición documentada en
CR-036 ya mostró varianza grande por frame (encode 110–135 ms CPU; readback ~76 ms);
con VAAPI activo la varianza del SEND TCP (bloqueo del hilo + contención por
m_primarySocketMutex) y la entrega en ráfagas hacia el codec son las fuentes
restantes de jitter de presentación, no el throughput.

## 4. Impact analysis

| Área | Documents to update | Implements | Version change |
|---|---|---|---|
| Cemu plugin | DES-004 | send descoplado del encode | 1.12.0 |
| Android | DES-004 | pacing de presentación por arrival EMA | 1.12.0 |

- Wire: sin cambio (mismas líneas, misma ruta acoplada por TCP_NODELAY del servidor).
- SPEC-003: intacta (no define el pacing de presentación del receptor).
- Riesgos: decoders Android que ignoren renderTimestampNs quedan con la
  presentación inmediata previa (sin regresión); el hilo de envío añade una
  cola de 4 líneas (~ longitud media de 500 KB/frame ≈ 8 MB máx en RAM).

## 5. Implementation notes

- `TwinpadClient.cpp/.h`: `VideoSendLoop`, `m_videoSendQueue` (deque drop-old
  4), notify/join limpio en Start/Stop, métricas movidas al envío real.
- `H264SurfaceDecoder.kt`: mapa de arrivadas por `presentationTimeUs`,
  EMA de intervalo, ancla de presentación con umbral early/late, reset del
  estado en flush/reconfigure.

## 6. Verification / decision log

| | |
|---|---|

→ Gate Cemu: `ninja Cemu_release` en verde. Gate Android: `detekt` en verde.
La verificación física de estabilidad en pantalla queda al owner (60 fps
Nintendo Wind Waker/BotW durante ≥5 min sin tirones percibidos).

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-31 | Twinpad team (agente) | Created + applied pending owner physical verification |
