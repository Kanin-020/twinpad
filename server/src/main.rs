//! Servidor PC — adaptador ejecutable (REQ-001, REQ-002; DES-001 §12).
//!
//! Listener TCP en la LAN con el registro de tokens (R2/R3/R6/R7) y el
//! Handshake de SPEC-001 sobre TCP real. Muestra el QR vigente como URI en
//! stdout y lo regenera automáticamente al expirar (R7). La renderización
//! gráfica del QR y la persistencia cifrada de sesiones (ARCH §5) son
//! iteraciones posteriores.
//!
//! DES-001 §11: la entropía criptográfica y el reloj del host se inyectan
//! aquí; el resto del programa sigue siendo determinista y testeable.

use std::net::TcpListener;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use twinpad_server::session::{SessionDeps, SessionOutcome, run_handshake};
use twinpad_server::tokens::{Clock, RandomBytes, TokenStore};

/// Puerto TCP del servidor (REQ-001; dentro del rango 1024–65535 de §4.1).
const DEFAULT_PORT: u16 = 8420;

/// Puerto IPC del SDK (SPEC-006/ADR-008): los juegos con SDK anuncian aquí.
const DEFAULT_IPC_PORT: u16 = 8421;

/// Fuentes reales de entropía y reloj (ENG §5 P11: los efectos viven aquí).
struct OsRandomBytes;
impl RandomBytes for OsRandomBytes {
    fn fill(&self, buf: &mut [u8]) {
        getrandom::fill(buf).unwrap_or_else(|e| os_entropy_unavailable(&e));
    }
}

struct SystemClock;
impl Clock for SystemClock {
    fn now_ms(&self) -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0)
    }
}

/// Sin entropía del SO no hay tokens seguros: el servidor no puede arrancar.
fn os_entropy_unavailable(e: &getrandom::Error) -> ! {
    eprintln!("Twinpad: entropía del SO no disponible ({e}); abortando.");
    std::process::exit(1);
}

