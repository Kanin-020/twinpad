# Change Request (CR) — CR-075 — Twinpad

| | |
|---|---|
| ID | CR-075 |
| Title | Métricas avanzadas del HUD: fix del relay corrupto + aceleración HW/transporte/resoluciones y sección «Debug» en el drawer |
| Type | `Behavior change` + `Bug fix` |
| Status | `Applied` |
| Author | Buffy (agente) + Product owner |
| Created | 2026-11-04 |
| Decided | 2026-11-04 |
| Decided by | Product owner (petición expresa: «revisar por qué el botón de métricas avanzadas no muestra nada; agregar aceleración HW, TCP/UDP, resolución de entrada y de salida; mover gyro y métricas abajo como Debug») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | DES-004 §7.4 (nota), `testing/traceability.md`, drawer Android (SPEC-002 §11 nota) |
| Sections | HUD `video_stage_stats` (CR-053/054), `UdpPreferredMediaClient` (CR-062), drawer de ajustes |
| Current version → new version | DES-004 `1.6.0 → 1.7.0` (nota), sin wire estricto nuevo |

## 2. Requested change

**Current:**

```text
- Bug (desde CR-054): `VideoSendLoop` serializa CUALQUER ítem de la cola
  como `game_frame` Base64 («data":"" con annexB vacío); la línea NDJSON
  `video_stage_stats` (item con `ndjsonLine`) nunca llega al server y el
  HUD «Métricas avanzadas» queda vacío.
- El HUD solo muestra etapas/eventos con tiempos; no hay diagnóstico de
  backend del encoder (HW/SW), transporte media (UDP/TCP) ni resoluciones.
- «Métricas avanzadas» y «Giroscopio» viven entre secciones de usuario
  del drawer (HUD/controles), sin agrupar las de diagnóstico.
```

**Proposed:**

```text
- Fix del relay: en `VideoSendLoop` los ítems con `ndjsonLine` viajan tal
  cual por el canal primario (camino CR-053 restaurado); los AU binarios
  se serializan como `game_frame` SOLO en la rama fallback.
-  Mensaje `video_stage_stats` enriquecido (v2 del CR): claves top-level
  `hw`/`in_w`/`in_h`/`out_w`/`out_h` declaradas como campos OPCIONALES
  del variante `VideoStageStats` (el enum core es `deny_unknown_fields`:
  se necesitan campos declarados; `#[serde(default, skip_serializing_if)]`
  deja el wire canónico CR-053 sin detalles intacto). Validación core:
  par de dims todo-o-nada dentro del rango CR-030.:
  `hw` (bool: backend hardware activo), `in_w`/`in_h` (resolución de
  entrada: fuente del juego, pre-escala) y `out_w`/`out_h` (resolución
  de salida: post-escala, lo que viaja por el stream).
- HUD: segunda línea de detalles «HW · Trans · E · S» con solo lo
  conocido; el transporte lo conoce el teléfono
  (`UdpPreferredMediaClient` decide UDP/TCP a los 900 ms, CR-062 §8.5).
- Drawer: sección final «Debug» con «Métricas avanzadas» y
  «Giroscopio»; los ids de switches no cambian (sin migration).
```

## 3. Motivation

El owner reportó el HUD vacío y pidió diagnóstico de accel HW/transporte/
resoluciones. La causa raíz del HUD vacío es un bug de CR-054 (el item
de stats se re-serializaba como frame vacío, `data":""`, en vez de
viajar tal cual); el fix restaura el relay oficial de CR-053.
Primera hipótesis de implementación (v1) creía que serde toleraba claves
extra y el HUD seguía vacío: el enum core es `deny_unknown_fields`, con lo
que `GameMsg::decode` en el server descartaba la línea ANTES de registrarla
(v2 nace de TEST-0154, que demuestra la línea del Plugin).
El passthrough del server preserva la línea original; el codec Android
lee los detalles como opcionales (compatibilidad con plugins viejos:
campos null → línea oculta).

## 4. Acceptance

| Requirement | Verification |
|---|---|
| Los ítems NDJSON puros salen tal cual por `VideoSendLoop` | build Cemu verde; flujo real en banco físico |
| `hw`/`in_w`/`in_h`/`out_w`/`out_h` atraviesan el decode core del server y decodan como opcionales en la app | TEST-0154 (core), TEST-0153 (VideoStageStatsCodecTest) |
| Back-compat plugins viejos: sin claves → campos null y línea oculta | TEST-0153 |
| HUD muestra «HW · Trans · E · S» con solo lo conocido | TEST-0153 (formatDetails) |
| Drawer agrupa en «Debug» al final las dos métricas de diagnóstico | gyro monitor/advanced switches siguen funcionando (GyroMonitorRenderingTest, SettingsDrawerController en ids) |

## 8. Implementation

```text
- integrations/cemu: TwinpadClientPipeline.cpp (fix `VideoSendLoop` +
  rastreo `m_lastSourceWidth/Height`), TwinpadClient.cpp (claves CR-075
  en la línea), TwinpadClient.h (miembros de resolución de entrada).
- core: game.rs — campos opcionales del variante `VideoStageStats` +
  `video_stats_dims_valid` (v2; el deny_unknown_fields exige campos).
- server: tests/video_stage_stats_relay_integration.rs (TEST-0155):
  integración TCP real del peaje (registro verbatim + E4 sin muerte).
- android: UdpMediaClient.kt (onTransportActive), PairingMediaSession.kt
  (setMediaTransport), PairingViewModel.kt (mediaTransport), PairingActivity.kt
  (renderHudStageDetails), VideoStageStatsCodec.kt (decode + formatDetails),
  drawer_settings.xml + activity_pairing.xml + strings.xml.
- tests: TEST-0153 (4 casos en VideoStageStatsCodecTest), TEST-0154
  (core: `video_stage_stats_accepts_optional_details_and_rejects_bad_pairs`),
  TEST-0155 (integración server del relevo del HUD con líneas reales).
```
