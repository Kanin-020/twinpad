# Change Request (CR) — CR-081 — Twinpad

# CR-081 — El canal media UDP muere por un watchdog que confunde «AU incompleta» con «canal muerto», el fallback TCP no tiene listener y el binding del relay no expira

## 0. Metadatos

| | |
|---|---|
| ID | CR-081 |
| Title | Watchdog media: no matar UDP con datagramas autenticados en vuelo; server con listener TCP 8425 (fallback §8.5); binding del relay con expiración y log |
| Type | `Correction` (defecto de implementación relativo a al SPEC-003 §4.4 y §8.5) |
| Status | `Applied` |
| Author | Buffy (Codebuff), a pedido del owner (repo SDD) |
| Created | 2026-11-05 |
| Decided | 2026-11-05 |
| Decided by | owner (duque: «aproved» en el chat) |

- **Área:** Server PC (Rust, `server/src`) + App Android (Kotlin, `stream/`) — canal media UDP/TCP (SPEC-003 §4.4/§8.5).
- **CR relacionado:** CR-080 (causa de clock mezclada, ya aplicado y activo — el «AV Sync Warning» desapareció del logcat; este CR cubre el corte restante del streaming, causa separada y confirmada), CR-062, CR-065, CR-058 (R11), CR-054.

## 1. Síntoma

En sesión activa el teléfono queda en 0 fps mostrando el monitor full-screen de espera (CR-076 v3) aunque el server **emite AU sin pausa** hacia el teléfono. Reproducido el 2026-11-05 entre 15:24 y 15:33 con server persistente en primer plano (pid 226984) y re-empareje fresco (`session_id=8NGN1lA8`, `join nuevo addr=192.168.100.143:36566`).

## 2. Cadena causal confirmada (tres movers, una cadena)

1. **App — watchdog del fallback mata el transport UDP en frío** (`UdpPreferredMediaClient.runFallbackWatch`): a los 900 ms evalúa `receivedAnyDatagram()`, que es el flag `anyAuthenticatedDatagram` y **solo se enciende cuando el reassembler entrega una AU completa**. Una IDR de 599 fragmentos cuya ráfaga pierde ≥2 datagramas de un mismo grupo FEC no completa jamás en 900 ms, aunque el canal reciba y autentique datagramas individuales (`ingestUnit` autentica y avanza `latestSeq` aunque devuelva `null`). Con la IDR perdida, el flag queda falso y el watchdog ejecuta `udp.close()`: el socket UDP muere con el stream vivo del otro lado.
2. **Server — el fallback TCP §8.5 no tiene destino:** `ss -tlnp` del server muestra **ningún listener TCP 8425**; `tcp.start()` choca contra `ECONNREFUSED`, el evento `Closed` cierra el canal y la ventana de recuperación de 10 s agota → `INACTIVE` → monitor full-screen. El logcat fijó el apagado a las 15:32:23 (`Cancel FGS ... twinpad_session`).
3. **Server — el binding del relay no expira:** con el socket UDP del teléfono cerrado, los keepalives dejan de llegar y el relay de `media_udp.rs` **sigue emitiendo AU al puerto muerto indefinidamente, sin log** (el keepalive del mismo nonce refresca el binding en silencio y no hay camino de expiración). El server no se entera de que el media murió.

Evidencia de campo (15:25–15:33): server emitiendo AU sanos fn=55→262 (`datagrams=599` IDR, ~30 fps, ts monotónico, destino siempre `:36566`); teléfono con Recv-Q UDP que drena (55 296 B → 0 → 0 en 4 s) y socket UDP **ausente** a las 15:31:55 (muerto por el watchdog); `xgfGetFPS pid=17522 fps=-1`; teléfono viviendo y mandando input (`TwinpadGyro seq=7130`); `ss -tlnp` del server sin 8425/TCP; Teléfono `RcvbufErrors=1 804 181` históricos del boot (delta en streaming pendiente, PIEZA D del diagnóstico abajo).

## 3. Cambio solicitado (before/after exacto)

**Pieza A — App (watchdog, `UdpMediaClient.kt`):**

```text
AHORA:  a los 900 ms, si el reassembler NO entregó ninguna AU completa
        (receivedAnyDatagram()==false), se hace udp.close() y el TCP toma el canal.

DESPUÉS: a los 900 ms, el UDP solo se da por muerto si el reassembler NO autenticó
        NINGÚN datagrama individual en el período (p. ej. reassembler.latestSeq sin
        avanzar / contador de datagramas autenticados == 0). Con datagramas
        autenticados en vuelo aunque las AU no ensamblen (pérdida ≥2 por grupo FEC),
        el UDP queda como transporte vigente; el gate del decoder sigue esperando
        IDR (§14) y la siguiente IDR completa reactiva la presentación.
```

El frame pacing de CR-048 y el gate §14 (waitingForIdr) no se tocan.

> **Hallazgo al aplicar (2026-11-05):** la pieza B ya estaba viva en el código — `main.rs` liga `TcpListener::bind(("0.0.0.0", MEDIA_PORT))` con `run_media_listener`/`handle_media_client` (`media.rs`) en el mismo protocolo §4.2 del `H264MediaClient`. El lado del CR que entró nuevo server-side es la propiedad del canal §4.4 (`bump_tcp_owner` de CR-062), verificado con TEST-0163; el listener RPC 8425 en runtime queda bajo observación en el banco (el `ss -tlnp` del binario del 15:33 no lo mostraba — binario viejo o bind fracasado en esa corrida).

