# CR-083 — IDR confiable tras join/re-JOIN + cap de tasa en el relay UDP + renegociación del gate de decoder en la app

## 0. Metadatos

| Campo | Valor |
|---|---|
| ID | CR-083 |
| Título | El hook de IDR ignora fallos y no reintenta; el relay UDP reenvía al bitrate que sea (6 600 dgs/s); el gate §14 espera un IDR que sin reconfiguración de Cemu no llega nunca |
| Fecha | 2026-11-05 |
| Reportado por | owner (duque: «Sesion corriendo corriendo 0 fps roto» — sesión de 16:34:47 con APK CR-082 y server reiniciado 16:34:16 16:05) |
| Evidencia | Host: `OutDatagrams` +19 947 en 3 s (~6 600 dgs/s salientes; `InDatagrams` solo ~5/s); TCP 8426 (plugin→server): `bytes_sent` +19 253 884 en 3 s (~6,4 MB/s ≈ 55 Mbps, ~133 AU/s, ~48 KB/AU; 3,6 GB acumulados desde 16:34:16). Teléfono: socket UDP vivo conectado a :8425 (v4-mapped en `/proc/net/udp6`), RX ~6 200 dgs/s (+12 415 en 2 s) **sin** `NoPorts` ni `RcvbufErrors` nuevos → el reassembler drena todo y lo descarta silencioso; decoder `mId 0` (c2.mtk.avc.decoder) creado 16:34:26 con **cero** AUs (ningún `Stats`), outputFormat inicial con **crop 318×238 (SPS 320×240 = CAM, no juego)**; watchdog CR-082 disparó re-JOINs y el audio se reprovisionó ~16:36:45 — sin efecto |
| Estado | `Applied` |

## 1. Problema A — hook de IDR sin traza de fallo ni reintento (server)

`server/src/media_udp.rs:handle_join` hace:

```rust
let _ = (deps.on_media_joined)();
println!("udp-relay join nuevo addr={addr} dueño=UDP (IDR solicitado)");
```

- El resultado del hook (`bool`, CR-051) se **ignora**: si falla (p. ej. el socket writer de la sesión no está listo en el instante del join, o el plugin no responde al pedido de IDR), el log dice igualmente «IDR solicitado» — **mentira de log** y no existe reintento.
- El mismo patrón vive en el relay TCP (`server/src/media.rs`, `handle_media_client`).
- Consecuencia observada: la sesión de 16:34:47 arrancó 17+ min a 0 fps con un torrente de P-frames porque **jamás llegó una IDR**: el gate §14 del decoder retiene todo hasta la primera IDR. El «revive al cambiar resolución/vista» del duque es exactamente eso: solo una reconfiguración de Cemu fuerza un IDR nuevo en el encoder.
- Nota relacionada: la primera AU publicada al hub esta sesión tuvo SPS **320×240** (crop del decoder 318×238) — dimensión de la CAM (CR-034), no del juego. El hook de IDR debe además regir la AU que entra al hub ANTES de la primera IDR del juego (ver pieza B).

### Propuesta

1. `handle_join` y `handle_media_client`: el hook `on_media_joined` se ejecuta con **reintentos con backoff corto** (propuesto: 3 intentos a 0/250/500 ms). Si el último intento falla: `println!("udp-relay IDR: hook falló tras N intentos (fn_ pendiente)")` — sin engañar con «IDR solicitado».
2. El log «join nuevo» pasa a reflejar el desenlace: `…(IDR solicitado ok)` / `…(IDR falló: sin retransmisión de IDR)`.
3. **Sin cambio de wire**: el pedido de IDR sigue siendo el de CR-051 (reenvío video_config + IDR inmediata al (re)conectar).

### Cuándo NO aplica

- No se cambia cuándo el hub exige IDR para hacer forwardable una AU (TEST-0139 vigente).

## 2. Problema B — el relay UDP emite sin cap de tasa (server)

