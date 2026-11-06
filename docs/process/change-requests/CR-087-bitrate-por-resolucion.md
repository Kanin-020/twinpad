# Change Request (CR) — CR-087 — Twinpad

> **What this is:** a controlled, reviewable change to an approved artifact. One CR per logical change.

| | |
|---|---|
| ID | CR-087 |
| Title | Bitrate del encoder derivado de la resolución elegida (540p 10 / 720p 15 / 1080p 20 Mbps) y ajuste de «Nativa» con las dimensiones reales del stream |
| Type | `Behavior change` |
| Status | `Applied (2026-11-06, code-applied; actualización de SPEC-002 pendiente)` |
| Author | Buffy (agente), instrucción directa del owner |
| Created | 2026-11-06 |
| Decided | 2026-11-06 |
| Decided by | Owner («SUbelos un poco 10 15 20» + «Automático por resolución» + «Segun la dimensiones recibidas, investiga cual es el valor mas optimo de Mbps para cada resolución suponiendo 60 fps minimos») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `SPEC-002` (§11 R15 bitrate, R17 resolución) |
| Sections | §11 R15/R17, §16 (verificación) |
| Current version → new version | `1.13.0 → pendiente` |

## 2. Requested change

**Current** (tras CR-064, código vigente):

```text
El drawer no expone control de bitrate: el cliente envía siempre
10 000 kbps fijos en video_config, sea cual sea la resolución elegida
(960×540, 1280×720, 1920×1080 o «Nativa» 0/0).
```

**Proposed:**

```text
El bitrate del encoder se deriva de la resolución de emisión elegida:
960×540 → 10 000 kbps, 1280×720 → 15 000 kbps, 1920×1080 (o superior)
→ 20 000 kbps. El wire no cambia: video_config sigue llevando w/h y
bitrate_kbps (CR-027/CR-030/CR-074) y la escalera CR-058 sigue usando
ese valor como referencia. Sigue sin haber control de bitrate en el
drawer (CR-064 se mantiene).

«Nativa» (0/0) no tiene resolución propia: la app envía la clase 540p
(10 000 kbps) al configurar la sesión y, al recibir el `stream_start`
con las dimensiones reales, reenvía el `video_config` 0/0 con el
bitrate de esa clase (máx. un reenvío por clase y sesión, para no
reiniciar el encoder ni forzar IDR en bucle).
```

## 3. Motivation

Pedido del owner: subir el bitrate por encima del valor fijo de CR-064 y
hacerlo automático por resolución, con valores pensados para 60 fps.

Referencia pública de H.264 (YouTube Live, 60 fps): 720p60 → 8 Mbps
recomendados (mínimo 3), 1080p60 → 17 Mbps recomendados (mínimo 6); 540p no
se publica. La tabla elegida queda por encima de esos mínimos en todas las
clases (10/15/20), lo que deja margen al rate-control en contenido de juego.

Límite real del proyecto: la recepción UDP del teléfono. CR-064 bajó de
30 000 a 10 000 kbps porque 30 000 emitía ~3 400 datagramas/s contra un
lector que consumía ~2 300 dgs/s (drops → AUs incompletas → 0 fps). 20 Mbps
queda por debajo de ese techo medido y la escalera CR-058 puede degradar si
la red o el equipo no lo sostienen; el valor sigue siendo configurable por
`TWINPAD_VIDEO_BITRATE_KBPS` en el servidor.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004 | no | El contrato de salida H.264 no cambia | none |
| REQ-008 | no | Sigue sin control de bitrate en el drawer | none |
| SPEC-002 | yes | R15 (bitrate) y R17 (resolución → emisión) describen el valor fijo de CR-064 | update pendiente (owner) |
| SPEC-003 | no | `video_config.bitrate_kbps` mantiene rango 1 000–30 000 | none |
| ARCH | no | — | none |
| DES-002 | no | — | none |
| TEST-069 | yes | Asertos de default y wire pasan a 10/15/20 Mbps derivados | updated in-code |
| TEST-0151 | yes | «Nativa» sigue enviando 0/0 con la clase 540p como valor inicial | updated in-code |
| TEST-0183 | yes | Cobertura nueva del mapeo y del ajuste de «Nativa» | added (Passed) |
| Code | yes | `AndroidSettingsStore.kt`, `VideoBitrate.kt` (nuevo), `PairingViewModel.kt` | aplicado |
| Data / migrations | no | El bitrate no se persiste (CR-064) | none |
| Integrations / contracts | no | Wire sin cambios | none |

## 5. Options considered

1. Bitrate derivado de la resolución + «Nativa» ajustada con el `stream_start` — mantiene el drawer sin control manual (CR-064) y usa el dato real de la captura.
2. Do nothing (10 000 kbps fijos) — deja 1080p con menos margen del que el owner quiere para 60 fps.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** el owner fijó los tres valores y eligió el ajuste automático por resolución; «Nativa» se resuelve con las dimensiones anunciadas por el stream porque el drawer no las conoce.
- **Rollout:** misma iteración; solo app Android (sin cambios de servidor ni de Cemu).

## 7. Execution checklist

- [ ] Target document(s) updated (SPEC-002 R15/R17, versión y changelog con este CR)
- [x] Dependent documents updated (ninguno en este CR)
- [x] Tests added/updated (TEST-0183 nuevo; TEST-069/TEST-0151 actualizados)
- [x] `testing/traceability.md` rows updated (incluida la historia de cambios)
- [ ] Indexes (`_index.md`) updated
- [x] Code change merged (if any)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-06 | Buffy (agente) | Abierto y aplicado en código: bitrate por resolución y ajuste de «Nativa» |
