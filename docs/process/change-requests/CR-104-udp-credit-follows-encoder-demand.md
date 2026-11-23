# Change Request (CR) — CR-104 — Twinpad

| | |
|---|---|
| ID | CR-104 |
| Title | El crédito del relay UDP debe seguir la demanda real del encoder (CR-101) y una AU descartada por crédito debe avanzar el watermark de `latest` |
| Type | `Behavior change` + `Defect fix` |
| Status | `Decided` (opción A, medida en banco 2026-11-23) |
| Author | Buffy (agente) |
| Created | 2026-11-23 |
| Decided | 2026-11-23 |
| Decided by | Usuario (petición directa: «la transmisión por UDP debe ir al 100 sin pérdidas o las mínimas posibles») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `SPEC-003` (§4.4 relay UDP, token bucket CR-083/CR-101) |
| Sections | SPEC-003 §4.4 (crédito de emisión por sesión) |
| Current version → new version | `SPEC-003 1.18.0 → 1.19.0` |

## 2. Requested change

**Current (CR-083 + CR-101):**

```text
El relay emite AU no-IDR solo si la AU cabe en el crédito (1 s del bitrate
efectivo kbps). Una AU que no cabe se descarta y se sigue esperando.
```

**Proposed (opción A, aplicada):**

```text
El techo de crédito es max(kbps configurado, demanda real del encoder), donde la
demanda es la producción medida en la ventana de 1 s anterior (bytes de TODA AU
publicada, emitida o descartada), acotada a 3× el kbps configurado y con
decaimiento 1/8 por ventana cuando baja. Una AU descartada por crédito avanza
igualmente el watermark de `latest` (latest-frame-wins).
```

## 3. Motivation

Banco físico (Android + Cemu, `h264_vaapi`, 1280×720, `bitrateKbps=15000`):

- El encoder emite ~31 Mbps (AU media 129 KB a 30 fps) pese a pedirle 15 Mbps
  (`rc_mode=CBR` no se respeta en el driver VAAPI del host): el crédito de
  CR-101 descartaba 9 AU/s.
- Medición antes: 20,7 fps emitidos, 132 AU descartadas en 14,5 s, huecos de AU
  en el cliente → tirones. CPU del servidor 51 % de un núcleo.
- El `continue` del descarte **no** avanzaba `last_fn`/`last_timestamp_us`, así
  que `wait_for_forwardable` devolvía la MISMA AU y el hilo giraba en seco: el
  contador de descartes por ventana registraba 2 295 353 AU (imposible a 30 fps)
  y se comía el núcleo.

## 4. Options

| Option | Description | Result |
|---|---|---|
| A | Techo adaptativo + watermark en el descarte | **Elegida** |
| B | Corregir el rate-control del encoder VAAPI (`TwinpadH264.cpp`) | Descartada por ahora: exige recompilar Cemu; queda como recomendación |
| C | Subir el `bitrateKbps` fijo de la app | Descartada: no arregla el descarte ni el giro en seco, y oculta el desbordamiento del encoder |

## 5. Decision

Opción A.

- `server/src/media_udp.rs`: `next_demand_kbps` (demanda acotada a 3× y
  decaimiento 1/8) y avance de `last_fn`/`last_timestamp_us` en el descarte.
- Test `demand_ceiling_follows_encoder_within_three_times` (TEST-0200).

## 6. Consequences

- Medición después (mismo banco): 29,5 fps emitidos (los 30 del encoder),
  **0 AU descartadas** en 13,8 s de régimen, CPU del servidor 4,7 %.
- Cliente: `VideoFrameStats 2 s: enqueued=60, acquired=59` y
  `getRealTimeFps is 30` — sin frames perdidos en la decodificación.
- El arranque de la sesión descarga la cola del encoder con descartes
  (latest-frame-wins) hasta que la demanda medida se estabiliza; es el
  comportamiento buscado, no una pérdida en régimen.
- Riesgo asumido: la protección de inundación queda en 3× el kbps configurado;
  si el encoder se desborda más, se recorta al techo y se descarta lo sobrante.
- Pendiente (opción B): hacer que `h264_vaapi` respete el bitrate pedido
  eliminaría también el sobreconsumo de ~20 Mbps en LAN.
