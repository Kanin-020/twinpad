# Change Request — CR-043 — Conversión RGB8→YUV con SSSE3 y consumo NV12 directo del encoder hardware

| | |
|---|---|
| ID | CR-043 |
| Title | Codec Cemu: conversión YUV acelerada (SSSE3) y ruta NV12 directa para el encoder hardware |
| Type | `Optimization` |
| Status | `Applied` |
| Author | Twinpad team (agente) |
| Created | 2026-10-29 |
| Decided | 2026-10-30 |
| Decided by | Product owner (petición del owner: «Implementalo», tras el informe de cuellos de botella hacia el 100% de FPS) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | DES-004 (ruta codec del adaptador), SPEC-003 (sin cambios de contrato) |
| Sections | DES-004 §Cemu → codec; SPEC-003 §4.3 sin alterar |
| Current version → new version | DES-004 1.8.0 → 1.10.0 (incluye CR-041 en 1.9.0); SPEC-003 sin cambio de versión por este CR |

## 2. Requested change

**Current (al abrir el CR):** la conversión RGB8→YUV es escalar por píxel en un hilo a 1080p (2,07 M píxeles luma + 0,5 M bloques chroma por frame) y corre también con encoder hardware; la ruta hardware además reconstruye el NV12 por píxel (Y + interleave UV) dentro del encoder desde un I420 + copia extra.

**Applied (2026-10-30):**

```text
Dispatch runtime en x86/x64: la conversión RGB8→YUV (I420 y NV12) usa una
ruta SSSE3 (pshufb + madd BT.601, un dword por píxel en luma y croma) cuando
el host la reporta; sin soporte, el fallback escalar conserva el comportamiento
exacto previo. La aritmética y el redondeo por píxel (y (+128>>8+16),
u/v (+128>>8+128), promedio floor /4 del bloque 2x2) son idénticos bit a bit
entre ambas rutas (TEST-0105).
Además, el encoder hardware consume NV12 directamente: Encode() apunta los
data[] del frame software al buffer consolidado del caller (zero-copy, sin
reserva propia ni interleave por píxel); los candidatos hardware (NVENC/QSV/
AMF/VAAPI/VideoToolbox) y la ruta CPU usan los mismos buffers del caller.
Sin cambio de wire: el contrato game_frame/stream_start queda intacto.
```

## 3. Motivation

Informe de cuellos de botella hacia el 100% de FPS de Cemu→Android (octubre 2026): la conversión RGB→YUV escalar era el mayor coste CPU por frame restante (B1, ~8 ms a 1080p, incompatible con 60 fps en un hilo), y el doble transporte CPU↔GPU del encoder hardware (B2, ~6,2 MB extra por frame al reconstruir NV12 desde I420) sumaba latencia sin necesidad.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004/005 | No | Sin cambio de wire ni de contrato del stream | None |
| SPEC-003 | No | Wire y cadencia intactos | None |
| ARCH | No | Cambio interno del adaptador Cemu | None |
| DES-004 | Yes | Ruta codec: conversión SSSE3 y NV12 directo | Updated 1.10.0 |
| DES-003 | Yes | Nota del EncoderState (contrato CR-041 + ruta CR-043) | Updated 0.3.0 |
| TEST | Yes | TEST-0105 (identidad bit-exacto + benchmark) | Added |
| Code | Yes | `TwinpadClient.cpp/.h` (dispatcher YUV, encoder NV12); `VulkanRenderer` sin cambios | Applied |

## 5. Options considered

1. **SSSE3 con dispatch runtime + NV12 directo (elegida)** — usa solo instrucciones pre-SSE4.1 (pshufb/madd), mantiene bit-exactitud con el escalar y elimina el doble transporte; fallback seguro sin soporte.
2. Librería externa (libyuv) — dependencia nueva que no existía en el build de Cemu para esta ruta.
3. Avanzar RGB al encoder y dejar la conversión al VPP del driver — depende del driver/escalador del backend, con colores posiblemente distintos por driver.

## 6. Decision

- **Outcome:** Applied (petición del owner del 2026-10-30).
- **Rationale:** quita el mayor coste CPU por frame manteniendo resultados idénticos bit a bit respecto a la ruta escalar (verificación TEST-0105) y reduce el transporte del encoder hardware; sin riesgo de wire ni de colores.
- **Rollout:** la verificación física en banco (CR-042 "After" + fps efectivos) queda pendiente; la identidad bit-exacto y el benchmark ya están registrados en TEST-0105.

## 7. Execution checklist

- [x] Product owner approval recorded (2026-10-30)
- [x] Target document(s) updated (DES-004 1.10.0, DES-003 0.3.0)
- [x] Dependent documents updated (traceability TEST-0105, más TEST-073/082 por CR-041)
- [x] `_index.md` updated
- [x] Code change applied (`TwinpadClient.cpp/.h`); build completo del repo de Cemu pendiente de ejecución

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-30 | Twinpad team (agente) | Opened and Applied |
