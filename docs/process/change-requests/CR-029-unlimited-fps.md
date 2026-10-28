# Change Request (CR) — CR-029 — Twinpad

| | |
|---|---|
| ID | CR-029 |
| Title | FPS unlimited en la Segunda pantalla: sin tope 30/60 ni pacing por fps |
| Type | `Behavior change` |
| Status | `Applied` |
| Author | Product owner + Twinpad team |
| Created | 2026-10-28 |
| Decided | 2026-10-28 |
| Decided by | Product owner (petición expresa en esta iteración: "quitar ese limite de fps ahora sea solo unlimited") |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-002 §11, SPEC-003 §2/§4.1/§8.1/R5, SPEC-006 §7/R6, `testing/traceability.md` |
| Sections | Selector FPS del drawer, `stream_start.fps`, pacing Cemu/server, encoder |
| Current version → new version | SPEC-002 `1.6.0 → 1.7.0`; SPEC-003 `1.8.0 → 1.9.0`; SPEC-006 `1.14.0 → 1.15.0` |

## 2. Requested change

**Current:**

```text
El framerate del stream es un parámetro de control acotado: SPEC-003 §4.1
valida `stream_start.fps` en 1–60 y lo usa como objetivo de ritmo; el
drawer de ajustes expone un selector 30/60 y lo envía en `video_config`;
el Servidor lo reenvía a Cemu (clamp 1–60) y registra el ritmo de
captura (`set_cemu_capture_fps`); Cemu pausa la captura y la publicación
de frames a `interval = 1s/fps` (Renderer y PublishGamePadFrame) y el
relay del server pausa entre AU (`1000/fps`). Los encoders reciben
`framerate = fps` y timestamps sintéticos `frameIndex * 1000/fps`.
```

**Proposed:**

```text
- Sin pacing ni tope: ninguna capa fija el ritmo por un fps objetivo.
  Cemu captura y publica cada frame en cuanto captura+encoder lo
  permiten; el Servidor retransmite cada AU en cuanto llega (solo el
  finalize/poll vigente). `stream_start.fps` (SPEC-003 §4.1) pasa a
  metadata informativa del wire: campo con dominio natural u8 (0–255),
  sin validación de rango 1–60 y sin efecto de pacing; el valor 0
  significa "sin límite declarado" y esa es la constante que anuncia el
  Servidor. El campo no sale del wire (sin cambio de schema).
- `video_config` de sesión (SPEC-002 R15) deja de llevar `fps`: el
  drawer elimina su selector 30/60 y la persistencia local de fps; el
  Server ignora el campo `fps` si una app antigua lo envía.
- Cemu: se elimina el pacing de captura/publicación
  (Renderer::ShouldCaptureTwinpadFrame y
  TwinpadClient::PublishGamePadFrame) y el clamp 1–60. El encoder usa
  una tasa nominal interna (60) SOLO como metadata del rate-control de
  OpenH264/FFmpeg y produce marcas de tiempo reales (milisegundos desde
  la época del encoder). `SetCaptureFps` se retira junto con el resto
  de su estado.
- Server: `VideoConfig` pierde su campo fps y `TWINPAD_FPS` queda
  discontinuado; `MediaSource` pierde `fps`; el relay emite sin pacing
  y `stream_start` anuncia `fps=0`.
```

## 3. Motivation

El owner pidió quitar el tope de FPS ("quiero quitar ese limite de fps ahora sea solo unlimited"): el objetivo 30/60 ya no debe fijar el ritmo de la Segunda pantalla; cada fuente emite tanta tasa como sostengan captura+encoder.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004/REQ-005 | no | El AC no fija un framerate objetivo | none |
| SPEC-002 | yes | El drawer pierde el selector FPS | §11 + changelog 1.7.0 |
| SPEC-003 | yes | §4.1 fps pasa a metadata 0–255; §8.1/r5 sin pacing | §2/§4.1/§8.1/R5 + changelog 1.9.0 |
| SPEC-006 | yes | Cemu publica DRC sin tope 60 fps | §7/R6 + changelog 1.15.0 |
| DES-004 | yes | El adaptador cambia el rate de publicación | nota en changelog futura |
| Code | yes | `core/stream.rs`, `server/{video,games,media,main}.rs`, `Cafe/Twinpad/TwinpadClient.{h,cpp}`, `Renderer`, drawer Android (`drawer_settings.xml`, `SettingsDrawerController`, `AndroidSettingsStore`, `PairingMediaSession`, `PairingViewModel`, `StreamCodec`, `ProtocolConstants`) | hecho en esta iteración |
| Tests | yes | Core: rango fps (grupo TEST-016); Server: TEST-068; Android: `AndroidSettingsStoreTest`, `StreamCodecTest`; TEST-089 nuevo | filas actualizadas |

## 5. Options considered

1. Sin pacing ni tope y fps del wire como metadata (0 = unlimited) — elegida: refleja la petición del owner sin cambiar el schema del wire.
2. Subir el tope del selector (p. ej. 120/255) manteniendo el pacing — no: el owner pidió "solo unlimited".
3. Quitar el campo `fps` del wire `stream_start` — no: rompe compatibilidad sin necesidad; el valor aporta metadato informativo.

## 6. Decision

- **Outcome:** `Approved` (owner-requested)
- **Rationale:** "unlimited" real = quitar el pacing; el campo del wire queda como metadata informativa (0–255) por compatibilidad; el ajuste de bitrate (CR-027) sigue siendo la única limitante consciente de calidad.
- **Rollout:** same release (server, Cemu y Android juntos).

## 7. Execution checklist

- [x] Target document(s) updated (version bump y changelog con este CR)
- [x] Dependent documents updated (impact table)
- [x] Tests updated (TEST-068; TEST-089 nuevo; grupo TEST-016)
- [x] `testing/traceability.md` rows updated
- [x] Indexes (`_index.md`) updated
- [x] Code change applied (core, server, Cemu, Android drawer)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-28 | Product owner + Twinpad team | Opened (petición del owner) |
| 1.0.0 | 2026-10-28 | Product owner | Approved: sin pacing ni tope; fps del wire como metadata |
| 1.1.0 | 2026-10-28 | Twinpad team | Applied: código core/server/Cemu/Android y docs |
