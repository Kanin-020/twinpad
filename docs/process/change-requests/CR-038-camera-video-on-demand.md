# Change Request — CR-038 — Cámara del teléfono hacia Cemu bajo demanda

| | |
|---|---|
| ID | CR-038 |
| Title | Gobernar el envío de `camera_frame` por demanda: nuevo mensaje `camera_request` Cemu→Teléfono |
| Type | `Behavior change` |
| Status | `Applied` |
| Author | Twinpad team (usuario + agente) |
| Created | 2026-10-29 |
| Decided | 2026-10-29 |
| Decided by | Product owner (solicitud directa en sesión) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-006, DES-002, DES-004, traceability |
| Sections | SPEC-006 §4 (mensajes), §16 (verificación); DES-002 (flujo Android de la cámara frontal); DES-004 (ruta de cámara del VPAD) |
| Current version → new version | SPEC-006 1.18.0 → 1.19.0 |

## 2. Requested change

**Current:**

```text
SPEC-006 §4: `camera_frame` se envía "por cada access unit de la cámara frontal
del teléfono (30 fps) mientras Cemu está seleccionado". Android arranca la
cámara frontal al detectar game_select = cemu y la deja corriendo hasta cambiar
de juego o desconectar, aunque el juego en Cemu no use la cámara del GamePad.
```

**Proposed:**

```text
Nuevo mensaje `camera_request` Cemu→Servidor→Android de sesión (CR-038):
`type:"camera_request"`, `on:boolean`. Cemu lo emite en la transición del número
de instancias de cámara HLE abiertas (`CAMOpen`/`CAMClose` de coreinit `camera`):
on=true al pasar de 0 instancias a ≥1, on=false al volver a 0. También reenvía su
último estado tras `game_announce` (re-conexión IPC) y el Servidor re-entrega la
demandada vigente cuando una sesión selecciona el Plugin. Android solo abre la
cámara frontal (y por tanto solo envía `camera_frame`) con juego seleccionado
"cemu" y `camera_request on=true` vigente; con on=false o sin mensaje detiene el
stream. No se añade persistencia más allá del último estado por juego en el
Servidor.
```

## 3. Motivation

La cámara se envía indiscriminadamente desde que el teléfono selecciona Cemu
(incluso parado en el menú del juego), gastando batería, CPU (encoder H.264
30 fps) y ancho de banda del mismo socket que `input_report`, cuando la mayoría
de los títulos nunca abren la cámara del GamePad. Evidence: `PairingActivity.observeSelectedGame()`
arranca el stream por selección, no por uso; el lado Cemu tiene el semáforo
exacto en `Cafe/OS/libs/camera/camera.cpp` (`g_activeCameraInstances`, solo se
llena una superficie desde `TwinpadCameraDecoder` por instancia abierta).

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-006 | No | La cámara sigue siendo capacidad del Plugin | none |
| SPEC-006 | Yes | Nuevo mensaje y gating del `camera_frame` | update §4/§16, bump 1.19.0 |
| ARCH | No | Mismo canal IPC/sesión existente | none |
| DES-002 | Yes | Condición de arranque de la cámara frontal en la app | update |
| DES-004 | Yes | Gancho Cemu de demanda y reenvío tras announce | update |
| TEST-097/098/099/0100 | Yes | Round-trip core, cola/routing server, codec Android, línea Cemu | new TEST IDs + rows |
| Code | Yes | core (GameMsg), server (cola/estado + reenvío), Cemu (PublishCameraRequest + gancho HLE), Android (codec + gating) | Moderate |
| Integrations / contracts | Yes | Mensaje nuevo en IPC y sesión | Version bump vía CR |

## 5. Options considered

1. **`camera_request` pilotado por el uso real (aprobado)** — on/off con las
   transiciones de instancias HLE; demanda = lo que el juego realmente abre.
   Mínimo consumo y faithful al pedido del owner.
2. Demanda durante todo el título en ejecución — más simple, pero sigue
   enviando video en juegos que no usan la cámara.
3. Do nothing — el teléfono sigue enviando 30 fps injustificados.

## 6. Decision

- **Outcome:** `Approved` y aplicado (SPEC-006 1.19.0).
- **Rationale:** el owner (usuario) pidió explícitamente que el envío sea bajo
  demanda; el semáforo natural es la apertura/cierre de cámara del juego en el
  HLE `camera` de Cemu.
- **Rollout:** misma iteración; wire compatible hacia atrás (los mensajes
  desconocidos se descartan como hoy).

## 7. Execution checklist

- [x] Product owner approval recorded
- [x] Target document(s) updated (SPEC-006 1.19.0, DES-002, DES-004)
- [x] Tests added/updated (TEST-097, TEST-098, TEST-099, TEST-0100)
- [x] `testing/traceability.md` rows updated
- [x] `_index.md` updated
- [x] Code change merged

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-29 | Twinpad team (usuario + agente) | Opened |
| 1.0.0 | 2026-10-29 | Twinpad team (usuario + agente) | Applied: SPEC-006 1.19.0, TEST-097/098/099/0100 |