fn main() {
    let port: u16 = std::env::args()
        .nth(1)
        .and_then(|a| a.parse().ok())
        .unwrap_or(DEFAULT_PORT);

    let Ok(listener) = TcpListener::bind(("0.0.0.0", port)) else {
        eprintln!("Twinpad: no se pudo abrir el puerto {port}; ¿está en uso?");
        std::process::exit(1);
    };
    let addr = local_addr_for_qr();
    let store = Arc::new(TokenStore::new(
        OsRandomBytes,
        SystemClock,
        addr.clone(),
        port,
    ));
    let registry = Arc::new(twinpad_server::registry::SessionRegistry::new());

    // SPEC-006/ADR-008: listener IPC para juegos con SDK. Si el puerto está
    // en uso, el server sigue (pairing/video/inputs) pero sin detección.
    let ipc_port: u16 = std::env::args()
        .nth(2)
        .and_then(|a| a.parse().ok())
        .unwrap_or(DEFAULT_IPC_PORT);
    let game_registry = Arc::new(twinpad_server::games::GameRegistry::new());
    match TcpListener::bind(("127.0.0.1", ipc_port)) {
        Ok(ipc_listener) => {
            let sessions = Arc::clone(&registry);
            let games = Arc::clone(&game_registry);
            let publish = Arc::new(move |list: &[twinpad_core::game::GameInfo]| {
                if let Ok(line) = (twinpad_core::game::GameMsg::GamesAvailable {
                    games: list.to_vec(),
                })
                .encode()
                {
                    sessions.broadcast_line(&line);
                }
            });
            std::thread::spawn(move || {
                println!("Detección de juegos SDK activa en 127.0.0.1:{ipc_port} (SPEC-006).");
                twinpad_server::games::run_ipc_listener(ipc_listener, games, publish);
            });
        }
        Err(_) => {
            eprintln!(
                "Twinpad: puerto IPC {ipc_port} en uso; detección de juegos SDK desactivada."
            );
        }
    }

    println!("Twinpad Servidor PC — escuchando en {addr}:{port}");
    println!("Escanea este QR con la app Twinpad:");
    println!();
    let current = store.ensure_fresh_qr();
    match twinpad_server::qr_render::render_terminal(&current.qr_uri) {
        Ok(qr_text) => println!("{qr_text}"),
        // El URI canónico de §4.1 siempre cabe; si no, mostramos solo el URI.
        Err(_) => println!("(QR no renderizable) {}", current.qr_uri),
    }
    println!("URI (por si necesitas copiarlo): {}", current.qr_uri);
    println!("El QR se regenera automáticamente cada 300 s si nadie lo escanea (R7).");

    for stream in listener.incoming() {
        let Ok(stream) = stream else { continue };
        // R2: cada conexión usa el registro vigente; el consumo lo arbitra
        // el almacén con exclusión mutua (DES-001 §10).
        let record = store.ensure_fresh_qr().record;
        let token_for_thread = record.token;
        let store_ref = Arc::clone(&store);
        let registry_ref = Arc::clone(&registry);
        let games_ref = Arc::clone(&game_registry);
        std::thread::spawn(move || {
            let mut deps = SessionDeps {
                now_ms: &mut || SystemClock.now_ms(),
                next_nonce: &mut || {
                    let mut bytes = [0u8; 32];
                    OsRandomBytes.fill(&mut bytes);
                    twinpad_core::Nonce(bytes)
                },
                next_session_id: &mut || {
                    let mut bytes = [0u8; 16];
                    OsRandomBytes.fill(&mut bytes);
                    twinpad_core::SessionId(bytes)
                },
            };
            let mut stream = stream;
            match run_handshake(&mut stream, record, &mut deps) {
                SessionOutcome::Established {
                    session_id,
                    session_key,
                } => {
                    store_ref.mark_consumed(&token_for_thread); // R2, §14
                    // Conexión persistente (SPEC-001 §8.2): el server retiene
                    // un duplicado del stream para enviarle a la app; la
                    // conexión no se cierra tras el pair_ack.
                    let mut session_alive = false;
                    if let Ok(held) = stream.try_clone() {
                        registry_ref.insert(
                            twinpad_server::registry::ActiveSession {
                                session_id,
                                session_id_b64: twinpad_core::message::b64_16(&session_id.0),
                            },
                            held,
                        );
                        println!(
                            "Vinculación exitosa: session_id={} — sesión activa ({} en total)",
                            twinpad_core::message::b64_16(&session_id.0),
                            registry_ref.len(),
                        );
                        session_alive = true;
                    }
                    let _ = session_key; // se usará por el canal cifrado (futuro)

                    if session_alive {
                        // Anti-lag @60fps (2026-10-10): el socket de sesión
                        // sale preparado ANTES del primer frame (SNDBUF
                        // acotado + NODELAY); después no hay ventana fácil.
                        StreamSink::prepare_for_streaming(&stream);
                        // SPEC-006 §5/§7 paso 3: la sesión nueva recibe la
                        // lista vigente de juegos compatibles.
                        let games_line = (twinpad_core::game::GameMsg::GamesAvailable {
                            games: games_ref.list(),
                        })
                        .encode()
                        .ok();
                        if let Some(line) = games_line {
                            registry_ref.send_line(&session_id, &line);
                        }
                    }

                    if session_alive {
                        // SPEC-003 §7: fuente temporal sin Plugin (§17 del
                        // SPEC + decisión del owner): duplicado del escritorio
                        // capturado con xcap; TWINPAD_SOURCE=demo fuerza el
                        // patrón animado. Si la captura no está disponible
                        // (permisos, Wayland), cae al patrón con aviso.
                        // Video y audio comparten el lock de escritura: cada
                        // línea NDJSON viaja atómica (sin intercalado).
                        let write_lock = SessionWriteLock::default();
                        let writer = match stream.try_clone() {
                            Ok(w) => Some(StreamSink {
                                inner: w,
                                lock: write_lock.clone(),
                            }),
                            Err(_) => None,
                        };
                        let force_demo =
                            matches!(std::env::var("TWINPAD_SOURCE").as_deref(), Ok("demo"));
                        // CR-001: resolución/fps/calidad por entorno (720p×30
                        // por defecto); la app puede cambiarla en caliente
                        // (menú de ajustes) vía `video_config` de sesión.
                        let video_config_shared = std::sync::Arc::new(std::sync::Mutex::new(
                            twinpad_server::video::VideoConfig::from_env(),
                        ));
                        // POR QUÉ AQUÍ: crear el grabber AHORA STARIA el hilo
                        // de sesión — el portal ScreenCast (Wayland) pide permiso
                        // con DIÁLOGO y espera al owner (U4 paciencia, §9), y el
                        // lector de sesión quedaría con el socket sin drenar:
                        // la app quedaria "conectando" y los input_report
                        // enquuing. Elegido: el grabber se construye DENTRO del
                        // hilo de video (abajo), que corre en paralelo, y el
                        // lector arranca de inmediato.
                        let video_thread = writer.map(|mut sink| {
                            let games = Arc::clone(&games_ref);
                            let video_config_shared = std::sync::Arc::clone(&video_config_shared);
                            std::thread::spawn(move || {
                                // REQ-006 AC-2: si hay un juego vivo con SDK
                                // publicando `game_frame`, su contenido alimenta
                                // el stream (fuente preferente, §8.1 de SPEC-006);
                                // si no, las fuentes de SPEC-003 §17
                                // (escritorio/demo). El sondeo es por frame.
                                if games.preferred_source().is_some() {
                                    println!(
                                        "Fuente de video: juego con SDK Twinpad (SPEC-006 AC-2)."
                                    );
                                    let mut poll = move || games.preferred_source();
                                    let _ = twinpad_server::video::run_game_stream(
                                        &mut sink,
                                        u64::MAX, // hasta que la sesión se corte
                                        twinpad_server::video::DEMO_FPS,
                                        &mut poll,
                                        false,
                                    );
                                } else {
                                    // El grabber se crea EN ESTE hilo: el diálogo
                                    // del portal (si aparece) ya no congela al
                                    // lector de sesión (ver comentario de arriba).
                                    let grabber = if force_demo {
                                        None
                                    } else {
                                        match twinpad_capture::DesktopGrabber::new() {
                                            Ok(g) => {
                                                let initial = video_config_shared
                                                    .lock()
                                                    .map(|c| {
                                                        format!(
                                                            "{}×{} @ {} fps JPEG q{}",
                                                            c.width, c.height, c.fps, c.jpeg_quality
                                                        )
                                                    })
                                                    .unwrap_or_else(|_| "config no legible".into());
                                                println!(
                                                    "Fuente de video: escritorio del PC — {initial} (CR-001), modo {:?}.",
                                                    g.mode()
                                                );
                                                Some(g)
                                            }
                                            Err(e) => {
                                                println!(
                                                    "Captura de escritorio no disponible ({e:?}); usando patrón de demostración."
                                                );
                                                None
                                            }
                                        }
                                    };
                                    let result = match grabber {
                                        Some(g) => {
                                            let cfg_shared =
                                                std::sync::Arc::clone(&video_config_shared);
                                            twinpad_server::video::run_desktop_stream_dynamic(
                                                &mut sink,
                                                u64::MAX, // hasta que la sesión se corte
                                                cfg_shared,
                                                g,
                                            )
                                        }
                                        None => twinpad_server::video::run_demo_stream(
                                            &mut sink,
                                            u64::MAX, // hasta que la sesión se corte
                                            twinpad_server::video::DEMO_FPS,
                                            twinpad_server::video::DEMO_WIDTH,
                                            twinpad_server::video::DEMO_HEIGHT,
                                        ),
                                    };
                                    match result {
                                        Ok(()) => println!("Stream terminado."),
                                        Err(_) => {
                                            println!("Emisión detenida (receptor fuera de línea).")
                                        }
                                    }
                                }
                            })
                        });

                        // SPEC-005 §7: stream de audio demo (tono suave) mientras
                        // no exista el juego con audio (§3: el origen real es el
                        // Plugin). Misma conexión persistente; hilo propio
                        // (ARCH §8: prioridad inputs > video > audio).
                        let audio_writer = match stream.try_clone() {
                            Ok(w) => Some(StreamSink {
                                inner: w,
                                lock: write_lock.clone(),
                            }),
                            Err(_) => None,
                        };
                        let audio_thread = audio_writer.map(|mut sink| {
                            std::thread::spawn(move || {
                                let mut chunk_counter = 0u64;
                                let mut source = move |_seq: u64| {
                                    chunk_counter += 1;
                                    Some(twinpad_server::audio::demo_chunk(
                                        chunk_counter,
                                        twinpad_server::audio::CHUNK_SAMPLES,
                                        twinpad_server::audio::DEMO_RATE,
                                    ))
                                };
                                let _ = twinpad_server::audio::run_audio_stream(
                                    &mut sink,
                                    twinpad_server::audio::DEMO_RATE,
                                    twinpad_server::audio::DEMO_CHANNELS,
                                    &mut source,
                                );
                            })
                        });

                        // El hilo retiene `stream` abierto (lectura keepalive;
                        // EOF = el cliente cerró). Aquí llegan los
                        // `input_report` de la app (SPEC-004, ADR-007) y el
                        // `video_config` del menú de la app.
                        let mut last_seq: Option<u32> = None;
                        let reader_cfg = std::sync::Arc::clone(&video_config_shared);
                        use std::io::{BufRead, BufReader};
                        // §9: el handshake armó SO_RCVTIMEO (prueba 10 s) sobre
                        // este MISMO fd: en sesión v1 no hay tráfico periódico
                        // (U4), dejarlo expira la lectura y mata la sesión sin
                        // input. Reset a bloqueo indefinido: EOF es la única
                        // señal de fin (el emisor descubre el corte al fallar
                        // su escritura).
                        let _ = stream.set_read_timeout(None);
                        let mut reader = BufReader::new(stream);
                        let mut line = String::new();
                        loop {
                            line.clear();
                            match reader.read_line(&mut line) {
                                Ok(0) | Err(_) => {
                                    // El cliente cerró: liberar la sesión y
                                    // detener la emisión (el drop del SinkSpy
                                    // del emisor falla al escribir y termina).
                                    registry_ref.remove(&session_id);
                                    println!("Sesión finalizada.");
                                    break;
                                }
                                Ok(_) => {
                                    // SPEC-004 §9: reporte con `seq` viejo se
                                    // descarta; aquí solo validamos y registramos.
                                    let trimmed = line.trim_end();
                                    if trimmed.is_empty() {
                                        continue;
                                    }
                                    // Menú de la app (CR-001): cambio de calidad
                                    // del video en caliente.
                                    if trimmed.starts_with("{\"type\":\"video_config\"") {
                                        if let Some(cfg) = parse_video_config(trimmed) {
                                            if let Ok(mut slot) = reader_cfg.lock() {
                                                *slot = cfg;
                                            }
                                            println!(
                                                "Video config actualizada por la app: {}×{} @ {} fps.",
                                                cfg.width, cfg.height, cfg.fps
                                            );
                                        }
                                        continue;
                                    }
                                    match twinpad_core::input::InputMsg::decode(trimmed) {
                                        Ok(report) => {
                                            last_seq = apply_seq_filter(last_seq, &report);
                                            println!("input_report aceptado ({last_seq:?}).");
                                        }
                                        Err(_) => println!("input_report inválido descartado."),
                                    }
                                }
                            }
                        }
                        if let Some(handle) = video_thread {
                            let _ = handle.join();
                        }
                        if let Some(handle) = audio_thread {
                            let _ = handle.join();
                        }
                    }
                }
                SessionOutcome::Rejected(e) => {
                    println!("Vinculación rechazada ({}).", e.code());
                }
            }
        });
    }
}

