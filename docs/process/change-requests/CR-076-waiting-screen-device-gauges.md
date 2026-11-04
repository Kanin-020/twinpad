# Change Request (CR) — CR-076 — Twinpad

| | |
|---|---|
| ID | CR-076 |
| Title | Ventana de espera del teléfono: gauges de CPU/GPU/RAM/FPS del dispositivo (referencia visual AYN Thor) |
| Type | `Feature` |
| Status | `Applied` |
| Author | Buffy (agente) + Product owner |
| Created | 2026-11-04 |
| Decided | 2026-11-04 |
| Decided by | Product owner (petición: «en la ventana de espera, mostrar gráficas de consumo de CPU, GPU, RAM y FPS; la referencia está en references») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | DES-002 (nota), `testing/traceability.md`, layout `activity_pairing.xml` |
| Sections | Pantalla de sesión (Linked, SPEC-002 §6), vista「sin segunda pantalla」(AC-3 de SPEC-003) |
| Current version → new version | DES-002 `… → +1 minor`, sin wire ni protocolo nuevo |

## 2. Requested change

**Current:**

```text
- En Linked sin stream (AC-3: INACTIVE), session_view solo muestra el
  mensaje «sin segunda pantalla activa» y la lista de juegos compatibles:
  la pantalla de espera está vacía de contenido útil.
- No hay medición de consumo del dispositivo (CPU/GPU/RAM) ni de FPS
  de render visibles en ninguna parte de la app.
```

**Proposed:**

```text
- Panel de 4 gauges circulares (referencia: reference/AYN-Thor-18.jpg):
  CPU (% de uso), GPU (% de busy del driver, si el SoC lo expone),
  RAM (GB usados / total) y FPS (render de la app, medido con
  Choreographer contra la tasa de refresco del display).
- Ubicación: abajo-centro de session_view; SOLO en la ventana de espera
  (Linked con StreamState.INACTIVE); al empezar el stream el panel se
  oculta (el HUD del stream ya muestra FPS/kbps).
- Muestreo a 1 Hz reutilizando el tick existente del HUD; lógica de
  cómputo aislada en clases puras (/) para test unitario JVM.
- Sin dependencias nuevas: los gauges son una View propia (Canvas).
```

## 3. Motivation

El owner pidió una ventana de espera informativa estilo handheld (AYN
Thor): saber qué está consumiendo el teléfono mientras no hay juego.
Las métricas son del DISPOSITIVO (no del stream): en espera no hay
telemetría del plugin, y el FPS relevante para la referencia es el de
render del teléfono. La GPU no tiene API estándar: se lee el busy del
driver cuando el SoC lo expone (Adreno kgsl) y el gauge queda «—» (sin
datos) en el resto; nunca inventa números.

## 4. Acceptance

| Requirement | Verification |
|---|---|
| CPU % se calcula por delta de `/proc/stat` (busy/total entre muestras) | TEST-0156 (`CpuSample` puro) |
| FPS se estima con ventana deslizante de ticks de frame | TEST-0156 (`FpsWindow` puro) |
| RAM % y GB desde totales/uso del ProcessInfo | TEST-0156 (`RamReading` puro) |
| GPU: solo porcentaje del driver si existe; null → gauge sin dato | TEST-0156 (`GpuBusy` parsing kgsl) |
| Panel de gauges solo en Linked + INACTIVE; oculto en STREAMING | verificación en banco físico |
| Sin dependencias nuevas de gradle | build.gradle.kts sin diff de deps |

## 8. Implementation

```text
- android: ui/GaugeView.kt (View circular reutilizable: arco + valor +
  etiqueta), ui/DeviceMetricsSampler.kt (lógica pura: CpuSample,
  GpuBusy, RamReading, FpsWindow + adaptadores Android: /proc/stat,
  kgsl sysfs, ActivityManager.MemoryInfo, Choreographer),
  PairingActivity.kt (panel visible en local + INACTIVE, tick 1 Hz),
  activity_pairing.xml (metrics_panel Linear horizontal con 4 GaugeView),
  strings.xml (labels CPU/GPU/RAM/FPS).
- tests: TEST-0156 (DeviceMetricsSamplerTest, JVM).
```
