# Change Request — CR-036 — Continuidad del video de la Segunda pantalla al cambiar de escena

| | |
|---|---|
| ID | CR-036 |
| Title | Mantener el stream de video sin congelados ni tirones cuando el juego cambia de escena |
| Type | `Behavior change` + `Defect fix` |
| Status | `Applied` |
| Author | Twinpad team (agente) |
| Created | 2026-10-28 |
| Decided | 2026-10-29 |
| Decided by | Product owner (conversación; elección registrada abajo) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-003 (video), SPEC-006, DES-004, traceability |
| Sections | SPEC-003 cadencia y reinicio del encoder; SPEC-006 publicación de frames; DES-004 ruta de captura/readback DRC |
| Current version → new version | SPEC-003 1.7.0 → 1.8.0; SPEC-006 1.20.0 → 1.21.0; DES-004 1.7.0 → 1.8.0 |

## 2. Requested change

**Current (al abrir el CR):**

```text
El path de captura Twinpad publica solo cuando el juego dibuja al target DRC y
descartaba en silencio los frames cuyo formato/textura del DRC no coincidía con
los formatos soportados o excedía el buffer de readback. EncodeGamePadFrame no
recrea el encoder por cadencia (ya aplicado); sin frame nuevo no se publica nada.
```

**Applied (2026-10-29):**

```text
Mientras el Plugin Cemu esté conectado, si no llega un frame nuevo del DRC dentro
de 100 ms, Cemu repite el último frame ya escalado (mismo contrato `game_frame`;
el wire no cambia). El encoder no se recrea por cadencia medida: la cadencia solo
alimenta la metadata del rate-control; el encoder se recrea cuando cambian las
dimensiones o el bitrate configurados (existente). El readback Vulkan del DRC
acepta R8G8B8/R8G8B8A8/B8G8R8A8 (unorm/srgb, con intercambio B↔R para BGRA) y el
buffer de readback cubre un DRC hasta 4K RGBA/BGRA. Los descartes ya no son
silenciosos: se registran una vez por sesión en el log con el motivo.
```

## 3. Motivation

El owner reporta tirón/congelado al cambiar de escena (Wind Waker, BotW con install SELECT) y ausencia total de video en la Segunda pantalla con audio/táctil activos.

**Medición (2026-10-29, `[TwinpadPerf]`, binario ventilador idéntico en ambas sesiones):**

| Sesión | Ventana | Observación |
|---|---|---|
| BotW 12:21–13:46 (85 min) | 5 s | `drc_callback`≈66–67 en todas las ventanas, nunca 0: el juego siguió dibujando el DRC; no hubo congelado por falta de dibujo |
| BotW 12:21–13:46 | 5 s | `frame_sent`≈33–39 (~7–8 fps); `openh264`≈86–118 ms avg (max 293), `pixel_convert+i420`≈43 ms, `encode_pipeline`≈110–135 ms, `readback_ready`≈76 ms ⇒ ~200 ms/frame end-to-end en OpenH264 CPU |
| Wind Waker HD 13:47–14:11 | 5 s | `drc_callback`≈146–151, `capture_accepted`≈146–151 y **cero** eventos de readback en toda la sesión: los retornos silenciosos del readback Vulkan (`m_twinpadReadbackSlotCount==0` o formato/geometría no soportada) descartaban todos los frames |

Diagnóstico documentado: el build enlaza `libavcodec` sin backend de dispositivo VAAPI (los encoders se registran pero `av_hwdevice_ctx_create` VAAPI devuelve −1), por lo que la sesión siempre cae a OpenH264 CPU. Ese cuello de botella de cadencia se trata en CR-039 (encoder por hardware), no aquí.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004/005 | Yes | Continuidad del video (sin frames muertos ante huecos del DRC) | Cumplido por repetición |
| SPEC-003 | Yes | Regla de recreación de encoder (no por cadencia) | Actualizado 1.8.0 |
| SPEC-006 | Yes | Regla de publicación: repetición ≤100 ms; formatos DRC aceptados | Actualizado 1.21.0 |
| DES-004 | Yes | Ruta readback Vulkan (formatos BGRA, buffer 4K, fail-loud) | Actualizado 1.8.0 |
| TEST | — | Sin TEST ID nuevo: los tests vigentes del wire no cambian (contrato `game_frame` intacto) | N/A |
| Code | Yes | `VulkanRenderer` (formatos/tamaño/límites), `TwinpadClient` (pixel format Bgra8 + conversión), diagnóstico fail-loud | Aplicado |
| Integrations / contracts | No | Sin cambio de wire | None |

## 5. Options considered

1. Repetir el último frame en huecos (≤100 ms) y no recrear el encoder por cadencia — **aplicada**: elimina el corte sin nuevo protocolo.
2. No hacer nada — los huecos y el video ausente de Wind Waker permanecen.
3. Reconstruir la dependencia ffmpeg con VAAPI habilitado y usar encoder hardware — separada en **CR-039**; resuelve el throughput (~7–8 fps → 30+), no la continuidad ante huecos.

## 6. Decision

- **Outcome:** Applied (owner-requested, 2026-10-29). El owner eligió además el camino del encoder por hardware para el cuello de bottleneck de cadencia CPU (CR-039).
- **Rationale:** la medición descartó congelado por falta de dibujo del juego y el código ya no recreaba el encoder por cadencia; el defecto real de video ausente (Wind Waker) era el rechazo silencioso del readback por formato BGRA/techo del buffer.
- **Rollout:** build `Cemu_release` del 2026-10-29 14:0x con el fix + diagnóstico; verificación física pendiente de relanzamiento del emulador por el owner.

**Reglas vigentes tras el CR:** repetición del último frame del DRC cada ≤100 ms con plugin conectado; sin recreación de encoder por cadencia (solo metadata de rate-control); readback acepta RGB/RGBA/BGRA (unorm/srgb) hasta 4K; todo descarte de readback queda registrado en el log.

## 7. Execution checklist

- [x] Product owner approval recorded (2026-10-29)
- [x] Medición con `[TwinpadPerf]` durante y fuera de transiciones (BotW 85 min; Wind Waker sesión de fallo)
- [x] Target document(s) updated (SPEC-003 1.8.0, SPEC-006 1.21.0, DES-004 1.8.0)
- [x] Code change aplicado (submódulo Cemu: formatos BGRA, buffer 4K, fail-loud, pixel format Bgra8)
- [x] `_index.md` actualizado (CR-039 registrado)
- [ ] Verificación física del relanzamiento (owner): Wind Waker produce `readback_queued/completed` y frames en el teléfono; sin líneas `READBACK rechazado`/`sin slots`
- [ ] Smoke del toggle SELECT/documentado si el owner quiere el toggle desde el teléfono (requiere nuevo CR)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-28 | Twinpad team (agente) | Opened |
| 0.2.0 | 2026-10-29 | Twinpad team (agente) | Medición registrada; decisión del owner; código aplicado (BGRA/4K/fail-loud + repetición 100 ms ya existente); SPEC-006 1.21.0, SPEC-003 1.8.0, DES-004 1.8.0; cuello de bottleneck CPU derivado a CR-039; Status → Applied |