`relay_udp_session` reenvía cada AU tan pronto `wait_for_forwardable` la libera, **sin ningún límite de tasa por sesión**: el encoder del plugin a ~6,4 MB/s (55 Mbps, ~133 AU/s) se convierte en ~220 datagramas por AU = ~6 600 dgs/s hacia el teléfono, en contra de `effective_kbps` (§4.3) que ronda 10 000 kbps configurado (CR-027/CR-064: 10 000 kbps fijos, sin slider del usuario).

- El estómago del teléfono (4 MB de socket, CR-065) drena, pero a 6 600 dgs/s el reassembler solo ve basura descartable y el decoder sigue cerrado; el costo de radio y CPU del flood es gratuito y sostenido (3,6 GB en ~9 min del server arriba).
- La escalera §4.3 (R10/R11) reacciona a las ventanas P/D: **no acota el envío en tiempo real**.

### Propuesta

1. En `relay_udp_session`, `UdpEmitter::seal_au` ya tiene el tamaño; añadir **token bucket por sesión** rellenado según `effective_kbps` (misma fuente que la escalera; min 10 000 kbps peeled): si la AU excede el crédito disponible, **se descarta la AU** (latest-frame-wins: la siguiente tendrá crédito suficiente o la IDR sí se envía siempre).
2. La IDR **nunca** se interrumpe por el token bucket (excepción explícita).
3. Log una única vez por ventana de 2 s cuando se descarte por crédito: `udp-relay tasa: descartadas N AU por crédito (P credit kb/s)`.
4. **Sin cambio de wire.**

### Cuándo NO aplica

- Si el `configured_kbps` real del usuario fuera 55+ Mbps en su config, el cap no baja de esa cifra (el bucket respeta el bitrate configurado; la medición de 6,4 MB/s sugiere que NO es el caso).

## 3. Problema C — el cliente espera IDR indefinidamente sin renegociar (app)

El gate §14 espera la primera IDR; el watchdog CR-082 re-arma por progreso de seq (los fragmentos autentican aunque nunca decodifiquen) — exactamente el caso tabú: **progreso habitual + AU nunca entregadas**. En esta sesión el watchdog correcto pero inútil: re-JOIN sobre un relay que solo manda P-frames.

### Propuesta

> **Nota de aplicación (2026-11-05):** la vía ya existente para forzar la
> resolución+IDR del CR-051 es la `video_config` del teléfono→server
> (main.rs la recibe y la reléa a Cemu; un `stream_start` del teléfono
> no existe en el wire). La renegociación aplicada reenvía el
> `video_config` VIGENTE del drawer y el server acompaña el reenvío
> con `send_cemu_video_force_idr` (la ACCIÓN de banco que revive).

`MediaRecoveryPolicy` añade una comprobación complementaria a la del progreso de seq: si transcurrieron `AU_STARVE_MS` (propuesto: 8 000 ms) **sin que se entregue ninguna AU completa** PERO con fragmentos auténticos entrando (señal ya disponible: `reassembler.latestSeq` avanza, `lastSeqAccepted` avanza), el cliente **renegocia con el server vía el canal de control** — el server entra al estado «stream_start» re-pedido de resolución (la ACCIÓN que en banco revivió el stream, vía el reenvío video_config de CR-030/CR-051) **sin tocar** el one-shot §8.5 ni el re-JOIN §4.4 vigentes.

- Mensaje nuevo o existente: preferente **reutilizar un `stream_start` idéntico** (w/h del propio stream vigente) en el canal de control; sin cambios de wire (es una secuencia ya existente).
- Máx 1 renegociación por minuto de starve (ladder de intentos; tras 3: Close + recreación del cliente — lo mismo que CR-082 ya hace tras el re-JOIN fallido).
- Refactor permitido: el policy pasa a dos señales (`seq progress` y `AU delivered`); los accessors del test JVM se amplían a la segunda señal.

## 4. Tests

