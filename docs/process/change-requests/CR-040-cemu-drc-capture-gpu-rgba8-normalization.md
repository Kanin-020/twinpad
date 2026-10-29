# Change Request — CR-040 — Normalización en GPU del readback del DRC a RGBA8

| | |
|---|---|
| ID | CR-040 |
| Title | Captura del DRC: cualquier formato renderizado por el juego se normaliza en GPU a RGBA8 antes del readback |
| Type | `New behavior` |
| Status | `Applied` |
| Author | Twinpad team (agente) |
| Created | 2026-10-29 |
| Decided | 2026-10-29 |
| Decided by | Product owner (conversación; elección registrada abajo) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | DES-004 (ruta de captura/readback), SPEC-006 (regla de publicación) |
| Sections | DES-004 §readback; SPEC-006 §3 (canal Plugin→Servidor, regla del readback) |
| Current version → new version | DES-004 1.8.0 → 1.9.0; SPEC-006 1.21.0 → 1.22.0 |

## 2. Requested change

**Current (al abrir el CR):**

```text
El readback Vulkan del DRC acepta R8G8B8/R8G8B8A8/B8G8R8A8 (unorm/srgb). Un DRC
en otro formato (p. ej. Wind Waker HD USA renderiza su GamePad en
VK_FORMAT_A2B10G10R10_UNORM_PACK32) es rechazado con bpp=0 y la Segunda pantalla
se queda sin video: solo se loguea el primer descarte y el stream nunca abre
(sin stream_start → HUD 0 fps rec / «Sin segunda pantalla activa»).
```

**Applied (2026-10-29):**

```text
Si el formato de la superficie DRC está en el conjunto aceptado
(R8G8B8/R8G8B8A8/B8G8R8A8, unorm/srgb) se conserva el camino rápido actual
(copia directa imagen→buffer, byte idéntico). Para cualquier otro formato
blitable, el readback normaliza en GPU: vkCmdBlitImage de la superficie DRC
(filter NEAREST, sin escala) hacia una imagen RGBA8 intermedia por slot
(UNORM o SRGB según la transferencia de la superficie fuente; destino
3840×2160 máx., igual techo que el buffer de readback) y esa imagen es la que
se copia al buffer del slot; el slot registra el formato ya normalizado
(R8G8B8A8_UNORM/SRGB → Rgba8/Rgba8Srgb) y el resto del pipeline no cambia.
Un formato sin soporte de blit (BLIT_SRC/BLIT_DST ausente por driver) sigue
descartado y fail-loud (una vez por sesión, con motivo). La activación de la
conversión se registra una vez por sesión en el log. Sin cambio de wire: el
contrato game_frame/stream_start queda intacto.
```

## 3. Motivation

Verificación física de CR-039 (2026-10-29, sesión real teléfono+Cemu+Wind Waker HD 854×480):

| Cadena | Resultado |
|---|---|
| Emparejamiento + announce + input + gyro | OK (`[TwinpadGyro] cemu_rx` a ~30 Hz; HUD con mando activo) |
| Captura DRC | `TwinpadPerf drc_callback≈102 capture_accepted≈102` por 5 s en todas las ventanas |
| Video | 0 eventos `readback_queued`, sin `stream_start`, HUD `0 fps · «Sin segunda pantalla activa»` |
| Encoder (hardware o CPU) | Nunca arranca: no hay slots de frames de partida |

Evidencia del rechazo (log de Cemu, una vez por sesión): `[TwinpadVideo] READBACK rechazado: fmt=64 w=854 h=480 bpp=0 copySize=0` — `VK_FORMAT_A2B10G10R10_UNORM_PACK32 = 64` (vulkan_core.h). CR-036 arregló el caso «cero eventos» para BGRA, pero el formato real del DRC de este título es 10 bits y quedó fuera del conjunto. En CPU cada formato nuevo exigiría su propio desempaquetado; en GPU la conversión es una vez y universal (mismo mecanismo que ya usa el path de screenshots de Cemu con `vkCmdBlitImage` a `R8G8B8A8_*`).

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004/005 | No | Contrato del stream intacto | None |
| SPEC-006 | Yes | Regla del readback: formatos no aceptados se normalizan en GPU (blit) a RGBA8 | Updated 1.22.0 |
| SPEC-003 | No | Wire y cadencia sin cambios (los frames ya llegan como Rgba8/Rgba8Srgb, caminos vigentes) | None |
| ARCH | No | Sin cambio de responsables/bordes (adaptador Cemu) | None |
| DES-004 | Yes | Ruta de captura: normalización GPU por slot; descrpt. fail-loud retenido | Updated 1.9.0 |
| TEST | No | Sin TEST ID nuevo: TEST-075 (selección de slots) no cambia; la ruta Vulkan no es host-testeable. Verificación física con WWHD | Traceability actualizada con historia |
| Code | Yes | `VulkanRenderer` (imagen RGBA8 por slot + blit + teclado de fail-loud); `TwinpadClient` sin cambios | Aplicado |
| Integrations / contracts | No | Sin cambio de wire | None |

## 5. Options considered

1. **Normalización en GPU a RGBA8 (elegida)** — universal para cualquier formato blitable del juego, reutiliza un patrón ya existente en el renderer (screenshots), sin coste CPU por frame y sin tocar el wire.
2. Desempaquetado CPU de A2B10G10R10 (CR-040 temprano) — corrige solo este formato; cada juego nuevo con otro formato repetiría el defecto.
3. RGB24 (3 B/pixel) — ahorra 25 % del readback pero la mayoría de los drivers no soporta blit a `R8G8B8` (el propio Cemu lo guarda tras `BLIT_DST_BIT`); el cuello real es el encode, no el copy.
4. No hacer nada — Wind Waker HD (y cualquier título con DRC 10-bit) queda sin video.

## 6. Decision

- **Outcome:** Applied (owner-requested, 2026-10-29): «Abre el CR e implémentalo, para la opción B».
- **Rationale:** máxima compatibilidad (todo formato blitable queda cubierto) con el menor costo de rendimiento (un blit extra en el mismo command buffer) y cero cambios de contrato.
- **Rollout:** build nuevo `Cemu_release`; verificación física con Wind Waker HD + teléfono (video en el HUD y encoder activo).

## 7. Execution checklist

- [x] Product owner approval recorded (2026-10-29)
- [x] Target document(s) updated (DES-004 1.9.0, SPEC-006 1.22.0)
- [x] Dependent documents updated (traceability con historia del CR)
- [x] Tests: sin TEST ID nuevo (ruta GPU no host-testeable); verificación física documentada
- [x] `_index.md` updated
- [x] Code change applied (`VulkanRenderer`)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-29 | Twinpad team (agente) | Opened and Applied |
