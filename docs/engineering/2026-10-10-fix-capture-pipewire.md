# Reporte: transmisión rota al sincronizar

## Segunda ronda — corte a ~5 s / ~10 s después de vincular (puerto 8420)

**Síntoma:** la app pasaba a `Linked` y a los ~5 s caía a `Failure` (logcat:
`16:48:33.540 Linked → 16:48:38.570 Failure`); el socket quedaba `CLOSE-WAIT`
en el server.

**Causa raíz — SO_RCVTIMEO sobrevive al handshake en AMBOS extremos:**

- **App (`TcpPairingClient`):** el lectord e líneas corría con
  `soTimeout = 5 s` (E1 del handkshake). `disableReadTimeout()` se llamaba
  al entrar a `Linked`, pero DESDE LA CORRUTINA DE UI — llegaba después de
  que ya la U4 tomó la decisión: el hilo lector estaba bloqueado en
  `readLine()` con el timeout armado, y a los 5 s recibía
  `SocketTimeoutException` → `break` → `null` → `Failure`.
- **Server:** el handshake (session.rs `read_line_with_timeout`, STORE=10 s
  del proof) lava SO_RCVTIMEO sobre el fd del stream, que es el MISMO fd
  que el lector de sesión reutiliza con BufReader: al no haber tras tráfico
  periódico en U4, la lectura expiraba (10 s) y mataba la sesión (`grep
del fd del server: mismas señales de cierre

**Enderezo:** `java.net.Socket` no permite cambiar `soTimeout` mientras
otra operación de lectura EN EL MISMO hilo está bloqueada — pero en Android
`setSoTimeout` afecta solo a la lectura **siguiente**, así que el disabling
latiente lo haría on el primer timeout vencido). Fijamos el corte yamos:

- **App fix:** el hilo lector detecta él mismo el `pair_ack`, y sobre ese
  socket setea `soTimeout = 0` ANTES de la siguiente lectura — y si igual
  recibe un `SocketTimeoutException`, lo interpreta como señal de
  desincronización (en vez de matar la sesión), resetea `soTimeout = 0`
  y sigue — la única señal de fin queda EOF (`line == null`). El
  `disableReadTimeout()` de la máquina queda como método best-effort
  (paridad con SPEC-002), pero ya no es el camino mágico permanente.
- **Server fix:** el lector de sesión tras el handshake hacer
  `set_read_timeout(None)` antes del primer `read_line` — el EOF y el
  write-fallo del emisor son las únicas señales de fin (§8 versión
  server, no hay tráfico periódico en U4).

**Por qué la U4 funciona:** en v1 NINGUNA parte del SPEC pide keepalive
periódico (SPEC-002 §8): el par (TCP) healthy queda establecido por la
existencia de datos del stream, y la redundancia del video/inputs le quitan
cualquier espacio de timeout. El corte era SOLO la corrección automática
del timeout de handshake que NO se desactivó al pasar a sesión.

**Puertos verificados en el banco:** 8420 (`0.0.0.0`, sesión/pairing) y
8421 (IPC SDK, localhost) correctos, owned por el mismo proceso; una
conexión NC con token inválido responde `E3` como corresponde y el server
cierra la conexión limpia (no es un bug del puerto).

**Verificación:**
- Server: `cargo check`, `cargo clippy --workspace` (0 warnings), `cargo
test --workspace` — 126+ tests OK; `cargo build -p twinpad-server`;
- Android: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:installDebug` — OK,
instalado en el dispositivo G665XOFYFMNBPJ6D.
- El server se dimotó (pid 21375 kill) y el binario nuevo está listo en
  `target/debug/twinpad-server`; si el pid sigue activo en tu cable
  simplemente ran `target/debug/twinpad-server 8420` — no hago push de la session.

## Tercera ronda — "conectando" eterno, luego video a ~5 fps

1. **"Conectando" para siempre:** `DesktopGrabber::new()` se ejecutaba EN el
hilo de sesión (main.rs), y en Wayland el portal ScreenCast bloquea ese hilo
(diálogo de permiso) — el lector de sesión ni siquiera arrancaba, los
input_report quedaban sin drenar (135 bytes en la cola del socket) y la app
colgaba en "conectando". **Fix:** el grabber se crea DENTRO del hilo de
video, que corre en paralelo; el lector arranca de inmediato.

2. **Video a ~5 fps:** medido con `server/examples/bench_frame.rs`:
- build debug: **293 ms/frame → máx 3.4 fps**
- build release: **22 ms/frame → máx 44.7 fps**

El server estaba armado debug (`target/debug/twinpad-server`). **Fix:**
`cargo build --release` y correr `target/release/twinpad-server`. El example
`bench_frame` queda como herramienta de medición.

---

## Diagnóstico

**Síntoma anterior (1 fps):** el round-trip DBus de `xcap::capture_image` (~700 ms/frame en Wayland) limitaba el stream. Ya estaba diagnosticado en los comentarios del repo (commit `d1f0cde` separó hilo de captura).

**Síntoma actual (stream se corta):** los cambios en curso reemplazan `xcap` por captura continua vía portal ScreenCast (Wayland) + PipeWire:

1. `server/capture/src/wayland_pipewire.rs` (nuevo) pedía la sesión del portal **correctamente**…
2. …pero el `pipewire_worker` era un **stub**: hacía `drop(tx)` y entraba en un `while !running { sleep(100ms) }`, **jamás conectaba PipeWire**. Resultado: `latest_frame()` devolvía `None` en el primer uso → `frame()` → `GrabError::Backend` → `run_desktop_stream_dynamic`/`capture_loop` detenía el stream (§7 paso 5: "la captura desapareció") → **corte al sincronizar**.

Ademas había una línea **corrupta** en `select_sources` (la llamada `portal.call_method("SelectSources"...)` estaba pegada al comentario de `cursor_mode`; parte del código quedó fuera del flujo ejecutable).

## Fix

`server/capture/src/wayland_pipewire.rs` (reescrito, 577 líneas):

- **Worker PipeWire real** en hilo dedicado: `pw_init` → `MainLoopRc` en ese hilo → `ContextRc::new(&loop)` → **`connect_fd_rc(fd_del_portal)`** (la clave que xcap no hacía: dentro de esa conexión el `node_id` del portal existe) → `StreamBox` con `MEDIA_TYPE=Video, CATEGORY=Capture, ROLE=Screen`.
- **Negociación amplia** (`enum_format` POD): `BGRx|RGBx|xRGB|RGBA` (4 B/px, canal fijo); el compositor elige formato/tamaño/framerate nativos (en KWin: BGRx a fps del panel). 3 tests cubren el POD y el contrato del frame con `rgb8_scaled`.
- **Callbacks**:
  - `state_changed` → log de diagnóstico.
  - `param_changed` → guarda `(w,h)` negociado.
  - `process` → `dequeue_buffer`, valida tamaño (`w*h*4`), `try_send` a cola cap-1: si el lector se atrasa se descarta el frame (R3 SPEC-003: drenar al más reciente), **nunca se bloquea el thread del compositor**.
- **Parada limpia**: timer propio del loop (100 ms) llama `quit()` (thread-safe) cuando `running` cae en `Drop` del grabber → sin `join()` colgado ni sesión huérfana del portal (se cierra con `Close`).
- **Errores culle el canal**: si el lector cierra (Drop), los `try_send` fallan → logs; `latest_frame()` devolvía `None` → la capa superior degrada a screenshot (R3 del SPEC-003), no se corta.

`select_sources` quedó corregido (llamada bien separada del comentario).

## Verificación

- `cargo check --workspace` — sin errores ni warnings.
- `cargo clippy -p twinpad-capture -- -D warnings` — limpio (workspace deniega `unwrap_used`).
- `cargo test --workspace` — **126 tests OK** (incl. 7 de `twinpad-capture`: 4 de `rgb8_scaled` + 3 nuevos del módulo PipeWire).
- `cargo build --workspace` + `cargo fmt -p twinpad-capture` — OK.
- `./gradlew :app:compileDebugKotlin` y `:app:testDebugUnitTest` (lado Android) — OK.

## Notas / limitaciones

- La captura real requiere un compositor Wayland con portal ScreenCast (entorno del agente es headless — PIPEWIRE runtime no presente): la ruta con el stream real **no se ejecutó en el banco del agente**; quedó verificada por compilación, tests de contrato y la guía de la API (`pipewire-rs` docs + examples). Faltan los logs `capture:` (`stream estado → Streaming`, "formato negociado") en tu banco con KWin: sacalos con `cargo run -p twinpad-server` y revisá stdout.
- El lado Android (service foreground, keep-screen-on, `HashMap` de stream) quedó como estaba: los tests pasan y el flujo no corta la sesión por el evento de background (`onLinked` ignora `AppBackgrounded`).
