# CR-084 — El reassembler del teléfono purga AUs de más de 256 fragmentos: la IDR de 1080p jamás ensambla (gate §14 y Qin=0)

## Cabecera

| Campo | Valor |
|---|---|
| ID | CR-084 |
| Título | El reassembler del teléfono purga AUs de más de 256 fragmentos: la IDR de 1080p jamás ensambla (gate §14 y Qin=0) |
| Fecha | 2026-11-05 |
| Autor | Buffy (agente) |
| Requerimientos | REQ-004 (stream de video), SPEC-003 §4.3/§4.4 |
| Documentos afectados | SPEC-003 (constantes de §4.4 si toca), código Android (`stream/UdpMediaClient.kt`), traceability |
| Estado | `Applied` |

## 1. Problema — dictamen de la sesión del 17:31–17:33 (banco del owner)

Con CR-083 aplicado y verificado en vivo (link.md fila), la sesión sigue en 0 fps. La cadena de evidencia recolectada hoy:

1. Server (binario con las 3 piezas, pid 365324): el relay emite AU continuas (`udp-relay AU fn=23601 idr=true datagrams=485`, P de 2–22 dg entre IDRs); el binding del teléfono se refresca por el keepalive (§4.4) — tránsito UDP teléfono→server auténtico y vivo.
2. Ningún «join nuevo» adicional ⇒ el watchdog CR-082 ve `seq` avanzando: **el teléfono recibe fragmentos auténticos**.
3. El decodificador del teléfono nunca encola nada: `MediaCodec [video-debug-dec] Stats 5416ms: Qin:0,DQin:0/0` (mId 3) y el mId 4 arrancó sin entregar. Qin=0 = cero AUs al codec.
4. En el código del teléfono (`UdpReassembler.ingestUnit` → `purgeAbandoned`): la ventana anti-replay de §4.4 (`REPLAY_WINDOW = 256`) purga cualquier AU pendiente cuyo `firstSeq <= seq_actual - 256`. Los fragmentos de una AU llegan con `seq` **consecutivos** (el relay los sella y envía de corrido): al llegar el fragmento 257 (seq = firstSeq + 256), la AU pendiente cae en `firstSeq <= firstSeq` → **se purga con los fragmentos 0..255 dentro**. Los fragmentos restantes recrean la entrada (`getOrPut`) con otro `firstSeq` y faltan las cabezas para siempre ⇒ **ninguna AU de más de ~256 datagramas ensambla jamás**.
5. La IDR de 1920×1080 son 485–588 datagramas (por encima del tope de 256) ⇒ la IDR jamás se entrega ⇒ el gate §14 del app (espera la primera IDR) retiene incluso las AU pequeñas que SÍ ensamblan ⇒ Qin=0, pantalla negra, 0 fps.
6. Las piezas de CR-083 trabajan pero chocan contra este muro: la IDR renegociada (CR-083 pieza C) también es >256 fragmentos y también muere; y la señal de starve (contador de AU entregadas) no dispara porque las P pequeñas SÍ se entregan por el reassembler (retienen el contador vivo).

El caso 16:34 del duque quedó explicado igual: «la primera AU del hub fue CAM 320×240 (417 dg)» — también >256 fragmentos, tampoco ensamblaba.

## 2. Propuesta — pieza única: regla de purga por AU viva

En `UdpReassembler.purgeAbandoned` (Kotlin, `stream/UdpMediaClient.kt`), abandonar una AU pendiente solo cuando **todos** sus fragmentos están fuera de la ventana, es decir cuando su último seq esperado ya venció:

```kotlin
// antes: if (firstSeq <= cutoff) remove
// después:
val lastExpectedSeq = firstSeq + (fragTotal - 1)
if (lastExpectedSeq <= cutoff) remove
```

- Los `seq` de una AU son consecutivos (emisores: `seal_au` del server, encoder del Cemu ⇒ la regla es exacta).
- El tope de memoria queda acotado igual: `MAX_PENDING_AUS = 4` tope de AUs pendientes + el descarte FIFO ya existente; una AU incompleta expira sola cuando entera sale de la ventana (≤ `fragTotal + 256` seqs — con `MAX_FRAG_TOTAL = 3_085` sigue siendo cotizado).
- Semántica de §4.4 intacta: la ventana anti-replay (256) sigue descartando replay de datagramas viejos; solo cambia CUÁNDO una AU incompleta se abandona.
- Doble verificación en el checklist: el gemelo Rust (`core/src/media_udp.rs`, líneas 660–693, misma constante y misma estructura de ensamblado) — si algún consumidor puede recibir AUs >256 fragmentos, replicar la regla exacta ahí también (no cambia el wire; diseño constante intacto).