**Pieza B — Server (listener TCP 8425, `SPEC-003 §8.5`):**

```text
AHORA:  el server solo escucha el media por UDP (8425/UDP); el fallback TCP del
        cliente no tiene a dónde conectar y muere en ECONNREFUSED.

DESPUÉS: el server escucha 8425/TCP con el canal media TCP de §8.5: autentica el
        JOIN del cliente (§4.2 sellado, misma media_key derivada del session_key del
        pair vigente) y reenvía las AU del stream activo en records (mismo formato
        del canal media binario, CR-054), sin FEC. El cliente H264MediaClient
        consumidor ya existe; el cambio server-side es el listener + dispatcher del
        stream vigente hacia el socket TCP autenticado.
```

**Pieza C — Server (expiración del binding del relay, `media_udp.rs`):**

```text
AHORA:  el relay emite a chan.addr para siempre mientras el pairing está activo;
        los keepalives del MISMO nonce refrescan el binding sin log; con el socket
        del cliente muerto no llega señal alguna y el datagrama sigue saliendo a un
        puerto muerto sin ningún log.

DESPUÉS: si en una ventana (10 s, teniente margen sobre el keepalive de 1 s de
        R11) no llega ningún JOIN/keepalive/datagrama autenticado del canal, el
        relay sale con log («udp-relay salida: sin keepalives N s, addr=…») y deja
        de emitir; el siguiente JOIN re-abre con el camino ya vigente («udp-relay
        join nuevo», media_udp.rs l.241). El refresco de binding por keepalive del
        mismo nonce se queda con log de una línea («udp-relay binding refrescado
        addr=…») para que el diagnóstico en distancia no dependa del lado Kotlin.
```

## 4. Impacto

| Artefacto | ¿Afectado? | Impacto | Acción |
|---|---|---|---|
| SPEC-003 §4.4 / §8.5 | sí | comportamiento del transporte media requiere las tres piezas | actualizar al aplicar (misma iteración) |
| REQ-XXX | no | — | none |
| ARCH | no | no cambia frontera server/app | none |
| DES-XXX | no | — | none |
| TEST-0162/0163/0164 | sí | tres tests nuevos | filas en `testing/traceability.md` |
| Código | sí | app: `stream/UdpMediaClient.kt` (~10 líneas), server: `main.rs` listener + `media_udp.rs` expiración | PR único con las tres piezas |
| Data/migraciones | no | — | none |
| Integraciones/wire | no | el wire §4.2/§4.4 no cambia | none |

## 5. Opciones consideradas

1. **Las tres piezas** (esta propuesta) — curan la cadena completa: sin (A) el UDP muere en frío ante una IDR incompleta; sin (B) el fallback TCP no tiene destino; sin (C) el server carece dediagnosticación cuando el media murió.
2. **Solo pieza A (sin nada del server)** — insuficiente: si el UDP de verdad muere (caída de red real), el fallback queda sin destino y el server no se entera. Reprobado por el criterio del SPEC §8.5, que exige el fallback TCP.
3. **No hacer nada** — el streaming queda en el ciclo «0 fps + monitor» ante cualquier pérdida de IDR: inaceptable; el owner ya lo reportó repetidamente.

## 6. Decisión — Aprobado por el owner y aplicado (2026-11-05)

- **Outcome:** aprobado en el chat («aproved»); las tres piezas aplicadas el mismo día.
- **Rationale:** la cadena causal confirmada exige las tres piezas juntas (§5, opción 1).
- **Rollout:** misma iteración (sin migración; sin cambio de wire). Pieza C con re-chequeo del binding ANTES de emitir (la AU que despierta la iteración vencida no sale a un destino muerto — los primeros dos tests fracasados de TEST-0164 señalaron el orden correcto).

## 7. Checklist de ejecución

- [x] SPEC-003 §4.4/§8.5 actualizado con versión bump (1.15.0) + changelog que referencia CR-081
- [x] Código app aplicado (PIEZA A) con `TEST-0162` (JVM Passed)
- [x] Código server aplicado (PIEZA C nuevo; PIEZA B verificada ya existente) con `TEST-0163`/`TEST-0164` (cargo test host: 86+11+… [​verde]
- [x] Filas TEST-0162/0163/0164 + fila CR-081 en `testing/traceability.md`
- [x] `_index.md` con la fila del CR-081
- [x] Gates verdes: `cargo test` server EXIT=0 (90 suites sin fallos) + `testDebugUnitTest/ktlintMainSourceSetCheck/detekt/assembleDebug` BUILD SUCCESSFUL; APK reinstalado para la prueba del owner
- [ ] Logs temporales del diagnóstico (logcat `/tmp/media_phone.log`) y ajuste del CR-080 cerrado con el seguimiento de runtime — pendiente del preview del owner en banco (reintento del stream vigente)

## Changelog

| Versión | Fecha | Autor | Cambio |
|---|---|---|---|
| 0.1.0 | 2026-11-05 | Buffy | abierto con las tres piezas y la cadena causal confirmada |
| 0.2.0 | 2026-11-05 | Buffy | aprobado por el owner y aplicado: pieza A (app, TEST-0162), pieza C (server, TEST-0164) y pieza B verificada existente (TEST-0163); SPEC-003 1.15.0; re-chequeo del binding antes de emitir; gates verdes (cargo test server + gradle suite app) |
