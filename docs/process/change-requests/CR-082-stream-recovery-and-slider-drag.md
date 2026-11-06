# CR-082 — Recuperación del canal media en la app + drag del slider de métricas

## 0. Metadatos

| Campo | Valor |
|---|---|
| ID | CR-082 |
| Título | Recuperación del canal media UDP/TCP en la app (watchdog periódico + re-JOIN) y drag robado del slider de métricas |
| Fecha | 2026-11-05 |
| Reportado por | owner (duque: «sigue a 0 fps», «se corta la transmisión de repente», «el slider ya no lo puedo volver a bajar») |
| Evidencia | `/tmp/media_phone.log` (sesión 16:07:18–16:08:04): video 30 fps 16:07:18–16:07:35, luego `MediaCodec Stats Qin:0` 29 s sin NINGUNA actividad de la app (ni fallback ni re-JOIN); pings de control cada 5 s intactos |
| Estado | `Applied` |

## 1. Problema A (corte de la transmisión, ~16:07:35)

El watchdog §8.5 (`UdpMediaClient.runFallbackWatch`) es **de un solo disparo**: decide a los 900 ms del arranque y muere. Tras ese instante no existe NINGÚN mecanismo en la app que detecte que el canal media se paró:

- El keepalive §4.4 (1 000 ms, mismo nonce) sigue enviándose (el hilo `runClient` sigue vivo), PERO si esos keepalives **no llegan** al server (red/Wi-Fi/AP), el server expira el binding a los 10 s (CR-081 pieza C, comportamiento diseñado) y corta el feed.
- La app ni siquiera sabe: nadie re-visita `receivedAnyAuthenticatedFragment()`, nadie re-anexa, nadie re-une.
- Resultado: imagen congelada a 0 fps, HUD pegado, app en silencio — la sesión del duque del 16:07:35.

El corte en sí es un evento de red real (los pings TCP corrieron siempre; el UDP se quebró); la falla del código es **no recuperarse**.

### Propuesta

1. **Watchdog periódico en `UdpMediaClient`:** en vez del check único a 900 ms, un hilo `fallbackWatch` que supervisa cada `WATCH_INTERVAL_MS` (propuesta: 2 000 ms) el avance de `lastSeqAccepted` desde la última revisión:
   - Con progreso: canal vivo, no hay acción.
   - Sin progreso por una ventana (`MEDIA_IDLE_MS`, propuesta: 5 000 ms, muy por encima del jitter y del periodo del keepalive de 1 000 ms): requiere re-JOIN — se manda un JOIN autenticado con **nonce NUEVO** (§4.2: el server toma un binding nuevo con seq 0; el `runClient` reinicia el reassembler y `lastSeqAccepted`); si tras 2 ciclos el re-JOIN no revivió el progreso, se entrega `Closed` a la app (ciclo actual de streams).
2. **Fallback §8.5 intacto:** el único disparo a 900 ms para el arranque frío queda igual (CR-081 pieza A, TEST-0162).
3. **Server sin cambios:** CR-081 pieza C queda sin ajustes: el re-JOIN con nonce nuevo crea un binding nuevo (camino ya cubierto por `join_takes_ownership_relay_fec_and_tcp_takeover`, TEST-0139).

### Cuándo NO aplica

- El watch NO engancha una IDR fragmentada: cuenta PROGRESO DE SEQ (bytes auténticos aceptados), no AU completas entregadas (misma señal que TEST-0162).

## 2. Problema B (drag muerto del slider de métricas, CR-079 v7)

`MetricsSliderContainer.onInterceptTouchEvent` roba el gesto tras el slop y devuelve los eventos a un gesto del parent — según logcat (drag inicio → dy -10/-37 → cancel/soltado ~7 ms después), el drag queda sin dueño: muere a los ~40–60 px, la asa del drag no responde; el panel queda casi siempre donde lo sueltas.

### Propuesta

`MetricsSliderContainer.onTouchEvent(event) = dragResponder?.invoke(event) ?: super.onTouchEvent(event)`: cuando el parent robó el gesto por intercept, el mismo cálculo del drag continúa en el parent y el flujo de eventos sí llega al controlador hasta soltar.

### Cuándo NO es un bug el «fullscreen pegado»

Ese «no baja» del duque a 16:09–16:10 corresponde al modo WAITING (stream muerto → panel fullscreen opaco sin asa), no al drag: el mismo corte de la sección §1. Si el duque quiere que el panel muerto permanezca plegable con stream visible congelado, es una decisión de UX aparte (no requerida para este CR).

## 3. Tests

- `TEST-0165` (JVM app): watchdog periódico — re-JOIN con nonce nuevo tras `MEDIA_IDLE_MS` sin progreso de seq; sin re-JOIN con progreso (contrapartida de TEST-0162).
- `TEST-0166` (instrumentado o manual banco): el drag vertical sobrevive a los ~60 px del slop (el logcat «MetricsSheet» del duque lo confirma en banco).

## 4. Sin cambios de wire

El wire queda idéntico: el JOIN es el §4.2 de siempre (nonce nuevo en el re-join), el server ya lo maneja (TEST-0139); el keepalive continúa cada 1 000 ms con su nonce primario.

## 5. Decisión — Aprobado y aplicado (2026-11-05)

- **Outcome:** aprobado en el chat («Aprobar el CR-082…»); las dos piezas aplicadas el mismo día (ver §6).
- **Rationale:** el corte en tránsito sin recuperación es un agujero del §4.4 que la pieza C de CR-081 dejó descubierta; el drag robado es defecto del v7 de CR-079.
- **Rollout:** misma iteración. REINTENTO del stream pendiente del banco del owner con la cola del terminal del server visible (los logs `udp-relay binding refrescado` cada ~1 s o `salida: binding sin keepalive … 10 000 ms` confirman la cadena exacta).

## 6. Checklist

- [x] Código app aplicado (watchdog periódico con `MediaRecoveryPolicy` + re-JOIN con nonce nuevo en `runClient` + `onTouchEvent` en el container) con TEST-0165 (Passed JVM); TEST-0166 (banco, Draft) pendiente del duque
- [x] Filas TEST-0165/0166 + fila CR-082 en `testing/traceability.md`
- [x] `_index.md` con la fila del CR-082
- [x] Gates verdes (`cargo test` server: 86+… EXIT=0; app: `testDebugUnitTest/ktlint/detekt/assembleDebug` BUILD SUCCESSFUL) y APK reinstalado
- [ ] Logs temporales del diagnóstico (logcat tipo `MetricsSheet`/`UdpMediaClient` retirados cuando el duque dé el view-log — la captura `/tmp/media_phone2.log` queda activa)

## Changelog

| Versión | Fecha | Autor | Cambio |
|---|---|---|---|
| 0.1.0 | 2026-11-05 | Buffy | abierto: corte del stream a los ~10 s sin recuperación (watchdog §8.5 one-shot) y drag robado del slider (CR-079 v7, intercept sin onTouchEvent) |
| 0.2.0 | 2026-11-05 | Buffy | aprobado por el owner y aplicado: `MediaRecoveryPolicy` (WATCH_INTERVAL 2 000 ms, MEDIA_IDLE 5 000 ms, 2 re-JOINs con nonce nuevo y seq 0 antes de entregar Closed) + `onTouchEvent` del `MetricsSliderContainer`; TEST-0165 Passed (JVM); gates verdes (cargo test server EXIT=0; gradle suite app BUILD SUCCESSFUL); APK reinstalado |
