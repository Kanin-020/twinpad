# Change Request (CR) — CR-053 — Twinpad

| | |
|---|---|
| ID | CR-053 |
| Title | HUD del stream con métricas por etapa del pipeline de video del Plugin (`video_stage_stats`) |
| Type | `New behavior` |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-10-31 |
| Decided | 2026-10-31 |
| Decided by | Owner (instrucción «Commit y el siguiente cuello de botella»: el HUD por etapas era el item medición del mapa de cuellos pendiente) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `DES-004-cemu-adapter.md` (anotación §7.4), `docs/testing/traceability.md`; contrato del mensaje nuevo levantado en `core::game` (paridad de validación) |
| Sections | DES-004 §7.4 (telemetría), §12 (mapa de componentes) |
| Current version → new version | `DES-004 1.15.0 → 1.16.0` |

## 2. Requested change

**Current:**

```text
El Plugin mide cada etapa del pipeline de video (readback_copy, readback_ready,
pixel_convert, scale, i420_convert, hardware_encoder, openh264, encode_pipeline,
base64_json, ipc_send + eventos de flujo) y las promedia por ventana de 5 s, pero
las publica solo al log de Cemu («[TwinpadPerf]»), invisible al owner durante una
sesión real. El HUD del teléfono únicamente muestra los contadores locales
(render · recibidos · Mbps); ninguna telemetría del lado plugin/server llega al
teléfono, así que no hay atribución de dónde atascó un frame.
```

**Proposed:**

```text
En cada flush de métricas (cada 5 s, desde el hilo FrameLoop), el Plugin
compone una línea NDJSON extra por el canal IPC primario:

  {"type":"video_stage_stats","window_ms":5000,
   "stages":[{"stage":"<nombre>","count":C,"avg_us":A,"max_us":M}, ...],
   "events":[{"event":"<nombre>","count":C}, ...]}

El server la válida estrictamente (GameMsg::VideoStageStats de CORE), retiene la
última por juego en el GameRegistry (una sola línea vigente, la nueva reemplaza
a la previa) y la drena a las sesiones del teléfono que tienen ese juego
seleccionado (misma ruta que rumble/camera/mic/keyboard). La app la muestra como
una segunda línea del HUD: «pix 0.2 ms (0.5 ms) · b64 55 µs (90 µs) · 120 ev»
(rótulos cortos + promedio (máximo)); sin datos vigentes la línea se oculta.

Validación estricta (paridad server/phone): window_ms 10–10000; etapa/evento
de los catálogos canónicos (10/8 nombres, igual que el log); count 1–100000;
avg_us ≤ max_us; sin entradas repetidas; arrays a lo más del tamaño del
catálogo; campo desconocido → E4. La línea es telemetría: jamás bloquea al
encoder (se encola con la política drop-oldest del canal de video); si se pierde,
la ventana siguiente de 5 s repone los datos.
```

## 3. Motivation

El owner reportó «se alcanza la meta del 100% de fps pero no es estable, hay
tirones aunque los marcadores coinciden»: se puede tener fluidez media buena con
una etapa puntual (readback tras un shader nuevo, encode Hardware por picos)
empujando en un frame suelto. El mapa de cuellos de botella dejó pendiente
«HUD por etapas» como la medición que atribuye: sin ella, el siguiente ajuste
(¿duplicar buffers de readback? ¿mover el encode a otro hilo?) es una apuesta.
La medición por etapas ya existía (contadores atómicos del Plugin); solo faltaba
llevarla a la pantalla donde el owner puede verla mientras juega.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004/005/006 | no | la telemetría no altera el contrato de video/audio | none |
| SPEC-003 | no | el stream `video_frame`/media no cambia | none |
| SPEC-004/005 | no | input/audio intactos | none |
| SPEC-006 | nota | el mensaje nuevo `video_stage_stats` (juego→server→app) es una extensión del canal IPC primario ya definido; el catálogo de mensajes de SPEC-006 deberá extenderse formalmente y esta CR es su autoridad temporal | backlog: alta del mensaje en SPEC-006 §4 |
| ARCH | no | sin nueva frontera (usa el canal IPC y el drenaje de sesión existentes) | none |
| DES-004 | yes | §7.4 telemetría + fila §12 + changelog 1.16.0 | update |
| TEST-0115 (nuevo) | yes | round-trip/E4 de `VideoStageStats` en core | add row |
| TEST-0116 (nuevo) | yes | drenaje por-última-línea en el registro (server) | add row |
| TEST-0117 (nuevo) | yes | codec estricto + formateo HUD (Android) | add row |
| Code | yes | `core/src/game.rs`, `server/src/games.rs`, `server/src/main.rs`, `TwinpadClient.cpp`, `VideoStageStatsCodec.kt`, `PairingViewModel.kt`, `PairingActivity.kt`, `activity_pairing.xml` | done esta iteración |
| Data / migrations | no | — | none |
| Integrations / contracts | no | líneas nuevas ignoradas por peers antiguos (un teléfono con app vieja no las parsea; un server viejo no las reenvía) | none |

## 5. Options considered

1. Mensaje estructurado `video_stage_stats` por el canal primario con drenaje de última línea vigente — reutiliza la topología ya vigente (cola por juego + drenaje en la sesión), sin hilos nuevos ni cambios de wire en SPEC-003/SPEC-004.
2. Reutilizar el log «[TwinpadPerf]» y leerlo a mano fuera de la sesión — solo sirve para análisis posterior; no ayuda al HUD durante el juego.
3. Emitir las métricas en cada frame — un orden de magnitud más de tráfico sin ganancia; la ventana de 5 s basta para el HUD.

## 6. Decision

- **Outcome:** Approved
- **Rationale:** dota del diagnóstico de alta etapa para el cuello de botella siguiente (wire binario, readback) con costo mínimo (una línea JSON cada 5 s), reutiliza la topología vigente y no modifica contratos vigentes.
- **Rollout:** mismo release; server+Plugin+app despliegan juntos (un peer viejo simplemente no recibe/ignora la línea).

## 7. Execution checklist

- [x] DES-004 actualizado (1.16.0, changelog con referencia a CR-053)
- [x] `core::game`: `GameMsg::VideoStageStats` + validación estricta E4
- [x] Server: cola por juego + drenaje a la sesión seleccionada
- [x] Plugin Cemu: línea por el canal primario en cada flush de 5 s
- [x] Android: codec + ViewModel + HUD (línea bajo el chip del HUD)
- [x] Tests nuevos con IDs (TEST-0115/0116/0117)
- [x] `testing/traceability.md` actualizado (fila + historial)
- [x] `_index.md` de CRs actualizado
- [ ] SPEC-006: alta formal del mensaje (pendiente revisión de documento de contratos)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-31 | Buffy (agente) | Opened & Approved |
