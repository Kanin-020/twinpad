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
                        // por defecto; 1080p×60: TWINPAD_WIDTH=1920
                        // TWINPAD_HEIGHT=1080 TWINPAD_FPS=60).
                        let video_config = twinpad_server::video::VideoConfig::from_env();
                        let grabber = if force_demo {
                            None
                        } else {
                            match twinpad_capture::DesktopGrabber::new() {
                                Ok(g) => {
                                    println!(
                                        "Fuente de video: escritorio del PC — {}×{} @ {} fps JPEG q{} (CR-001).",
                                        video_config.width,
                                        video_config.height,
                                        video_config.fps,
                                        video_config.jpeg_quality
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
                        let video_thread = writer.map(|mut sink| {
                            let games = Arc::clone(&games_ref);
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
                                    let result = match &grabber {
                                        Some(g) => twinpad_server::video::run_desktop_stream(
                                            &mut sink,
                                            u64::MAX, // hasta que la sesión se corte
                                            video_config.fps,
                                            video_config,
                                            g,
                                        ),
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
                        // `input_report` de la app (SPEC-004, ADR-007).
                        let mut last_seq: Option<u32> = None;
                        use std::io::{BufRead, BufReader};
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

impl StreamSink {
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
