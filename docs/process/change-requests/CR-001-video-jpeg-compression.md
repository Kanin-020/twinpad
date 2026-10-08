# Change Request (CR) — CR-001 — Twinpad

| | |
|---|---|
| ID | CR-001 |
| Title | Stream de video v1 con compresión JPEG (`fmt=jpeg`) + resolución/fps objetivo |
| Type | `Behavior change` |
| Status | `Approved` |
| Author | Eng (agent, por petición del owner) |
| Created | 2026-10-08 |
| Decided | 2026-10-08 |
| Decided by | Owner del proyecto (usuario) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-003 |
| Sections | §4 (`fmt`, `data`), §17 (OQ de compresión) |
| Current version → new version | `1.0.0 → 1.1.0` |

## 2. Requested change

**Current:**

```text
§4: `fmt` | string | `gray8` | `rgb8` (v1; extensible por SPEC futuro)
§4: `data` | base64url del Framebuffer crudo (sin cabecera de imagen),
    `w×h×bytes(fmt)` bytes
§17 OQ-1 (resuelta): sin compresión en v1; `rgb8` es el único formato
    requerido en la primera implementación. Si la medición de NFR-001
    fallara, la compresión entra por CR.
```

**Proposed:**

```text
§4: `fmt` | string | `gray8` | `rgb8` | `jpeg` (v1.1, CR-001)
§4: `data` | `rgb8`/`gray8`: base64url del framebuffer crudo,
    `w×h×bytes(fmt)` bytes. `jpeg`: base64url del archivo JPEG completo
    (JFIF, con cabecera); el tamaño es el del archivo codificado, no
    `w×h×bpp`. El decoder de la app decide por `fmt`.
§17 OQ-1 (ampliada por CR-001): la medición de ancho de banda demuestra
    que `rgb8` crudo no alcanza los objetivos del owner (720p/1080p a
    30/60 fps sobre WiFi LAN: 663 Mbps a 1.5 Gbps teóricos), por lo que
    v1.1 añade `jpeg` como formato requerido para esos objetivos;
    `rgb8`/`gray8` siguen definidos y el server elige `jpeg` por defecto
    con calidad configurable. La medición de NFR-001 queda en el banco
    físico (SPEC-003 §16).
```

## 3. Motivation

El owner pidió video fluido en 720p y 1080p a 30/60 fps. El ancho de banda
de rgb8 crudo (SPEC-003 OQ-1) hace esos objetivos imposibles en WiFi LAN:

| Modo | rgb8 crudo | JPEG ~80 % |
|---|---|---|
| 720p × 30 | 663 Mbps | ~33 Mbps |
| 720p × 60 | 1.33 Gbps | ~66 Mbps |
| 1080p × 30 | 1.5 Gbps | ~75 Mbps |
| 1080p × 60 | 3 Gbps | ~150 Mbps |

El propio SPEC-003 §17 preveía este camino: "si la medición de NFR-001
fallara, la compresión entra por CR". El JPEG decodifica con
`BitmapFactory` (hardware-accelerated en Android) sin dependencias nuevas
ni en server (`image` crate, ya en revisión ENG §11) ni en la app.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004 | no | el AC sigue siendo "el frame llega y se muestra" | none |
| SPEC-003 | yes | §4 añade `jpeg`; §17 OQ ampliada | update (v1.1.0) |
| ARCH | no | no cambia la estructura, solo el formato del framebuffer | none |
| DES | no | DES-003 no existe aún; la realización usa `image` (server) y `BitmapFactory` (app) | none |
| TEST-015/016 | yes | los tests del codec aceptan `fmt=jpeg` | update |
| Code | yes | server: encode JPEG + resolución/fps por env; app: decodificación JPEG | esta iteración |
| Integrations | no | el canal sigue siendo la sesión de SPEC-001; la app vieja rechazaría `jpeg` como E4 (coordinar APK nuevo) | none |

## 5. Options considered

1. **JPEG (`fmt=jpeg`) — propuesto**: sin dependencias nuevas, decode por
   hardware en Android, calidad ajustable, ~20-50× de reducción. Suficiente
   para los objetivos pedidos y testeable en CI.
2. H.264/H.265 por hardware — muy superior en bitrate, pero requiere
   MediaCodec/encoder nativo en el server (Linux/Windows), empaquetado de
   GOP/keyframes y un salto grande de complejidad; queda como evolución
   posterior si el banco físico demuestra que JPEG no alcanza NFR-001.
3. Do nothing — rgb8 crudo limita a ~480p×30 en la práctica; no cumple lo
   pedido por el owner.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** el owner pidió explícitamente 720p/1080p a 30/60 fps; el
  SPEC ya contemplaba la compresión por CR si la medición fallaba. JPEG es
  la opción mínima que cumple sin dependencias nuevas ni salto de
  complejidad; H.264 queda como evolución con datos del banco físico.
- **Rollout:** same release — server y app se actualizan juntos (la app
  vieja trata `jpeg` como E4).

## 7. Execution checklist

- [x] SPEC-003 actualizado (v1.1.0, changelog referencia CR-001)
- [x] Tests del codec actualizados (fmt=jpeg; tamaño sin fórmula w×h×bpp)
- [x] `testing/traceability.md` actualizado (CR-001 en change history)
- [x] `docs/process/change-requests/_index.md` actualizado
- [x] Server: encode JPEG + resolución/fps configurables
- [x] App: decodificación JPEG (BitmapFactory)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-08 | Eng (agent) | Opened con la decisión del owner |