- `TEST-0167` (server): hook `on_media_joined` fallido en `handle_join` — reintento con backoff y log de desenlace («IDR falló») en lugar de «IDR solicitado» (EXtendible test TCP análogo).
- `TEST-0168` (server): token bucket del relay UDP — la AU no-IDR se descarta cuando el crédito está agotado; la IDR se emite siempre; el crédito rellena a `effective_kbps`.
- `TEST-0169` (JVM app): `AU_STARVE_MS` sin AU entregadas con fragmentos auténticos ⇒ renegociación una sola vez (no spam) y luego Close tras el 3.er intento; AU completas presente ⇒ sin renegociación.

## 5. Decisión — Aprobado y aplicado (2026-11-05)

- **Outcome:** aprobado en el chat («aproved»); las tres piezas aplicadas el mismo día (ver §6). La pieza C se aplicó con la ruta de la Nota de aplicación (`video_config` vigente en vez de `stream_start`, que no existe en el wire teléfono→server).
- **Rationale:** la sesión 16:34:47 demuestra que con los CR-081/082 el recovery no basta: **sin IDR** el stream muere aunque todo el transporte esté perfecto; el flood de 6 600 dgs/s es daño colateral comprobado del envío sin cap.
- **Rollout:** misma iteración; ENGATES SERVER REQUIREN BINARY NUEVO (recompilar/reiniciar el server con la pieza A/B; el decreto del duque vigente pid 306319 corre la 16:05 sin CR-083). Banco del duque con la cola del terminal visible.

## 6. Checklist

- [x] Server: retry de IDR en `handle_join`/`handle_media_client` + logs de desenlace (TEST-0167, media.rs `idr_with_retry`; 3 reintentos 0/250/500 ms)
- [x] Server: token bucket por sesión en `relay_udp_session` anclado a `effective_kbps` con piso 10 000 kbps, cap 1 s de crédito, IDR siempre pasa, log de descartes cada 2 s (TEST-0168)
- [x] App: señal de starve en `MediaRecoveryPolicy` (AU_STARVE_MS 8 000, RENEGOTIATE_MAX 3, gap 1/min) + evento `Renegotiate` + renegociación por `video_config` vigente en `PairingMediaSession` (TEST-0169)
- [x] Server: `video_force_idr` (CR-051) acompañando cada reenvío de `video_config` del teléfono (main.rs)
- [x] `traceability.md` TEST-0167/0168/0169 Passed + fila CR-083 en `_index.md`
- [x] Gates verdes: `cargo test` server EXIT=0 (88+ suites); gradle `:app:testDebugUnitTest :app:ktlintMainSourceSetCheck :app:detekt :app:assembleDebug` BUILD SUCCESSFUL (detekt CyclomaticComplexMethod corrigido extrayendo `rejoinDecision`/`starveDecision`)
- [x] APK reinstalado por adb (`Success`)
- [ ] Banco del duque: sesión con stream a fps sin «0 fps roto»; binary del server CON CR-083 requiere rebuild/restart

## Changelog

| Versión | Fecha | Autor | Cambio |
|---|---|---|---|
| 0.1.0 | 2026-11-05 | Buffy | abierto en Draft: hook IDR ignora fallos (log miente, sin retry), relay UDP sin cap de tasa (6 600 dgs/s medidos; 3,6 GB acumulados), cliente re-JOINea un stream sin IDR nunca entregable (gate §14 sin AU); primera AU del hub de esta sesión fue CAM 320×240 |
| 0.2.0 | 2026-11-05 | Buffy | aprobado por el owner y aplicado: `idr_with_retry` (3 intentos, desenlace visible en los logs join/token bucket con piso 10 000 kbps y IDR siempre pasa + `video_force_idr` tras video_config del teléfono; app: señal de starve de AU (`AU_STARVE_MS` 8 000, `RENEGOTIATE_MAX` 3, gap 1/min) con renegociación vía `video_config` vigente (nota de aplicación: la vía `stream_start` no existe en el wire teléfono→server); TEST-0167/0168/0169 Passed; gates verdes (cargo EXIT=0, gradle BUILD SUCCESSFUL); APK reinstalado |