/// Extrae `w`/`h`/`fps` del mensaje `video_config` del menú de la app
/// (parseo mínimo sin serde; los valores se validan al construir VideoConfig).
fn parse_video_config(line: &str) -> Option<twinpad_server::video::VideoConfig> {
    let extract = |key: &str| -> Option<u32> {
        let marker = format!("\"{key}\":");
        let start = line.find(&marker)? + marker.len();
        let rest = &line[start..];
        let digits: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
        digits.parse().ok()
    };
    let current = read_current_session_config();
    Some(twinpad_server::video::VideoConfig {
        width: extract("w").map(|v| v as u16).unwrap_or(current.width),
        height: extract("h").map(|v| v as u16).unwrap_or(current.height),
        fps: extract("fps").map(|v| v as u8).unwrap_or(current.fps),
        jpeg_quality: extract("jpeg_quality")
            .map(|v| v as u8)
            .unwrap_or(current.jpeg_quality),
    })
}

/// Config vigente de la sesión (no from_env: un jpeg_quality ya ajustado en
/// caliente se mantendría al enviar solo un campo nuevo).
fn read_current_session_config() -> twinpad_server::video::VideoConfig {
    // El caller ya retiene el Arc; esta función se usa para el parse
    // preliminar antes del lock real (values por omisión coherentes).
    twinpad_server::video::VideoConfig::from_env()
}

