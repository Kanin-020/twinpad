# Change Request (CR) — CR-077 — Twinpad

| | |
|---|---|
| ID | CR-077 |
| Title | Tokens de `DESIGN.md` en la app Android + alternador PC/Gamepad de los gauges de espera + fix del medidor congelado |
| Type | `Feature` |
| Status | `Applied` |
| Author | Buffy (agente) |
| Created | 2026-11-05 |
| Decided | 2026-11-05 |
| Decided by | Product owner (petición directa) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-002 (nota R21, update pendiente), `testing/traceability.md`, código Android (`PairingActivity`, `WaitingGaugePanel`, `DeviceMetricsSampler`, `SettingsDrawerController`, `AndroidSettingsStore`, `GaugeView`), recursos (colors/themes/strings/layout/font) |
| Sections | Drawer de ajustes (SPEC-002 §6/R21), ventana de espera CR-076, pantallas Pairing |
| Current version → new version | SPEC-002 nota sin bump (patrón CR-075); SPEC/DES sin cambios de versión |

## 2. Requested change (owner)

```text
1. Aplicar docs/design/DESIGN.md (Featurebase dark) a la app Android:
   Void Canvas #050608 de fondo, paneles Charcoal/Steel, acento Signal
   Violet #7064F2 restringido a acciones, texto Inter 400–700 (variable +
   licencia OFL en android/font_licenses), Cloud White/Frost/Misted para
   texto, radios 12 dp (botones/inputs) y 16 dp (cards); botones
   primarios llenos blancos con texto Charcoal.
2. Arreglar el medidor de métricas: no refrescaba datos en tiempo real —
   `PairingViewModel.clearStreamSession()` no limpiaba `_hostMetrics` →
   los gauges quedaban congelados con las lecturas de una sesión pasada
   y la vista nueva no volvió a pintar nunca.
3. Interruptor en el drawer: «Métricas de la PC (desmarcado: Gamepad)»
   — alterna el origen de los 4 gauges CR-076 entre el HOST
   (`host_metrics`) y la medición del propio teléfono; preferencia
   persistente `metrics_source_host`, default PC.
4. Los gauges (PC y Gamepad) heredan el estilo de DESIGN.md (anillo
   Slate Edge, texto Cloud White, etiquetas Quiet Slate, inactivo
   atenuado).
```

## 3. Motivation

- El owner reportó una app sin tokens de la guía (tema Material por
  defecto, sin la escala de color ni la tipografía de DESIGN.md). La
  interfaz debe verse y ser consistente con la guía.
- El defecto del medidor congelado engaña: parece que el server dejó de
  emitir o es la causa operativa de CR-076 §5 (binario server antiguo
  en el banco), pero era un bug real del ViewModel (estado stale en
  `clearStreamSession`).
- La medición del teléfono como fuente del Gamepad es útil cuando
  `host_metrics` no está disponible (§6, limitaciones).

## 4. Acceptance criteria

| AC | Assertion |
|---|---|
| AC-1 | `PairingViewModel.clearStreamSession()` deja `_hostMetrics = null` (fix del medidor): después de un corte, la vista repinta desde «—» en el siguiente ciclo |
| AC-2 | Interruptor persistente en el drawer: el cambio persiste y llega a la Activity por la ruta existente de ajustes del stream (`onStreamSettingsChanged`); el estado restaurado se lee en onCreate antes de `applyPersistedSettings` |
| AC-3 | Con Gamepad elegido, el teléfono se mide en tiempo real (CPU delta `/proc/stat`, GPU busy del driver, RAM ActivityManager, FPS por Choreographer) mientras Linked y no STREAMING; el muestreo se detiene al alternar a PC, al ocultar la vista y al destruir la Activity; «—» sin dato, nunca inventos (CR-076) |
| AC-4 | La app aplica los tokens de DESIGN.md vía `colors.xml` + `themes.xml` + layouts (`activity_pairing`, `drawer_settings`) + drawable del chip HUD; Inter variable con licencia incluida |
| AC-5 | `PairingActivity` queda bajo los umbrales detekt (LargeClass/TooManyFunctions): el panel `WaitingGaugePanel` es dueño único del pintado de gauges y la interfaz `SettingsDrawerHost` no gana funciones (10) |

## 5. Implementation

- `DeviceMetricsSampler.kt` restaura la versión v1 verbatim de
  `edc3d2f1` (CPU por delta de `/proc/stat`, GPU del driver, RAM de
  ActivityManager, FPS por Choreographer): revierte la eliminación de
  CR-076 v2. El test vuelve como TEST-0158.
- `AndroidSettingsStore.kt` añade la clave `metrics_source_host`
  (default `true`); los casos de persistencia corren dentro de la
  suite existente TEST-069 (`AndroidSettingsStoreTest`, sin TEST ID
  nuevo para el store).
