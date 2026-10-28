# Change Request (CR) — CR-030 — Twinpad

| | |
|---|---|
| ID | CR-030 |
| Title | Resolución efectiva de la pantalla GamePad: `video_config` reenvía `w`/`h` a Cemu y el Plugin escala antes del encoder |
| Type | `Behavior change` |
| Status | `Applied` |
| Author | Product owner + Twinpad team |
| Created | 2026-10-28 |
| Decided | 2026-10-28 |
| Decided by | Product owner (petición expresa en esta iteración) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-002 §11, SPEC-003 §3/§8.1, SPEC-006 §16, `testing/traceability.md` |
| Sections | Efecto real del ajuste de resolución del drawer sobre el DRC |
| Current version → new version | SPEC-002 `1.7.0 → 1.8.0`; SPEC-003 `1.9.0 → 1.10.0`; SPEC-006 `1.12.0 → 1.13.0` |

## 2. Requested change

**Current:**

```text
El drawer de ajustes (resolución 960×540 / 1280×720 / 1920×1080) envía
w/h en `video_config`; el Servidor los valida y conserva en VideoConfig,
pero al reenviar el mensaje al adaptador Cemu solo incluye
`bitrate_kbps` (CR-027). El encoder de Cemu codifica al tamaño nativo
del DRC; la opción de resolución no tiene efecto en la fuente actual.
```

**Proposed:**

```text
- El Servidor reenvía w/h (clamped 16–3840 × 16–2160) junto a
  bitrate_kbps en el `video_config` hacia Cemu, al conectar la
  selección y en cada `video_config` de sesión.
- Cemu acepta w/h en el parse de `video_config` y los aplica como
  resolución de emisión SOLO de la pantalla GamePad (DRC):
  escalado nearest-neighbor del frame capturado a la dimensión
  objetivo antes del encode. La resolución de render del juego en
  la TV no cambia (el objetivo voxel es el framebuffer DRC).
- Sin upscale: si w×h objetivo ≥ nativo del DRC se publica nativo
  (escalar a más no añade información y desperdicia bits).
- Un cambio de dimensión efectiva reinicia el encoder (igual que
  bitrate, CR-027) vía la detección existente de encoderConfigChanged.
- Los targets deben ser pares (el encoding I420 exige pares);
  los impares se reducen al par inmediatamente inferior con clamp
  16–3840 × 16–2160.
- Sin `w`/`h` en el mensaje (o valores 0) se mantiene nativo.
```

## 3. Motivation

El owner pidió "aplicarle uso real a la opción de resolución" y aclaró que
"esa resolución solo va sobre la pantalla del gamepad, no a la resolución
general del juego". Hoy la opción es inerte para la fuente Cemu (solo el
bitrate viaja); darle efecto reduce el coste de codec y sube los bits por
píxel a bitrate fijo, sin tocar la renderización del juego.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004 | no | El AC vigente no fija el mecanismo de resolución del stream | none |
| SPEC-002 | yes | R17: resolución del drawer aplica al DRC | R17 + changelog 1.8.0 |
| SPEC-003 | yes | §3: `video_config` reenviado con w/h; §8.1 redacción confirmada | §3 + changelog 1.10.0 |
| SPEC-006 | yes | §16: verificación del escalado del DRC | fila + changelog 1.13.0 |
| DES-004 | yes | Cemu escala el DRC al target antes del encoder | nota en changelog futura (sin bump) |
| Code | yes | `server/games.rs` + `server/main.rs` (firma de `send_cemu_video_config`) y `Cafe/Twinpad/TwinpadClient.{h,cpp}` (SetCaptureResolution, escala, métrica SCALE) | hecho en esta iteración |
| Tests | yes | Server: reenvío con w/h (TEST-068); Cemu: TEST-088 nuevo (escala RGB pura) | filas actualizadas |

## 5. Options considered

1. Reenviar w/h a Cemu y escalar el frame DRC antes del encode — elegida: da efecto real a la opción con la alcalina del wire ya definida (drawer ya envía w/h) y sin creador de protocolo nuevo.
2. Cambiar la resolución interna del render target DRC (GPU) — descartada: alteraría la renderización del juego en TV (fuera del alcance pedido por el owner) y depende del backend gráfico.
3. Escalar en el Servidor — descartado: el Servidor relay no toca medios H.264; escalarciano RGB antes del encode es más barato y evita re-codificar.

## 6. Decision

- **Outcome:** `Approved` (owner-requested)
- **Rationale:** un único ajuste de resolución efectivo para la pantalla del GamePad,heim con el formato JSON ya presente en la app (CR-010 histórico).
- **Rollout:** same release (server y Cemu se despliegan juntos; la app no cambia).

## 7. Execution checklist

- [x] Target document(s) updated (version bump y changelog con este CR)
- [x] Dependent documents updated (impact table)
- [x] Tests updated (TEST-068; TEST-088 nuevo)
- [x] `testing/traceability.md` rows updated (primera tabla + change history)
- [x] Indexes (`_index.md`) updated
- [x] Code change applied (server + Cemu adaptador)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-28 | Product owner + Twinpad team | Opened (petición del owner) |
| 1.0.0 | 2026-10-28 | Product owner | Approved: w/h efectivos en Cemu, escala DRC pre-encode, sin upscale |
| 1.1.0 | 2026-10-28 | Twinpad team | Applied: código server/Cemu y docs |
