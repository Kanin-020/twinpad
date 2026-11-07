# Change Request — CR-091 — Dusklight como host Twinpad: dispositivo seleccionable y Segunda pantalla H.264

| | |
|---|---|
| ID | CR-091 |
| Title | Dusklight como host Twinpad: Twinpad como dispositivo de entrada y Segunda pantalla por H.264 |
| Type | `Scope change` |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-06 |
| Decided | 2026-11-06 |
| Decided by | Owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | CTX §4; ARCH §2/§3/§6; SPEC-006 §3/§7; DES-004 (o DES nuevo para el host Dusklight) |
| Sections | Alcance de integración directa; flujo de conexión del host Dusklight; registro y selección del dispositivo de entrada |
| Current version → new version | CTX 0.3.0 → 0.4.0; ARCH 1.1.0 → 1.2.0; SPEC-006 1.26.0 → 1.27.0 |

## 2. Requested change

**Current:** CTX §4 solo admite como excepción un adaptador experimental puntual de Cemu (CR-004); SPEC-006 y DES-004 describen el host Cemu. La integración `integrations/dusklight` existe en código pero no tiene contrato: anuncia `game_id=dusklight` y publica la Segunda pantalla como `game_frame` con `fmt:"jpeg"`, e inyecta los inputs en `PAD_CHAN0` mediante `PADSetVirtualStatus` sin figurar como dispositivo.

**Proposed (decisión del owner, 2026-11-06):**

1. **Dispositivo de entrada.** Twinpad se ofrece como una opción propia en Ajustes → Controles → «Device» de Dusklight (entrada especial, como «Keyboard»), de modo que `current_controller_name(port)` muestre «Twinpad». Al conectarse el cliente Twinpad, esa opción queda seleccionada automáticamente para el puerto del jugador y los `input_report` se aplican a ese puerto mientras Twinpad sea el dispositivo elegido; al desconectarse se libera el pad y se restaura el dispositivo previo. La selección se persiste en la configuración local de Dusklight.
2. **Segunda pantalla.** Dusklight publica el panel como access units H.264/AVC Annex B con `ts_capture_us` e `idr` mediante `game_frame(fmt:"h264")` (SPEC-003 §4/CR-015), en lugar de JPEG. El Servidor PC y la app Android no cambian: el relay ya es H.264 (CR-014).

## 3. Motivation

El owner reporta dos defectos observables en Dusklight (2026-11-06): «no detecta los inputs» y «tampoco llegan los datos de la segunda pantalla, no está mostrando nada». Ambas causas están localizadas:

| Síntoma | Evidencia | Causa |
|---|---|---|
| La Segunda pantalla no muestra nada | `android/.../pairing/PairingViewModel.kt:267` contabiliza `video_frame`/`video_patch` como `onFrameRejected()`; `server/src/games.rs:737-757` solo entrega a `store_access_unit`/`MediaHub` cuando `fmt == H264` y el resto va al slot heredado que ya no alimenta la sesión | Dusklight emite JPEG (`src/dusk/twinpad/Core/TwinpadProtocol.cpp:257-273`); tras CR-014 el único formato que llega al teléfono es H.264 |
| Los inputs no se ven como control | `extern/aurora/lib/dolphin/pad/pad.cpp:422-430`: la lista «Device» son solo gamepads SDL; `PADSetVirtualStatus` (`src/dusk/twinpad/TwinpadHooks.cpp:98`) inyecta estado sin entrada de dispositivo | Twinpad nunca aparece en la lista ni puede asignarse; el juego solo lo ve si otro flujo escribe el pad virtual |

