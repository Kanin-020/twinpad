# Change Request (CR) — CR-027 — Twinpad

| | |
|---|---|
| ID | CR-027 |
| Title | Bitrate objetivo del encoder H.264 configurable (drawer, env y reenvío a Cemu) |
| Type | `Behavior change` |
| Status | `Applied` |
| Author | Product owner + Twinpad team |
| Created | 2026-10-27 |
| Decided | 2026-10-27 |
| Decided by | Product owner (petición expresa en esta iteración) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-002 §11, SPEC-003 §3/§8.1/§11, `testing/traceability.md` |
| Sections | Ajuste del drawer, `video_config`, encoder de Cemu |
| Current version → new version | SPEC-002 `1.5.0 → 1.6.0`; SPEC-003 `1.7.0 → 1.8.0` |

## 2. Requested change

**Current:**

```text
El drawer del drawer de ajustes expone un slider de "calidad" (JPEG-q); el
contrato wire `video_config` por sesión (resolución/fps, fila histórica de
traceability 2026-10-09) no incluye bitrate. La variable de entorno
TWINPAD_JPEG_QUALITY perdió sentido tras la migración a H.264 (CR-014):
los encoders de Cemu fijan 8'000 kbps hardcoded (kOpenH264Bitrate).
```

**Proposed:**

```text
- El drawer de ajustes sustituye el slider de calidad JPEG por uno de
  bitrate objetivo: 1.000–30.000 kbps (slider 0…29000 + offset 1.000),
  por defecto 8.000. Se persiste en SharedPreferences y se envía en
  video_config como bitrate_kbps en caliente.
- El Server lee TWINPAD_VIDEO_BITRATE_KBPS (1000–30000, default 8000,
  clamp idéntico), conserva el valor en VideoConfig y reenvía fps +
  bitrate_kbps al adaptador Cemu: set al conectar y en cada video_config
  de sesión.
- Cemu: nodo TwinpadClient añade SetCaptureBitrate/GetCaptureBitrate
  (kbps, clamp 1000–30000, default 8000). El parse de video_config
  acepta bitrate_kbps. El encoder persistente se reinicia cuando cambia
  el bitrate; FFmpeg hardware usa bit_rate/rc_max_rate/rc_buffer_size =
  kbps × 1000 y OpenH264 usa iTargetBitrate/iSpatialBitrate/iMaxSpatial
  Bitrate con el mismo valor.
- El ajuste sustituye al de calidad JPEG (TWINPAD_JPEG_QUALITY queda
  sin uso y discontinuado): con H.264 la calidad se controla por bitrate.
```

## 3. Motivation

El owner pidió quitar el ajuste JPEG ("ya no es relevante") y añadir el ajuste de bitrate. Tras CR-014 la Segunda pantalla es H.264; el bitrate objetivo es el control de calidad real y hoy estaba fijado en código sin canal de ajuste.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004/REQ-005 | no | El AC vigente no fija el mecanismo de calidad | none |
| SPEC-002 | yes | Slider del drawer de calidad JPEG → bitrate | R15 + changelog 1.6.0 |
| SPEC-003 | yes | video_config reenviado a Cemu con bitrate_kbps; §8.1 el trigger admite bitrate nuevo | §3/§8.1 + changelog 1.8.0 |
| SPEC-004 | no | Signature HID y wire de input sin cambios | none |
| DES-004 | yes | Cemu registra el bitrate del cliente y lo pasa al encoder | nota en changelog futura (sin bump) |
| Code | yes | `server/video.rs`, `server/games.rs`, `server/main.rs`, drawer Android (`AndroidSettingsStore`, `SettingsDrawerController`, layouts/strings) y `Cafe/Twinpad/TwinpadClient.{h,cpp}` | hecho en esta iteración |
| Tests | yes | Server: parser/env/clamps y reenvío (TEST-068/TEST-069 actualizados); Android: `AndroidSettingsStoreTest` (TEST-069); Cemu: TEST-082 sin cambios de contrato | filas actualizadas |

## 5. Options considered

1. Bitrate objetivo configurable por drawer + env con clamp uniforme 1.000–30.000 kbps y reinicio de encoder en Cemu — elegida: control real de calidad en H.264 sin transcodificar en el server.
2. Mantener 8.000 kbps hardcodeados — no cumple la petición del owner.

## 6. Decision

- **Outcome:** `Approved` (owner-requested)
- **Rationale:** un único parámetro de calidad alineado con el codec vigente; el permiso de cambio del encoder queda en el adaptador Cemu (igual que fps/resolución).
- **Rollout:** same release (drawer, server y Cemu se despliegan juntos).

## 7. Execution checklist

- [x] Target document(s) updated (version bump y changelog con este CR)
- [x] Dependent documents updated (impact table)
- [x] Tests updated (TEST-068/069; sin TEST nuevos)
- [x] `testing/traceability.md` rows updated (first table + change history)
- [x] Indexes (`_index.md`) updated
- [x] Code change applied (server, Android drawer, Cemu adaptador)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-27 | Product owner + Twinpad team | Opened (petición del owner) |
| 1.0.0 | 2026-10-27 | Product owner | Approved: bitrate objetivo 1.000–30.000 kbps default 8.000 |
| 1.1.0 | 2026-10-27 | Twinpad team | Applied: código server/Android/Cemu y docs |
