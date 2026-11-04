# Change Request (CR) — CR-074 — Twinpad

| | |
|---|---|
| ID | CR-074 |
| Title | Resolución del GamePad: permitir upscale y añadir la opción «Nativa» (w/h = 0/0) al drawer |
| Type | `Behavior change` |
| Status | `Applied` |
| Author | Buffy (agente) + Product owner |
| Created | 2026-11-04 |
| Decided | 2026-11-04 |
| Decided by | Product owner (petición expresa en esta iteración) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-002 §11 (R17), SPEC-003 §3/§8.1, SPEC-006 §16, `testing/traceability.md` |
| Sections | Efecto del drawer de resolución sobre el DRC (upscale y opción nativa) |
| Current version → new version | SPEC-002 `1.8.0 → 1.9.0`; SPEC-003 `1.10.0 → 1.11.0`; SPEC-006 `1.16.0 → 1.17.0` |

## 2. Requested change

**Current:**

```text
- CR-030: el Plugin escala el DRC a w/h SOLO hacia abajo; si w×h objetivo
  ≥ nativo se publica nativo (sin upscale).
- El drawer solo ofrece 960×540 / 1280×720 / 1920×1080; no existe forma
  de volver a la dimensión nativa del DRC una vez elegido un target.
- El servidor clampcea w/h a 16–3840 × 16–2160; un 0 del drawer no puede
  expresar «nativo».
```

**Proposed:**

```text
- Subir y bajar: el Plugin escala el DRC al objetivo siempre que
  difiera de la nativa (nearest-neighbor por eje, el mismo mecanismo
  de CR-030); el objetivo sigue siendo el framebuffer DRC y la
  resolución de la TV no cambia. El clamp máximo (16–3840 × 16–2160)
  y el requisito de paridad I420 se mantienen.
- Opción «Nativa» en el drawer: envía w/h = 0/0 en `video_config`;
  el servidor la acepta como centinela de nativo (passthrough sin
  clamp) y Cemu aplica su ruta 0→nativa ya existente
  (SetCaptureResolution). El default del drawer sigue siendo
  1920×1080 (CR-031).
- Un cambio de objetivo (incluido 0/0) reinicia el encoder por la
  detección existente de encoderConfigChanged; el stream regenera
  stream_stop/stream_start + IDR (SPEC-003 §8.1, sin cambio wire).
```

## 3. Motivation

El owner constató que con DRC nativo 854×480 las opciones 720p/1080p eran
inertes (CR-030 prohibía el upscale: la nativa siempre gana) y pidió:
«Añade upscale y una opción de resolución llamada nativa, que tome
directamente la que venga». El upscale reutiliza el mismo `ScaleRgb`
nearest-neighbor ya testeado (TEST-088), sin encoder/re-codificación
adicional en el servidor; la opción «Nativa» restaura la fuente sin
re-emparejar ni reiniciar el juego.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004 | no | El AC vigente no fija la política de upscale del DRC | none |
| SPEC-002 | yes | R17: opción «Nativa» y upscale permitido | R17 + changelog 1.9.0 |
| SPEC-003 | yes | §3: escala bidireccional y sentinela 0/0; §8.1 redacción | §3/§8.1 + changelog 1.11.0 |
| SPEC-006 | yes | §16: verificación del escalado bidireccional | fila + changelog 1.17.0 |
| DES-004 | yes | nota de implementación del upscale/nativo | nota en changelog futura (sin bump) |
| Code | yes | `server/src/main.rs` (`parse_video_config` passthrough 0/0), Cemu `TwinpadClientPipeline.cpp` + `TwinpadYuvConvert.cpp` (`ScaleRgb` guard), Android drawer/store | hecho en esta iteración |
| Tests | yes | TEST-0149 (server, 0/0 nativo), TEST-0150 (Cemu, upscale); TEST-088 redacción actualizada | filas actualizadas |
| Data / migrations | no | — | none |
| Integrations / contracts | no | wire `video_config` sin cambio de forma (0/0 es w/h válidos) | none |

## 5. Options considered

1. Upscale con el `ScaleRgb` existente + centinela 0/0 — elegida: cero
   wire nuevo, reutiliza el escalador nearest testeado y el estado
   `0 → nativo` que Cemu ya soporta desde CR-030.
2. Interpolación bilineal para el upscale — descartada: el nearest es
   la ruta ya probada en CPU (TEST-043, escalar y SSSE3) y el owner
   pidió efecto inmediato sin trabajo extra de cómputo.
3. Encoder por hardware a resolución nativa del selector de Cemu
   (graphic pack DRC) — descartado como requisito del CR: es un ajuste
   operativo de Cemu, no del protocolo; queda como alternativa manual.

## 6. Decision

- **Outcome:** `Approved` (owner-requested)
- **Rationale:** el owner pidió explícitamente upscale y la opción
  «Nativa»; el mecanismo elegido no toca el wire ni añade dependencias y
  mantiene el clamps de seguridad (máximo 3840×2160, paridad I420).
- **Rollout:** same release (server y Cemu se despliegan juntos; la app
  añade la opción «Nativa»).

## 7. Execution checklist

- [x] Target document(s) updated (version bump y changelog con este CR)
- [x] Dependent documents updated (impact table)
- [x] Tests added/updated (TEST-0149, TEST-0150; TEST-088 redacción)
- [x] `testing/traceability.md` rows updated (main table + change history)
- [x] Indexes (`_index.md`) updated
- [x] Code change applied (server + Cemu + Android)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-04 | Buffy (agente) | Opened (petición del owner: «Añade upscale y una opción de resolución llamada nativa») |
| 1.0.0 | 2026-11-04 | Product owner | Approved: upscale bidireccional del DRC con ScaleRgb + opción «Nativa» (w/h = 0/0) |
| 1.1.0 | 2026-11-04 | Buffy (agente) | Applied: docs + código server/Cemu/Android |
| 1.2.0 | 2026-11-04 | Buffy (agente) | Fix del reporte del owner («en nativa no se aplica contener y estirar»): el marco de la Segunda pantalla ahora sigue al frame REAL presentado (`effectiveVideoDims` + re-aplicación al cambiar el frame en `PairingActivity`, TEST-0152); sin cambio de docs de otra índole |
