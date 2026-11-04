# Change Request (CR) — CR-076 — Twinpad

| | |
|---|---|
| ID | CR-076 |
| Title | Ventana de espera: gauges de CPU/GPU/RAM/FPS del HOST (PC) como vista completa (referencia visual AYN Thor) |
| Type | `Feature` |
| Status | `Applied` |
| Author | Buffy (agente) + Product owner |
| Created | 2026-11-04 |
| Decided | 2026-11-04 (rev v2 y v3 el mismo día) |
| Decided by | Product owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-006 §4 (1.24.0), DES-002 (nota), `testing/traceability.md`, `core/src/game.rs`, `server/src/{lib.rs,main.rs,host_metrics.rs}`, Android (layout/activity, ViewModel/Activity, codec) |
| Sections | Pantalla de sesión (Linked, SPEC-002 §6); canal de sesión de SPEC-001 §4.2 |
| Current version → new version | SPEC-006 1.23.0 → 1.24.0; DES-002 nota update pendiente |

## 2. Requested change (v2 — corrección del owner)

**Current (v1, supersedida):**

```text
- Gauges medían el TELÉFONO (CPU /proc/stat/kgsl, RAM ActivityManager,
  FPS pelo Choreographer) en un panel-overlay abajo del session_view.
```

**Proposed (v3, refinamiento del banco, aplicada):**

```text
4. El monitor de espera es FULL-SCREEN de verdad: retícula 2×2 de
   GaugeView que ocupa toda la pantalla (cada gauge 1/4 del área,
   altura autoscala por celda). Se elimina el texto centrado «Sin
   segunda pantalla activa» (txt_session_message fuera del layout y
   del Activity): el único texto es la lista de juegos, al pie.
5. Hallazgo del banco: el server en ejecución era un binario anterior
   (inode distinto al build 17:06) → el teléfono no recibía
   host_metrics. El server release vigente corre como unidad de
   usuario (systemd-run --user twinpad-bench) con el env del script
   (TWINPAD_VIDEO_BITRATE_KBPS=10000).
```

**Proposed (v2, aplicada):**

```text
1. Los datos mostrados son los de la PC (HOST que corre twinpad-server),
   NO los del teléfono: nuevo mensaje de sesión `host_metrics`
   (Servidor→Android) con cpu_percent, gpu_percent opcional,
   ram_used_mb/ram_total_mb y game_fps (tasa de publicación del Plugin
   del juego seleccionado, Δgfn/Δs); emisión cada 2 s mientras la
   sesión viva.
2. Vista COMPLETA de espera (no overlay): un contenedor full-screen
   (waiting_view) dentro de session_view, POR ENCIMA del Framebuffer
   residual — cuando se pierde la conexión el último frame renderizado
   NO queda visible; se muestra la vista de espera (mensaje, lista de
   juegos y gauges) para INACTIVE (stream_stop) e INTERRUPTED (corte).
   Al STREAMING la vista se retira y el video regresa inmediatamente.
3. Sin datos inventados: GPU ausente en el host → «—»; fps sin juego o
   sin frames frescos → «—». Los porcentajes fuera de 0..100 en el wire
   rechazan la línea (E4) y el codec Android la descarta.
```

## 3. Motivation

(v1) El owner pidió una ventana de espera informativa estilo handheld
(referencia AYN Thor). (v2, corrección del owner): «Los datos que debe
mostrar son los de la PC no los del teléfono» y «Debe ser una vista
completa no un overlay; actualmente cuando se queda sin conexión muestra
el último frame renderizado». El teléfono NO mide nada de sí mismo; el
Servidor mide al host y lo publica por la sesión ya existente.

## 4. Acceptance

| Requirement | Verification |
|---|---|
| `host_metrics` round-trip y rechazos estrictos en core (deny_unknown_fields, cpu/gpu ≤ 100, RAM coherente, fps ≥ 0) | TEST-0157 (`host_metrics_round_trips_and_rejects_bad_fields`, core) |
| Parsers del HOST puros: /proc/stat (busy/total), /proc/meminfo (usada=total−available), GPU «37 %»/«37%», fps = Δgfn/Δs con techo | TEST-0157 (`cpu_stat_and_meminfo_parse_host_values`, `gpu_busy_and_fps_follow_the_wire_contract`, `sampler_needs_two_cpu_ticks...`, server) |
| Codec Android estricto (gpu/fps omitibles, rechazo de porcentaje fuera de rango, RAM incoherente, fps negativo, tipo ajeno) | TEST-0157 (`HostMetricsCodecTest`, JVM) |
| Vista completa de espera cubre el último frame (INACTIVE/INTERRUPTED: sin frame residual visible); vista de vuelta al STREAMING | verificación en banco físico |
| Emisor del SERVER con sesión viva por sesión viva y parada con la bandera compartida (patrón del emisor de pings CR-055) | wiring revisado; emisión observable en banco (debuglog «host_metrics») |

## 8. Implementation

```text
- core: `GameMsg::HostMetrics` (wire `host_metrics`) + validación
  (HOST_METRICS_PERCENT_MAX, ram_total ≥ 1, used ≤ total, fps ≥ 0).
- server: módulo `host_metrics` (HostMetricsSampler, parse CPU/RAM/GPU
  puros, fps_from_gfn, spawn_host_metrics_sender a 2 s por sesión con
  el lock de línea compartido y la bandera session_running), cable
  en main.rs (spawn + join), sin persistencia.
- Android: `wire/HostMetricsCodec.kt` (estricto), ViewModel patrón
  hostMetrics (StateFlow) ruta `host_metrics`, layout: waiting_view
  (v3: retícula 2×2 full-screen sobre drc_content con los 4 GaugeView
  + lista de juegos al pie, sin mensaje centrado), PairingActivity:
  visibilidad de la vista por StreamState (no-STREAMING) y tick de
  1 Hz rellenando los gauges del host («—» sin dato).
- Eliminado (v1): DeviceMetricsSampler.kt del teléfono y su test
  (TEST-0156 supersedeí); el layout ya no tiene panel-overlay.
- tests: TEST-0157 (core round-trip + server parsers + app codec).
```
