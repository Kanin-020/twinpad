# Change Request — CR-092 — Dusklight: puerto 1 sin desalojo, táctil del teléfono y `video_config`

| | | |
|---|---|---|
| ID | CR-092 |
| Title | Dusklight: auto-selección del puerto 1 solo si está libre, táctil del teléfono sobre el panel y `video_config` aplicado por el host |
| Type | `Scope change` |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-06 |
| Decided | 2026-11-06 |
| Decided by | Owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-006 §3/§7 (R14) |
| Sections | Flujo del host Dusklight: selección de dispositivo, consumo del táctil y configuración de emisión |
| Current version → new version | SPEC-006 1.27.0 → 1.28.0 |

## 2. Requested change

**Current (SPEC-006 1.27.0, R14):**

1. Al conectar, el host de Dusklight «selecciona Twinpad como dispositivo del puerto 1» **sin condición**: si el puerto ya lo ocupa un mando SDL o el teclado, el adaptador los desaloja (`PADClearPort` + `PADSetKeyboardActive(FALSE)`) y los restaure al desconectar.
2. El `input_report` de SPEC-004 §4 trae `touch_x`/`touch_y`/`touch_pressed`, pero el adaptador **los descarta**: su propio README los documenta como límite conocido («el panel se maneja con el táctil nativo del companion»).
3. El adaptador **ignora `video_config`**: emite siempre a la resolución nativa del panel con 8.000 kbps fijos y un único slice, aunque el drawer de la app (SPEC-002 R15/R17, SPEC-003 §4) envíe otra resolución o bitrate.

**Proposed (decisión del owner, 2026-11-06):**

1. **Puerto 1 sin desalojo.** La auto-selección ocurre solo si el puerto 1 está libre (ni mando SDL asignado ni teclado con bindings). Si está ocupado, Twinpad no se auto-selecciona y el puerto conserva su dispositivo; el usuario puede elegir «Twinpad» a mano en Ajustes → Controles → Device, acción que sí libera el puerto (comportamiento ya existente).
2. **Táctil del teléfono.** El host traduce `touch_x`/`touch_y`/`touch_pressed` a los gestos que ya consume la capa táctil del panel (0 = down, 1 = move, 2 = up, coordenadas normalizadas 0…1) y los entrega a ella. Sin cambio de wire. El táctil nativo del companion sigue operativo: si ambos escriben el mismo frame, gana el último, la misma regla que ya rige el pad virtual.
3. **`video_config` aplicado.** El host acepta la línea que el Servidor reenvía del drawer: `w`/`h` (16–3840 × 16–2160, `0/0` = nativo, dimensiones impares al par inferior) y `bitrate_kbps` (clamp 1.000–30.000, defecto 8.000). Escala el frame capturado antes de codificar (nearest-neighbor, en ambas direcciones) y reinicia el encoder con IDR cuando cambia la configuración. La codificación pasa a slices paralelos para sostener la tasa que pida la sesión.

## 3. Motivation

El owner reporta tres defectos observables en Dusklight (2026-11-06). Las causas están localizadas en el adaptador:

| Síntoma | Evidencia | Causa |
|---|---|---|
| El mando del Deck deja de responder al conectar el teléfono | `TwinpadHooks.cpp` `autoSelect()`: `PADClearPort(0)` + `PADSetKeyboardActive(0, FALSE)` incondicionales | La auto-selección desaloja el dispositivo aunque el puerto 1 esté ocupado |
| Tocar el panel en el teléfono no hace nada | `Core/TwinpadProtocol.cpp` parsea `touch_*` y `TwinpadPadMap.h` documenta que no se usan | El gesto nunca llega a la capa táctil del panel |
| Video a pocos FPS y sin responder al ajuste de resolución | `Video/TwinpadH264.cpp`: `SM_SINGLE_SLICE`, `iMultipleThreadIdc` sin fijar, 8.000 kbps fijos; `Client` descarta `video_config` | Un solo hilo codificando el panel nativo (≈1240×1080) fija el techo de tasa, y la resolución que pide la app nunca se aplica |

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-006 | yes | R14 pasa a describir la auto-selección condicionada, el consumo del táctil y la aplicación de `video_config` | Actualizar R14 y su changelog |
| SPEC-003 | no | `video_config` y el escalado ya están contratados (CR-027/CR-030/CR-074); el host se limita a cumplirlos | none |
| SPEC-004 | no | `input_report` sin cambios; el táctil ya estaba en el wire | none |
| CTX / ARCH / DES | no | No cambian actores ni fronteras: el adaptador sigue siendo el borde del juego | none |
| TEST | yes | Nuevos casos de `video_config`/escalado y de traducción táctil en host; el caso de dispositivo se reescribe | Reservar TEST-0187/0188 y actualizar TEST-0186 |
| Code | yes | `Core/TwinpadProtocol` (`video_config`), `Video/TwinpadH264` (escalado + slices), `Channels/TwinpadTouch` (nuevo), `TwinpadHooks`, `dualscreen.cpp` | Implementado en `integrations/dusklight` |
| Data / migrations | no | `game.twinpadInputPort` conserva su defecto `-1` | none |
| Integrations / contracts | no | Sin cambio de wire ni de capabilities | none |

## 5. Options considered

1. **Aplicar el contrato existente (propuesta).** El wire ya transporta el táctil y `video_config`; el host solo tenía que consumirlos. Cierra los tres síntomas sin tocar Servidor ni app.
2. **Solo el arreglo del puerto.** Barato, pero deja el táctil muerto y el video limitado por el encoder en un hilo.
3. **Do nothing.** El adaptador sigue desalojando mandos, ignorando el táctil y emitiendo a una tasa que no controla el usuario.

## 6. Decision

- **Outcome:** `Approved` — opción §5.1.
- **Rationale:** Los tres defectos son del host, no del wire: `touch_*` y `video_config` ya están contratados y ya llegan al adaptador. Consumirlos no añade contrato nuevo y devuelve al usuario el control de la tasa desde el drawer de la app.
- **Decided by:** Owner, 2026-11-06 (petición directa de los tres comportamientos).
- **Rollout:** Sin migración. La auto-selección deja de aplicarse a quien ya tuviera el puerto ocupado; el resto de configuraciones no cambia. El táctil y la tasa se validan con ISO y teléfono (TEST-0188 sigue manual en ese tramo).

## 7. Execution checklist

- [x] SPEC-006 R14 actualizada (1.28.0)
- [x] Auto-selección solo con el puerto 1 libre; el desalojo queda únicamente en la elección manual de Device
- [x] `Channels/TwinpadTouch` (traducción pura) entregada a `companion::touchEvent` desde `dualscreen.cpp`
- [x] `video_config` parseado y aplicado (w/h, `0/0` nativo, bitrate clampeado) con escalado nearest-neighbor
- [x] Encoder con 4 slices y `iMultipleThreadIdc` (mismo ajuste que el adaptador de Cemu)
- [x] TEST-0187/0188 y actualización de TEST-0186 en `testing/traceability.md`
- [x] `dusk_twinpad_tests` verdes (25/25) y `dusklight` enlazado

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-06 | Buffy (agente) | Abierto y aprobado por el owner: los tres defectos son del host y se cierran consumiendo contrato existente |