- `WaitingGaugePanel.kt` (internal): dueño único del pintado de los 4
  gauges por origen — `renderHost(metrics)` para `host_metrics` y
  `renderDevice()` para el teléfono con I/O fuera del hilo de UI
  (`Dispatchers.Default`, ENG §9), un solo sampleo por tick serializado
  (`@Synchronized`), monitor FPS idempotente que instala su previo el
  primer tick (como el sampler del HOST) y se apaga con
  `stopDeviceMonitor()`; techo del arco FPS 60 (CR-076 v2). Su
  extracción saca de `PairingActivity` el bloque de gauges inline
  (umbral detekt).
- `PairingActivity` reemplaza el bloque de gauges inline por el panel
  (elimina `renderHostGauges/renderDeviceGauges/renderPercentGauge/
  renderHostRamGauge/renderHostFpsGauge/initializeGaugeLabels/
  sampleDeviceMetrics/startDeviceFpsMonitor`, `DeviceSnapshot`, las
  constantes locales `GAUGE_MAX_*`/`MB_PER_GB`, el estado
  `gaugeLabelsInitialized/deviceCpuPrev/deviceFpsMonitor` y los imports
  de Dispatchers/withContext sobrantes); el tick loop delega en
  `gaugePanel.renderHost(...)` / `gaugePanel.renderDevice()`.
- `AndroidSettingsStore` nueva clave `metrics_source_host` (default
  `true`, persistida round-trip).
- `SettingsDrawerController`: el interruptor usa la ruta existente
  `updateStreamSettings { copy(metricsSourceHost = ...) }` (persiste y
  notifica el host por `onStreamSettingsChanged` — sin función nueva en
  la interfaz `SettingsDrawerHost`, que queda en 10 funciones para el
  umbral detekt); el switch solo se MARCA en restore (sin llamada de
  restauración).
  Nota: `updateStreamSettings` en caliente reenvía el `video_config`
  vigente (con valores idénticos: el server re-aplica la misma
  configuración y no hay efecto semántico ni Cambio de wire).
- Fix del medidor (2): `PairingViewModel.clearStreamSession()` limpia
  `_hostMetrics` con `null` (defecto raíz del owner report).
- Gauges: `GaugeView` estilo DESIGN.md (anillo Slate Edge, texto Cloud
  White, etiquetas Quiet Slate, inactivo 30 %), fondo Void Canvas en
  activity/drawer, chip HUD `bg_hud_chip` (Charcoal + stroke Slate Edge
  + 12 dp) y botón filled `Widget.Twinpad.Button.Filled` (blanco 92 % +
  texto Charcoal, 12 dp, en ambos layouts).
- Recursos: `colors.xml` (tokens), `themes.xml` (fondo, Inter,
  buttonStyle), `activity_pairing.xml` + `drawer_settings.xml` por
  token, `bg_hud_chip.xml`, `res/font/inter_variable.ttf` + `inter.xml`
  (weights 400/500/600), licencia `android/font_licenses/Inter-OFL.txt`
  (desde rsms/inter v4.1), `strings.xml` con
  `settings_metrics_host`.

## 6. Limitations (explícito)

- `host_metrics` (CR-076) mide un HOST Linux vía `/proc` y sysfs: en un
  servidor Windows los gauges PC quedarán «—» hasta un CR propio (NO
  este CR); el alternador Gamepad es la fuente viva disponible ahí.
- La causa operativa CR-076 §5 (binario server viejo en el banco) sigue
  válida como motivo separado: si el server en ejecución no emite
  `host_metrics`, los gauges PC muestran «—» y hay que alternar a
  Gamepad o usar el server release vigente.

## 7. Files touched

- `android/app/src/main/kotlin/com/twinpad/android/app/ui/{PairingActivity,WaitingGaugePanel,DeviceMetricsSampler,SettingsDrawerController,AndroidSettingsStore,GaugeView}.kt`
- `android/app/src/main/kotlin/com/twinpad/android/app/pairing/PairingViewModel.kt`
- `android/app/src/main/res/values/{colors,strings,themes}.xml`
- `android/app/src/main/res/layout/{activity_pairing,drawer_settings}.xml`
- `android/app/src/main/res/drawable/bg_hud_chip.xml`
- `android/app/src/main/res/font/{inter.xml,inter_variable.ttf}`
- `android/font_licenses/Inter-OFL.txt`
- `android/app/src/test/.../ui/{DeviceMetricsSamplerTest,AndroidSettingsStoreTest}.kt`
- `docs/specs/SPEC-002-android-pairing-ui.md` (nota R21)
- `docs/testing/traceability.md` (TEST-0158 + fila CR-077)
- `docs/process/change-requests/CR-077-...md` + `_index.md`

## 8. Test evidence

- `cd android && ./gradlew :app:testDebugUnitTest
  :app:ktlintMainSourceSetCheck :app:detekt` — BUILD SUCCESSFUL (suite
  completa de tests unitarios + lint Kotlin + detekt con umbrales).
- TEST-0158 (`DeviceMetricsSamplerTest`) restaurado y verde;
  `AndroidSettingsStoreTest` (casos extra en la suite TEST-069) cubre
  la persistencia round-trip de `metrics_source_host`.