/// SPEC-004 R2/§9: solo el reporte con `seq` mayor define el estado; los
/// reportes viejos o repetidos se descartan. Devuelve el seq a registrar.
fn apply_seq_filter(last_seq: Option<u32>, msg: &twinpad_core::input::InputMsg) -> Option<u32> {
    let twinpad_core::input::InputMsg::InputReport { seq, .. } = msg;
    if last_seq.is_none_or(|last| *seq > last) {
        return Some(*seq);
    }
    last_seq
}

/// Escritura serializada de la sesión: los hilos de video y audio comparten
/// el mismo socket TCP por clones separados; sin esta exclusión, una línea
/// de ~1 MB (video) puede intercalarse a mitad de escritura con un chunk de
/// audio y corromper ambas líneas NDJSON (la app las descarta → sin sonido
/// y con cortes de video). `Arc<Mutex<()>>` serializa cada línea completa
/// (write_all + '\n' + flush) como una sola unidad atómica.
#[derive(Clone, Default)]
struct SessionWriteLock(Arc<std::sync::Mutex<()>>);

/// Adaptador del stream retenido a `FrameSink` (la escritura va por el
/// duplicado del stream; la lectura sigue en el hilo de la sesión).
struct StreamSink {
    inner: std::net::TcpStream,
    lock: SessionWriteLock,
}