## 3. Decisión — aprobado por el owner

- Aprobado («aproved» del duque, hoy) y aplicado el mismo día: código Android + gemelo Rust, gates verdes y APK reinstalado.
- Rationale del fix: la cadena del owner del streaming roto tiene tres capas resueltas (IDR confiable CR-083, cap de tasa, recuperación del canal) y el cuello final es UNA regla fuera de la ventana media real: el propio CR-065 ya tocó el problema de fragmentos de IDR perdidos por buffer del socket; esto es el complemento lógico en el reassembler.
- Riesgo bajo: no cambia wire, no cambia constantes de SPEC, no cambia ninguna máquina de estados del gate; solo la regla de abandono de AU incompleta.
- Rollout: app → gates → APK reinstalado por adb → banco del duque con la cola del server para ver el 1080p a fps.

## 4. Tests

| Test | Qué verifica |
|---|---|
| TEST-0170 (JVM nuevo, Passed) | Una AU de 500 fragmentos (por encima de `REPLAY_WINDOW`) llega completa por `ingestUnit` y se entrega con fn/ts vigentes (antes moría en la purga del fragmento 257); la entrega es única al llegar el último fragmento y el cuerpo llega intacto (fn=7_001, ts=5_000, idr, 49 983 B). |
| TEST-0171 (Rust nuevo, gemelo — Passed) | `core/src/media_udp.rs`: una AU sellada por el propio `seal_au` del server con ~470 fragmentos (> `REPLAY_WINDOW`) ensambra completa y se entrega una única vez al llegar el último datagrama; la AU chica siguiente entrega normal (sin estado sucio). |
| TEST-0138 Rust (actualizado a la regla CR-084, Passed) | `pending_au_abandoned_after_256_seq_gap` → `pending_au_abandoned_after_last_seq_gate`: el seq 256 ya NO mata una AU de 2 fragmentos (su último seq esperado sigue en ventana — el tail tardío SÍ ensambla); una AU cuyo último seq esperado ya venció (cutoff 4_044 ≥ 4_001) sí expira y su tail recreado no entrega nunca. |

## 5. Checklist

- [x] `UdpReassembler.purgeAbandoned`: regla swap a `lastExpectedSeq` (Kotlin) + TEST-0170 en `UdpMediaClientTest`
- [x] Verificar gemelo Rust (`core/src/media_udp.rs`): la misma regla vieja (`first_seq > cutoff`) estaba viva y se replicó (purga por `first_seq + frag_total - 1`, aritmética u64 antidesborde) + TEST-0171 y TEST-0138 actualizado
- [x] `traceability.md`: TEST-0170 y TEST-0171 → Passed + `_index.md` fila CR-084 → Applied
- [x] Gates: `cargo test` workspace EXIT=0 (124 tests del core con TEST-0171 en verde) + gradle BUILD SUCCESSFUL (TEST-0170 dentro de `:app:testDebugUnitTest`; detekt LongMethod solucionado recortando la cola del test)
- [x] APK reinstalado por adb («Performing Streamed Install» → Success) — banco del duque pendiente de confirmación en vivo

## 6. Notas de aplicación

- El fix es SOLO del reassembler: el server NO requiere rebuild — el binario vivo del duque (pid 365324) puede seguir corriendo.
- El test Rust pre-existente que fijaba la semántica vieja (`pending_au_abandoned_after_256_seq_gap` acreditado a TEST-0138 de CR-062) se actualizó a la semántica nueva declarada aquí (la AU con último seq en ventana ya no muere; el tail tardío ensambla).

## Changelog

| Versión | Fecha | Autor | Cambio |
|---|---|---|---|
| 0.1.0 | 2026-11-05 | Buffy | abierto en Draft: dictamen del banco del 17:31–17:33 (Qin=0 con fragmentos auténticos fluidos y IDR de 485–588 dg en el wire); la purga por `firstSeq<=seq-256` mata toda AU de más de 256 fragmentos en el reassembler del teléfono (la IDR nunca ensambla); propuesta de regla por último-seq esperado |
| 0.2.0 | 2026-11-05 | Buffy | aprobado por el owner y aplicado: regla swap en `purgeAbandoned` (Kotlin, `UdpMediaClient.kt`) replicada en el gemelo Rust `core/src/media_udp.rs::purge_abandoned` (con aritmética u64); TEST-0170 (JVM) y TEST-0171 (Rust) en Passed; TEST-0138 Rust actualizado a la semántica nueva; detekt LongMethod resuelto recortando el test; gates verdes (cargo EXIT=0, gradle BUILD SUCCESSFUL); APK reinstalado por adb (Success) |