Sin este cambio, la integración de Dusklight no puede cumplir ni el contrato de video vigente ni la expectativa del owner.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| CTX | yes | El alcance de §4 pasa de «solo Cemu» a admitir también el fork Dusklight como host experimental | Actualizar §4 y añadir el actor/host en el glosario si se nombra «host Dusklight» |
| REQ-006 | no | Ya cubre host + anuncio + polling de inputs | none |
| SPEC-006 | yes | Debe nombrar el host Dusklight y su flujo de conexión/reconexión, y la semántica de dispositivo seleccionable | Update §3/§7 y reglas de negocio |
| SPEC-003 | no | El wire de video ya es H.264 (CR-014); Dusklight se limita a cumplirlo | none |
| SPEC-004 | no | `input_report` sin cambios; el mapeo ya existe | none |
| ARCH | yes | Añadir el adaptador Dusklight junto al de Cemu (componente, fronteras y fallo) | Update §2/§3/§6 |
| DES-004 | partial | Sirve de referencia, pero el host Dusklight tiene su propio ciclo de dispositivo | Nueva sección o DES-006 |
| TEST | yes | Casos de dispositivo (auto-selección, liberación, persistencia) y de video H.264 por el relay | Reservar IDs y registrar filas |
| Code | yes | `integrations/dusklight`: encoder H.264, publicación con metadata, entrada especial «Twinpad» en `controller_config`, selección/liberación y persistencia. Servidor y app sin cambios | PR en el fork |
| Data / migrations | yes | Nueva clave de configuración local de Dusklight (dispositivo por puerto) | Default que no altere configuraciones existentes |
| Integrations / contracts | yes | Sin cambio de wire; Dusklight pasa de JPEG a H.264 | Version bump documental |

## 5. Options considered

1. **H.264 en Dusklight (propuesta).** Cierra los dos síntomas sin tocar Servidor ni app y deja a Dusklight alineado con SPEC-003. Coste: añadir un encoder H.264 (OpenH264, el mismo fallback de Cemu) al build del fork, con revisión de dependencia de ENG §11.
2. **Reintroducir JPEG en la app.** Haría visible el panel sin dependencias nuevas, pero contradice SPEC-003 v1.5.0 y el relay opaco de CR-014; obliga a mantener dos pipelines de video en el teléfono. Rechazada salvo decisión explícita del owner.
3. **Do nothing.** Dusklight queda como prototipo sin Segunda pantalla utilizable ni entrada asignable.

## 6. Decision

- **Outcome:** `Approved` — opción §5.1: Dusklight publica H.264 y Twinpad es un dispositivo seleccionable.
- **Rationale:** Cierra los dos síntomas sin tocar el Servidor ni la app Android, que ya solo transportan H.264 (CR-014), y evita reintroducir JPEG contra SPEC-003 v1.5.0. El coste es una dependencia de build nueva en el fork (OpenH264, la misma que ya usa el adaptador de Cemu).
- **Decided by:** Owner, 2026-11-06 (elección explícita de «H.264 en Dusklight»).
- **Rollout:** Sin migración de datos; `game.twinpadInputPort` nace en `-1` (sin dispositivo), así que las configuraciones existentes no cambian de comportamiento. La auto-selección al conectar y su liberación al desconectar se prueban contra el juego, no en host.

## 7. Execution checklist

- [x] CTX, ARCH y SPEC-006 actualizados con el host Dusklight y el dispositivo seleccionable
- [ ] DES del host Dusklight con el ciclo de selección/liberación y la publicación H.264
- [x] Encoder H.264 en Dusklight con `ts_capture_us`/`idr` (SPEC-003 §4) — `Video/TwinpadH264`, OpenH264 Baseline/4.2, SPS+PPS por IDR y `video_force_idr` atendido
- [x] Entrada «Twinpad» en «Device» con auto-selección al conectar y liberación al desconectar — `game.twinpadInputPort`, restauración del dispositivo previo
- [x] TEST IDs y filas en `testing/traceability.md` — TEST-0185 (video/wire) y TEST-0186 (dispositivo)
- [x] Build de Dusklight y `dusk_twinpad_tests` verdes — 18/18 tests; `dusklight` enlazado

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-06 | Buffy (agente) | Abierto: causa raíz documentada para los dos defectos reportados en Dusklight |
| 0.2.0 | 2026-11-06 | Buffy (agente) | Aprobado por el owner (H.264 en Dusklight); implementadas ambas mitades y registrada la trazabilidad |