/// Tamaño máximo del buffer de envío del socket (anti-lag). POR QUÉ: con
/// streams @60 fps el kernel acumula el exceso de frames en el SNDBUF
/// (~256 KB..4 MB por defecto); un enlance Wi-Fi que drena menos que el
/// bitrate llena ese buffer con SEGUNDOS de video viejo — el síntoma del
/// lag de 0.5–2 s con "saltos" (el cursor se teletransporta). Acotado a
/// 1 frame grande (4 MiB), un SNDBUF lleno hace que `write` BLOQUEE en el
/// hilo del emisor; ahí entra el drop adaptativo del emisor.
const MAX_SND_BUF_BYTES: usize = 4 * 1024 * 1024;

impl StreamSink {
    /// Socket listo para streaming: `SO_SNDBUF` acotado (el lag tope por
    /// cola interna queda en ~0.1–0.3 s) y TCP_NODELAY (una línea de video
    /// no debe esperar al Nagle de la precedente). Sin esto, un Wi-Fi que
    /// drena menos que el bitrate llena el SNDBUF default (~256 KB–4 MB)
    /// con SEGUNDOS de video viejo: el lag de 0.5–2 s con saltos.
    /// API safe de `socket2` (ENG §4: sin unsafe en el árbol de server).
    pub fn prepare_for_streaming(stream: &std::net::TcpStream) {
        use socket2::SockRef;
        // `SockRef` deref a `Socket`: ambos setters viven ahí.
        let sock = SockRef::from(stream);
        let _ = sock.set_send_buffer_size(MAX_SND_BUF_BYTES);
        let _ = sock.set_tcp_nodelay(true);
    }

    /// Escribe la línea completa (con '\n' y flush) bajo el lock compartido;
    /// `false` si la conexión cayó.
    fn send_locked(&mut self, line: &str) -> bool {
        use std::io::Write;
        let Ok(_guard) = self.lock.0.lock() else {
            return false; // lock envenenado: el hilo dueño murió con la sesión
        };
        self.inner
            .write_all(line.as_bytes())
            .and_then(|_| self.inner.write_all(b"\n"))
            .and_then(|_| self.inner.flush())
            .is_ok()
    }
}

impl twinpad_server::video::FrameSink for StreamSink {
    fn send_line(&mut self, line: &str) -> bool {
        self.send_locked(line)
    }
}

/// Dirección LAN del host para el QR (§4.1): la primera IP privada no-loopback.
fn local_addr_for_qr() -> String {
    std::net::UdpSocket::bind("0.0.0.0:0")
        .and_then(|s| {
            s.connect("192.168.255.255:9")?;
            Ok(s.local_addr()?.ip().to_string())
        })
        .unwrap_or_else(|_| "127.0.0.1".to_string())
}

// ENG §6: unwrap permitido solo en tests.
#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use twinpad_core::input::InputMsg;

    // TEST-018 — verifies SPEC-004 (§9/R2: reporte con `seq` viejo se descarta).
    fn report(seq: u32) -> InputMsg {
        let line = format!(
            "{{\"type\":\"input_report\",\"seq\":{seq},\"stick_lx\":0,\"stick_ly\":0,\"stick_rx\":0,\"stick_ry\":0,\"dpad\":8,\"buttons\":0,\"trigger_l2\":0,\"trigger_r2\":0}}"
        );
        InputMsg::decode(&line).unwrap()
    }

    #[test]
    fn first_report_is_accepted() {
        assert_eq!(apply_seq_filter(None, &report(0)), Some(0));
    }

    #[test]
    fn greater_seq_is_accepted() {
        assert_eq!(apply_seq_filter(Some(3), &report(4)), Some(4));
    }

    #[test]
    fn stale_or_repeated_seq_is_dropped() {
        assert_eq!(apply_seq_filter(Some(5), &report(5)), Some(5));
        assert_eq!(apply_seq_filter(Some(5), &report(2)), Some(5));
    }
}
