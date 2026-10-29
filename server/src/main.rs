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
/// IPC PCM dedicado del SDK (CR-003/SPEC-006).
const DEFAULT_AUDIO_IPC_PORT: u16 = 8422;
/// Puerto local para que Cemu consulte el QR vigente (CR-007).
const DEFAULT_QR_IPC_PORT: u16 = 8423;
const QR_IPC_RESPONSE_LIMIT: usize = 16 * 1024;

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
    let game_registry = Arc::new(twinpad_server::games::GameRegistry::new());
    match bind_qr_ipc_listener(DEFAULT_QR_IPC_PORT) {
        Ok(listener) => {
            let qr_store = Arc::clone(&store);
            let qr_games = Arc::clone(&game_registry);
            std::thread::spawn(move || run_qr_ipc_listener(listener, qr_store, qr_games));
        }
        Err(error) => eprintln!("Twinpad: interfaz local del QR no disponible: {error}"),
    }
    let registry = Arc::new(twinpad_server::registry::SessionRegistry::new());
    let media_hub = Arc::new(twinpad_server::media::MediaHub::default());
    let input_owner = Arc::new(std::sync::Mutex::new(None::<twinpad_core::SessionId>));

    // SPEC-006/ADR-008: listener IPC para juegos con SDK. Si el puerto está
    // en uso, el server sigue (pairing/video/inputs) pero sin detección.
    let ipc_port: u16 = std::env::args()
        .nth(2)
        .and_then(|a| a.parse().ok())
        .unwrap_or(DEFAULT_IPC_PORT);
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
            let media_hub_for_ipc = Arc::clone(&media_hub);
            std::thread::spawn(move || {
                println!("Detección de juegos SDK activa en 127.0.0.1:{ipc_port} (SPEC-006).");
                twinpad_server::games::run_ipc_listener_with_media(
                    ipc_listener,
                    games,
                    publish,
                    Some(media_hub_for_ipc),
                );
            });
        }
        Err(_) => {
            eprintln!(
                "Twinpad: puerto IPC {ipc_port} en uso; detección de juegos SDK desactivada."
            );
        }
    }

    match TcpListener::bind(("127.0.0.1", DEFAULT_AUDIO_IPC_PORT)) {
        Ok(audio_listener) => {
            let games = Arc::clone(&game_registry);
            std::thread::spawn(move || {
                println!("IPC de audio SDK activo en 127.0.0.1:{DEFAULT_AUDIO_IPC_PORT} (CR-003).");
                twinpad_server::games::run_audio_ipc_listener(audio_listener, games);
            });
        }
        Err(_) => eprintln!(
            "Twinpad: puerto IPC de audio {DEFAULT_AUDIO_IPC_PORT} en uso; PCM SDK desactivado."
        ),
    }

    match TcpListener::bind(("0.0.0.0", twinpad_server::media::MEDIA_PORT)) {
        Ok(media_listener) => {
            let media_registry = Arc::clone(&registry);
            let session_registry = Arc::clone(&registry);
            let keys: Arc<twinpad_server::media::MediaCredentialLookup> =
                Arc::new(move |session_id| media_registry.media_credential(session_id));
            let media_registry_for_lookup = Arc::clone(&registry);
            let hub = Arc::clone(&media_hub);
            let selected: Arc<twinpad_server::media::SelectedGameLookup> =
                Arc::new(move |session_id| session_registry.selected_game(session_id));
            let media_game: Arc<twinpad_server::media::MediaGameLookup> =
                Arc::new(move |session_id| media_registry_for_lookup.media_game(session_id));
            std::thread::spawn(move || {
                println!(
                    "Canal media autenticado activo en 0.0.0.0:{} (SPEC-003).",
                    twinpad_server::media::MEDIA_PORT
                );
                twinpad_server::media::run_media_listener(
                    media_listener,
                    keys,
                    hub,
                    selected,
                    media_game,
                );
            });
        }
        Err(error) => {
            eprintln!(
                "Twinpad: no se pudo abrir puerto media {}: {error}",
                twinpad_server::media::MEDIA_PORT
            );
            std::process::exit(1);
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
        let input_owner_ref = Arc::clone(&input_owner);
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
                    registry_ref.insert_active_key(session_id, session_key);

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
                        // Video y audio comparten el lock de escritura: cada
                        // línea NDJSON viaja atómica (sin intercalado). Sin Plugin
                        // seleccionado no se emite ningún medio.
                        let write_lock = SessionWriteLock::default();
                        let session_writer = Arc::new(SessionWriter::default());
                        let selected_game = Arc::new(std::sync::Mutex::new(None::<String>));
                        let media_game = Arc::new(std::sync::Mutex::new(None::<String>));
                        registry_ref.set_selected_game(session_id, None);
                        let initial_video_config = twinpad_server::video::VideoConfig::from_env();

                        let session_input_snapshot =
                            Arc::new(std::sync::Mutex::new(None::<String>));
                        let pending_snapshot_for_selection = Arc::clone(&session_input_snapshot);
                        let selection_changed = Arc::new(std::sync::atomic::AtomicBool::new(false));
                        let session_running = Arc::new(std::sync::atomic::AtomicBool::new(true));
                        let writer = match stream.try_clone() {
                            Ok(w) => Some(StreamSink {
                                inner: w,
                                lock: write_lock.clone(),
                                priority: Arc::clone(&session_writer),
                                is_video: true,
                                selected_game: Arc::clone(&selected_game),
                                media_game: Some(Arc::clone(&media_game)),
                                registry: Some(Arc::clone(&registry_ref)),
                                session_id: Some(session_id),
                            }),
                            Err(_) => None,
                        };
                        // CR-001: resolución/fps/calidad por entorno (720p×30
                        // por defecto); la app puede cambiarla en caliente
                        // (menú de ajustes) vía `video_config` de sesión.
                        let video_config_shared =
                            std::sync::Arc::new(std::sync::Mutex::new(initial_video_config));
                        // POR QUÉ AQUÍ: crear el grabber AHORA STARIA el hilo
                        // de sesión — el portal ScreenCast (Wayland) pide permiso
                        // con DIÁLOGO y espera al owner (U4 paciencia, §9), y el
                        // lector de sesión quedaría con el socket sin drenar:
                        // la app quedaria "conectando" y los input_report
                        // enquuing. Elegido: el grabber se construye DENTRO del
                        // hilo de video (abajo), que corre en paralelo, y el
                        // lector arranca de inmediato.
                        let selected_game_for_video = Arc::clone(&selected_game);
                        let running_for_video = Arc::clone(&session_running);
                        let video_thread = writer.map(|mut sink| {
                            let games = Arc::clone(&games_ref);
                            let selected_game = selected_game_for_video;
                            let video_config_shared = Arc::clone(&video_config_shared);
                            std::thread::spawn(move || {
                                let config = Arc::clone(&video_config_shared);
                                let mut current_config = move || {
                                    config.lock().map(|config| *config).unwrap_or_else(|_| {
                                        twinpad_server::video::VideoConfig::from_env()
                                    })
                                };
                                let source_games = Arc::clone(&games);
                                let source_selection = Arc::clone(&selected_game);
                                let running = Arc::clone(&running_for_video);
                                let mut selected_source = move || {
                                    let game_id =
                                        source_selection.lock().ok().and_then(|slot| slot.clone());
                                    let Some(game_id) = game_id else {
                                        return (None, None);
                                    };
                                    if !source_games.has_live_game(&game_id) {
                                        return (None, None);
                                    }
                                    let frame = source_games.latest_frame(&game_id);
                                    (Some(game_id), frame)
                                };
                                let _ = twinpad_server::video::run_selected_game_stream(
                                    &mut sink,
                                    &running,
                                    &mut current_config,
                                    &mut selected_source,
                                );
                            })
                        });

                        // SPEC-005 §3/§5: no audio is sent until the SDK
                        // provides a real PCM source. Never synthesize a tone
                        // when the game has no captured audio.
                        let audio_thread = spawn_game_audio_forwarding(
                            Arc::clone(&games_ref),
                            stream.try_clone().ok(),
                            session_writer,
                            write_lock.clone(),
                            Arc::clone(&selected_game),
                            Arc::clone(&session_running),
                        );

                        // El hilo retiene `stream` abierto (lectura keepalive;
                        // EOF = el cliente cerró). Aquí llegan los
                        // `input_report` de la app (SPEC-004, ADR-007) y el
                        // `video_config` del menú de la app.
                        let mut last_seq: Option<u32> = None;
                        let reader_cfg = std::sync::Arc::clone(&video_config_shared);
                        let session_input_snapshot_for_reader = Arc::clone(&session_input_snapshot);
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
                                    // detener los emisores antes de join.
                                    session_running
                                        .store(false, std::sync::atomic::Ordering::Release);
                                    registry_ref.remove(&session_id);
                                    release_input_owner(&input_owner_ref, session_id);
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
                                        let current = reader_cfg.lock().map(|slot| *slot).ok();
                                        if let Some(cfg) = current.and_then(|current| {
                                            parse_video_config(trimmed, current)
                                        }) {
                                            if let Ok(mut slot) = reader_cfg.lock() {
                                                *slot = cfg;
                                            }
                                            // CR-029: sin fps reenviado; resolución + bitrate (CR-030).
                                            if selected_game
                                                .lock()
                                                .ok()
                                                .and_then(|selected| selected.clone())
                                                .as_deref()
                                                == Some("cemu")
                                            {
                                                games_ref.send_cemu_video_config(
                                                    cfg.width,
                                                    cfg.height,
                                                    cfg.bitrate_kbps,
                                                );
                                            }
                                            println!(
                                                "Video config actualizada por la app: {}×{}, {} kbps (sin tope de fps).",
                                                cfg.width, cfg.height, cfg.bitrate_kbps
                                            );
                                        }
                                        continue;
                                    }
                                    // CR-034: cámara del teléfono hacia Cemu.
                                    if trimmed.starts_with("{\"type\":\"camera_frame\"") {
                                        if let Ok(msg) =
                                            twinpad_core::game::GameMsg::decode(trimmed)
                                        {
                                            if let Ok(line) = msg.encode() {
                                                games_ref.send_cemu_camera_frame(&line);
                                            }
                                        } else {
                                            println!("camera_frame inválido descartado.");
                                        }
                                        continue;
                                    }
                                    if let Ok(twinpad_core::game::GameMsg::GameSelect { game_id }) =
                                        twinpad_core::game::GameMsg::decode(trimmed)
                                    {
                                        let active =
                                            selected_game.lock().ok().and_then(|slot| slot.clone());
                                        if games_ref.has_live_game(&game_id) {
                                            if active.as_deref() != Some(game_id.as_str()) {
                                                if let Ok(mut selected) = selected_game.lock() {
                                                    *selected = Some(game_id.clone());
                                                }
                                                if let Ok(mut active_media_game) = media_game.lock()
                                                {
                                                    *active_media_game = None;
                                                }
                                                registry_ref.set_selected_game(
                                                    session_id,
                                                    Some(game_id.clone()),
                                                );
                                                registry_ref.set_media_game(session_id, None);

                                                if game_id == "cemu" {
                                                    if let Ok(config) = reader_cfg.lock() {
                                                        games_ref.send_cemu_video_config(
                                                            config.width,
                                                            config.height,
                                                            config.bitrate_kbps,
                                                        );
                                                    }
                                                }
                                                if let Some(snapshot) =
                                                    pending_snapshot_for_selection
                                                        .lock()
                                                        .ok()
                                                        .and_then(|slot| slot.clone())
                                                {
                                                    games_ref
                                                        .send_input_snapshot(&game_id, &snapshot);
                                                }
                                                selection_changed.store(
                                                    true,
                                                    std::sync::atomic::Ordering::Release,
                                                );
                                                let response =
                                                    twinpad_core::game::GameMsg::GameSelected {
                                                        game_id,
                                                    };
                                                if let Ok(line) = response.encode() {
                                                    registry_ref.send_line(&session_id, &line);
                                                }
                                            } else {
                                                let response =
                                                    twinpad_core::game::GameMsg::GameSelected {
                                                        game_id,
                                                    };
                                                if let Ok(line) = response.encode() {
                                                    registry_ref.send_line(&session_id, &line);
                                                }
                                            }
                                        } else if let Ok(line) =
                                            (twinpad_core::game::GameMsg::GamesAvailable {
                                                games: games_ref.list(),
                                            })
                                            .encode()
                                        {
                                            registry_ref.send_line(&session_id, &line);
                                        }
                                        continue;
                                    }
                                    match twinpad_core::input::InputMsg::decode(trimmed) {
                                        Ok(report) => {
                                            let accepted_seq = apply_seq_filter(last_seq, &report);
                                            if accepted_seq != last_seq {
                                                last_seq = accepted_seq;
                                                let is_owner =
                                                    claim_input_owner(&input_owner_ref, session_id);
                                                if is_owner {
                                                    if let Ok(line) = report.encode() {
                                                        if let Ok(mut snapshot) =
                                                            session_input_snapshot_for_reader.lock()
                                                        {
                                                            *snapshot = Some(line.clone());
                                                        }
                                                        let target = selected_game
                                                            .lock()
                                                            .ok()
                                                            .and_then(|selected| selected.clone());
                                                        games_ref.retain_and_send_input(
                                                            target.as_deref(),
                                                            &line,
                                                        );
                                                    }
                                                }
                                            }
                                        }
                                        Err(_) => println!("input_report inválido descartado."),
                                    }
                                }
                            }
                        }
                        session_running.store(false, std::sync::atomic::Ordering::Release);
                        release_input_owner(&input_owner_ref, session_id);
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

/// Extrae `w`/`h` (sin `fps`, CR-029) del mensaje `video_config` del menú de
/// la app (parseo mínimo sin serde; los valores se validan al construir VideoConfig).
fn parse_video_config(
    line: &str,
    current: twinpad_server::video::VideoConfig,
) -> Option<twinpad_server::video::VideoConfig> {
    let extract = |key: &str| -> Option<u32> {
        let marker = format!("\"{key}\":");
        let start = line.find(&marker)? + marker.len();
        let rest = &line[start..];
        let digits: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
        digits.parse().ok()
    };
    Some(twinpad_server::video::VideoConfig {
        width: extract("w")
            .map(|v| {
                v.clamp(
                    u32::from(twinpad_core::stream::MIN_DIM),
                    u32::from(twinpad_core::stream::MAX_WIDTH),
                ) as u16
            })
            .unwrap_or(current.width),
        height: extract("h")
            .map(|v| {
                v.clamp(
                    u32::from(twinpad_core::stream::MIN_DIM),
                    u32::from(twinpad_core::stream::MAX_HEIGHT),
                ) as u16
            })
            .unwrap_or(current.height),
        // CR-027: bitrate objetivo del encoder H.264 del Plugin (kbps).
        // CR-029: un `fps` heredado de apps antiguas se ignora.
        bitrate_kbps: extract("bitrate_kbps")
            .map(|v| v.clamp(1_000, 30_000))
            .unwrap_or(current.bitrate_kbps),
    })
}

/// Selecciona el primer dispositivo que envía input hasta que su sesión se desconecta.
fn claim_input_owner(
    owner: &std::sync::Mutex<Option<twinpad_core::SessionId>>,
    session: twinpad_core::SessionId,
) -> bool {
    let mut guard = owner
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    match *guard {
        Some(active) => active == session,
        None => {
            *guard = Some(session);
            true
        }
    }
}

/// Libera la exclusividad solo al desconectarse la sesión controladora.
fn release_input_owner(
    owner: &std::sync::Mutex<Option<twinpad_core::SessionId>>,
    session: twinpad_core::SessionId,
) {
    let mut guard = owner
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    if *guard == Some(session) {
        *guard = None;
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

#[derive(Default)]
struct SessionWriter {
    video_waiting: std::sync::atomic::AtomicBool,
    audio_queue: std::sync::Mutex<std::collections::VecDeque<String>>,
    video_sent: std::sync::Condvar,
    video_generation: std::sync::Mutex<u64>,
}

/// Cantidad máxima de mensajes PCM pendientes en una sesión.
const SESSION_AUDIO_QUEUE_CAPACITY: usize = 8;

/// Adaptador del stream retenido a `FrameSink` (la escritura va por el
/// duplicado del stream; la lectura sigue en el hilo de la sesión).
struct StreamSink {
    inner: std::net::TcpStream,
    lock: SessionWriteLock,
    priority: Arc<SessionWriter>,
    is_video: bool,
    selected_game: Arc<std::sync::Mutex<Option<String>>>,
    media_game: Option<Arc<std::sync::Mutex<Option<String>>>>,
    registry: Option<Arc<twinpad_server::registry::SessionRegistry>>,
    session_id: Option<twinpad_core::SessionId>,
}

fn spawn_game_audio_forwarding(
    games: Arc<twinpad_server::games::GameRegistry>,
    socket: Option<std::net::TcpStream>,
    writer: Arc<SessionWriter>,
    lock: SessionWriteLock,
    selected_game: Arc<std::sync::Mutex<Option<String>>>,
    session_running: Arc<std::sync::atomic::AtomicBool>,
) -> Option<std::thread::JoinHandle<()>> {
    let socket = socket?;
    Some(std::thread::spawn(move || {
        use twinpad_server::video::FrameSink;
        let mut sink = StreamSink {
            inner: socket,
            lock,
            priority: writer,
            is_video: false,
            selected_game,
            media_game: None,
            registry: None,
            session_id: None,
        };
        let mut current_game = None::<String>;
        let mut last_seen_selection = None::<String>;
        let mut seq = 0u64;
        while session_running.load(std::sync::atomic::Ordering::Acquire) {
            let selected = sink
                .selected_game
                .lock()
                .ok()
                .and_then(|selected| selected.clone())
                .filter(|game_id| games.has_audio_source(game_id));
            // CR-038: al cambiar la selección (vigente sin el filtro de audio),
            // re-entregar a la nueva sesión el estado de camera_request que
            // el Plugin ya hubiera enviado (p. ej. reconexión del teléfono).
            let selection_now = sink
                .selected_game
                .lock()
                .ok()
                .and_then(|selected| selected.clone());
            if selection_now != last_seen_selection {
                last_seen_selection = selection_now.clone();
                if let Some(game_id) = selection_now.as_deref() {
                    let _ = games.requeue_camera_request(game_id);
                }
            }
            if selected != current_game {
                if current_game.is_some() {
                    let stop = twinpad_core::audio::AudioMsg::AudioStop {
                        reason: twinpad_core::audio::AudioStopReason::PluginStopped,
                    };
                    if let Ok(line) = stop.encode() {
                        if !sink.send_line(&line) {
                            return;
                        }
                    }
                }
                current_game = selected.clone();
                seq = 0;
                if let Some(game_id) = selected.as_deref() {
                    if let Some((rate, channels)) = games.audio_params(game_id) {
                        let start = twinpad_core::audio::AudioMsg::AudioStart {
                            rate,
                            channels,
                            sample_fmt: twinpad_core::audio::SAMPLE_FMT_S16LE.into(),
                        };
                        if let Ok(line) = start.encode() {
                            if !sink.send_line(&line) {
                                return;
                            }
                        }
                    }
                }
            }
            // CR-038: demanda de cámara del Plugin vivo seleccionado, sin el
            // gate de fuente de audio: rige el stream de `camera_frame`.
            if let Some(game_id) = selection_now.as_deref() {
                if let Some(on) = games.pop_camera_request(game_id) {
                    let msg = twinpad_core::game::GameMsg::CameraRequest { on };
                    if let Ok(line) = msg.encode() {
                        if !sink.send_line(&line) {
                            return;
                        }
                    }
                }
            }
            if let Some(game_id) = current_game.as_deref() {
                // CR-033: patrones de vibración del mismo juego seleccionado.
                while let Some(pattern) = games.pop_rumble(game_id) {
                    use base64::Engine as _;
                    let length = u8::try_from(pattern.len()).unwrap_or(u8::MAX);
                    let msg = twinpad_core::game::GameMsg::Rumble {
                        length,
                        data: base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(pattern),
                    };
                    if let Ok(line) = msg.encode() {
                        if !sink.send_line(&line) {
                            return;
                        }
                    }
                }
            }
            if let Some(game_id) = current_game.as_deref() {
                if let Some((_, pcm)) = games.pop_audio(game_id) {
                    use base64::Engine as _;
                    let chunk = twinpad_core::audio::AudioMsg::AudioChunk {
                        seq,
                        ts: SystemClock.now_ms(),
                        data: base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(pcm),
                    };
                    seq = seq.wrapping_add(1);
                    if let Ok(line) = chunk.encode() {
                        if !sink.send_line(&line) {
                            return;
                        }
                    }
                } else {
                    std::thread::sleep(std::time::Duration::from_millis(2));
                }
            } else {
                std::thread::sleep(std::time::Duration::from_millis(20));
            }
        }
    }))
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
        if !self.is_video {
            let Ok(mut queue) = self.priority.audio_queue.lock() else {
                return false;
            };
            if queue.len() == SESSION_AUDIO_QUEUE_CAPACITY {
                queue.pop_front();
            }
            queue.push_back(line.to_string());
            drop(queue);
            return self.drain_audio_queue();
        }
        self.priority
            .video_waiting
            .store(true, std::sync::atomic::Ordering::Release);
        let Ok(_guard) = self.lock.0.lock() else {
            return false;
        };
        let sent = write_line(&mut self.inner, line);
        self.priority
            .video_waiting
            .store(false, std::sync::atomic::Ordering::Release);
        if let Ok(mut generation) = self.priority.video_generation.lock() {
            *generation = generation.wrapping_add(1);
            self.priority.video_sent.notify_all();
        }
        drop(_guard);
        sent && self.drain_audio_queue()
    }

    fn drain_audio_queue(&mut self) -> bool {
        loop {
            while self
                .priority
                .video_waiting
                .load(std::sync::atomic::Ordering::Acquire)
            {
                let Ok(generation) = self.priority.video_generation.lock() else {
                    return false;
                };
                let observed = *generation;
                let Ok(generation) = self.priority.video_sent.wait_while(generation, |current| {
                    *current == observed
                        && self
                            .priority
                            .video_waiting
                            .load(std::sync::atomic::Ordering::Acquire)
                }) else {
                    return false;
                };
                drop(generation);
            }
            let Ok(_guard) = self.lock.0.lock() else {
                return false;
            };
            if self
                .priority
                .video_waiting
                .load(std::sync::atomic::Ordering::Acquire)
            {
                drop(_guard);
                continue;
            }
            let Ok(mut queue) = self.priority.audio_queue.lock() else {
                return false;
            };
            let Some(line) = queue.pop_front() else {
                return true;
            };
            drop(queue);
            if !write_line(&mut self.inner, &line) {
                return false;
            }
        }
    }
}

fn write_line(stream: &mut std::net::TcpStream, line: &str) -> bool {
    use std::io::Write;
    stream
        .write_all(line.as_bytes())
        .and_then(|_| stream.write_all(b"\n"))
        .and_then(|_| stream.flush())
        .is_ok()
}

impl twinpad_server::video::FrameSink for StreamSink {
    fn send_line(&mut self, line: &str) -> bool {
        let message = twinpad_core::stream::StreamMsg::decode(line).ok();
        let sent = self.send_locked(line);
        if sent {
            if let (Some(registry), Some(session_id), Some(message)) =
                (&self.registry, self.session_id, message)
            {
                match message {
                    twinpad_core::stream::StreamMsg::StreamStart {
                        fmt: twinpad_core::stream::FrameFormat::H264,
                        ..
                    } => {
                        let game_id = self
                            .selected_game
                            .lock()
                            .ok()
                            .and_then(|selected| selected.clone());
                        if registry.selected_game(&session_id) == game_id {
                            if let Some(media_game) = &self.media_game {
                                if let Ok(mut active_media_game) = media_game.lock() {
                                    *active_media_game = game_id.clone();
                                }
                            }
                            registry.set_media_game(session_id, game_id);
                        }
                    }
                    twinpad_core::stream::StreamMsg::StreamStop { .. } => {
                        if let Some(media_game) = &self.media_game {
                            if let Ok(mut active_media_game) = media_game.lock() {
                                *active_media_game = None;
                            }
                        }
                        registry.set_media_game(session_id, None);
                    }
                    _ => {}
                }
            }
        }
        sent
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

fn bind_qr_ipc_listener(port: u16) -> std::io::Result<TcpListener> {
    TcpListener::bind(("127.0.0.1", port))
}

/// Serves the current pairing URI and QR matrix over a dedicated loopback-only TCP port.
fn run_qr_ipc_listener(
    listener: TcpListener,
    store: Arc<TokenStore<OsRandomBytes, SystemClock>>,
    games: Arc<twinpad_server::games::GameRegistry>,
) {
    for stream in listener.incoming() {
        let Ok(stream) = stream else { continue };
        let store = Arc::clone(&store);
        let games = Arc::clone(&games);
        std::thread::spawn(move || serve_qr_request(stream, &store, &games));
    }
}

fn serve_qr_request<R: RandomBytes, C: Clock>(
    mut stream: std::net::TcpStream,
    store: &TokenStore<R, C>,
    games: &twinpad_server::games::GameRegistry,
) {
    use std::io::{BufRead, BufReader, Write};
    let _ = stream.set_read_timeout(Some(std::time::Duration::from_secs(2)));
    let _ = stream.set_write_timeout(Some(std::time::Duration::from_secs(2)));
    let mut request = String::new();
    if BufReader::new(&stream).read_line(&mut request).is_err() || request.trim_end() != "GET_QR" {
        return;
    }
    let response = cemu_qr_response(store, games).to_string();
    if response.len() <= QR_IPC_RESPONSE_LIMIT {
        let _ = stream.write_all(response.as_bytes());
        let _ = stream.write_all(b"\n");
    }
}

fn cemu_qr_response<R: RandomBytes, C: Clock>(
    store: &TokenStore<R, C>,
    games: &twinpad_server::games::GameRegistry,
) -> serde_json::Value {
    let current = store.ensure_fresh_qr();
    match twinpad_server::qr_render::encode_matrix(&current.qr_uri) {
        Ok(modules) => serde_json::json!({
            "uri": current.qr_uri,
            "modules": modules,
            "cemu_live": games.has_live_game("cemu"),
        }),
        Err(_) => serde_json::json!({"error": "qr_unavailable"}),
    }
}

// ENG §6: unwrap permitido solo en tests.
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::panic)]
mod tests {
    use super::*;
    use std::io::BufRead;
    use std::net::TcpListener;
    use twinpad_core::input::InputMsg;

    // TEST-018 — verifies SPEC-004 (§9/R2: reporte con `seq` viejo se descarta).
    fn report(seq: u32) -> InputMsg {
        report_with_buttons(seq, 0)
    }

    fn report_with_buttons(seq: u32, buttons: u16) -> InputMsg {
        let line = format!(
            "{{\"type\":\"input_report\",\"seq\":{seq},\"stick_lx\":0,\"stick_ly\":0,\"stick_rx\":0,\"stick_ry\":0,\"dpad\":8,\"buttons\":{buttons},\"trigger_l2\":0,\"trigger_r2\":0,\"touch_x\":0,\"touch_y\":0,\"touch_pressed\":false}}"
        );
        InputMsg::decode(&line).unwrap()
    }

    // TEST-037 — verifies selected game's PCM is forwarded as audio_start/chunk.
    #[test]
    fn selected_game_audio_is_forwarded_to_the_session() {
        use std::io::BufRead;
        use std::net::TcpListener;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let client = std::net::TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        client
            .set_read_timeout(Some(std::time::Duration::from_secs(1)))
            .unwrap();
        let (server, _) = listener.accept().unwrap();
        let games = Arc::new(twinpad_server::games::GameRegistry::new());
        let game_listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let game_client =
            std::net::TcpStream::connect(game_listener.local_addr().unwrap()).unwrap();
        let (game_stream, _) = game_listener.accept().unwrap();
        games
            .register(
                twinpad_core::game::GameInfo {
                    game_id: "pacdemo".into(),
                    game_name: "Pac Demo".into(),
                    capabilities: vec!["audio".into()],
                },
                game_stream,
            )
            .unwrap();
        assert!(games.start_audio("pacdemo", 48_000, 2));
        assert!(games.push_audio("pacdemo", vec![7; 16]));
        let selected = Arc::new(std::sync::Mutex::new(Some("pacdemo".to_string())));
        let running = Arc::new(std::sync::atomic::AtomicBool::new(true));
        let handle = spawn_game_audio_forwarding(
            Arc::clone(&games),
            Some(server),
            Arc::new(SessionWriter::default()),
            SessionWriteLock::default(),
            selected,
            Arc::clone(&running),
        )
        .unwrap();
        let mut reader = std::io::BufReader::new(client);
        let mut start_line = String::new();
        let mut chunk_line = String::new();
        reader.read_line(&mut start_line).unwrap();
        reader.read_line(&mut chunk_line).unwrap();
        assert!(matches!(
            twinpad_core::audio::AudioMsg::decode(start_line.trim_end()),
            Ok(twinpad_core::audio::AudioMsg::AudioStart {
                rate: 48_000,
                channels: 2,
                ..
            })
        ));
        assert!(matches!(
            twinpad_core::audio::AudioMsg::decode(chunk_line.trim_end()),
            Ok(twinpad_core::audio::AudioMsg::AudioChunk { seq: 0, .. })
        ));
        running.store(false, std::sync::atomic::Ordering::Release);
        handle.join().unwrap();
        drop(game_client);
    }

    // TEST-040 — verifies video priority over queued audio at message boundaries.
    #[test]
    fn session_writer_sends_video_before_queued_audio() {
        use twinpad_server::video::FrameSink;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let client = std::net::TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (server, _) = listener.accept().unwrap();
        let writer = Arc::new(SessionWriter::default());
        let lock = SessionWriteLock::default();
        let selected = Arc::new(std::sync::Mutex::new(None));
        let mut audio = StreamSink {
            inner: server.try_clone().unwrap(),
            lock: lock.clone(),
            priority: Arc::clone(&writer),
            is_video: false,
            selected_game: Arc::clone(&selected),
            media_game: None,
            registry: None,
            session_id: None,
        };
        let mut video = StreamSink {
            inner: server,
            lock,
            priority: Arc::clone(&writer),
            is_video: true,
            selected_game: selected,
            media_game: None,
            registry: None,
            session_id: None,
        };
        writer
            .video_waiting
            .store(true, std::sync::atomic::Ordering::Release);
        let audio_send = std::thread::spawn(move || audio.send_line("audio_chunk"));
        while writer.audio_queue.lock().unwrap().is_empty() {
            std::thread::yield_now();
        }
        assert!(video.send_line("video_frame"));
        assert!(audio_send.join().unwrap());

        let mut reader = std::io::BufReader::new(client);
        let mut first = String::new();
        let mut second = String::new();
        reader.read_line(&mut first).unwrap();
        reader.read_line(&mut second).unwrap();
        assert_eq!(first.trim_end(), "video_frame");
        assert_eq!(second.trim_end(), "audio_chunk");
    }

    // TEST-052 — verifies SPEC-004/006: session input is retained and polled through SDK.
    #[test]
    fn selected_plugin_receives_initial_and_latest_input_snapshot_via_sdk_polling() {
        use std::io::{BufRead, BufReader};
        use std::net::TcpListener;

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let registry = Arc::new(twinpad_server::games::GameRegistry::new());
        let addr = listener.local_addr().unwrap();
        let retained_snapshot = Arc::new(std::sync::Mutex::new(Some(report(1).encode().unwrap())));
        let snapshot_for_server = Arc::clone(&retained_snapshot);
        let games = Arc::clone(&registry);
        let server = std::thread::spawn(move || {
            let (socket, _) = listener.accept().unwrap();
            let mut reader = BufReader::new(socket.try_clone().unwrap());
            let mut announce = String::new();
            reader.read_line(&mut announce).unwrap();
            let twinpad_core::game::GameMsg::GameAnnounce { game } =
                twinpad_core::game::GameMsg::decode(announce.trim_end()).unwrap()
            else {
                panic!("SDK must announce before accepting session input");
            };
            games.register(game, socket.try_clone().unwrap()).unwrap();
            // No reply is sent during the SDK's announcement-rejection probe.
            std::thread::sleep(std::time::Duration::from_millis(600));
            let first = snapshot_for_server.lock().unwrap().clone().unwrap();
            assert!(games.send_input_snapshot("polltest", &first));
            let second = report_with_buttons(2, 1).encode().unwrap();
            assert!(games.retain_and_send_input(Some("polltest"), &second));
        });

        let config = twinpad_sdk::GameConfig::new(
            "polltest",
            "Polling test",
            vec!["input_consumption".into()],
        )
        .unwrap();
        let game = twinpad_sdk::TwinpadGame::announce_on(addr, config).unwrap();
        server.join().unwrap();
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
        while !matches!(
            game.poll_input(),
            Some(InputMsg::InputReport { seq: 2, .. })
        ) && std::time::Instant::now() < deadline
        {
            std::thread::sleep(std::time::Duration::from_millis(5));
        }
        assert!(matches!(
            game.poll_input(),
            Some(InputMsg::InputReport {
                seq: 2,
                buttons: 1,
                ..
            })
        ));
    }

    // TEST-083 — verifies SPEC-004/CR-021 touch fields and timestamped inertial motion wire contract.
    #[test]
    fn accepted_input_report_roundtrips_for_cemu_ipc() {
        let msg = twinpad_core::input::InputMsg::InputReport {
            seq: 3,
            stick_lx: 0,
            stick_ly: 0,
            stick_rx: 0,
            stick_ry: 0,
            dpad: twinpad_core::input::DPAD_NEUTRAL,
            buttons: 1,
            trigger_l2: 0,
            trigger_r2: 0,
            motion: Some(twinpad_core::input::MotionSample {
                timestamp_us: 456_789,
                accel: [1.0, -2.0, 9.8066],
                gyro: [0.1, -0.2, 0.3],
            }),
            touch_x: 65535,
            touch_y: 123,
            touch_pressed: true,
        };
        let line = msg.encode().unwrap();
        assert_eq!(twinpad_core::input::InputMsg::decode(&line).unwrap(), msg);
        assert!(line.contains("\"touch_pressed\":true"));
        assert!(line.contains("\"motion\":{\"timestamp_us\":456789,"));
    }

    // TEST-042 — verifies CR-005: solo el primer dispositivo controla Cemu hasta desconexión.
    #[test]
    fn first_session_keeps_control_until_disconnect() {
        let owner = std::sync::Mutex::new(None);
        let first = twinpad_core::SessionId([1; 16]);
        let second = twinpad_core::SessionId([2; 16]);
        assert!(claim_input_owner(&owner, first));
        assert!(!claim_input_owner(&owner, second));
        release_input_owner(&owner, second);
        assert!(!claim_input_owner(&owner, second));
        release_input_owner(&owner, first);
        assert!(claim_input_owner(&owner, second));
    }

    // TEST-047 — verifies the current Pairing URI and QR modules over loopback.
    #[test]
    fn cemu_qr_ipc_returns_current_uri_and_matrix_only_on_loopback() {
        struct TestRng;
        impl RandomBytes for TestRng {
            fn fill(&self, buf: &mut [u8]) {
                buf.fill(0x2a);
            }
        }
        struct TestClock;
        impl Clock for TestClock {
            fn now_ms(&self) -> u64 {
                42_000
            }
        }
        let store = Arc::new(TokenStore::new(
            TestRng,
            TestClock,
            "192.168.1.8".into(),
            8420,
        ));
        let games = Arc::new(twinpad_server::games::GameRegistry::new());
        let listener = bind_qr_ipc_listener(0).unwrap();
        assert!(listener.local_addr().unwrap().ip().is_loopback());
        let address = listener.local_addr().unwrap();
        let server_store = Arc::clone(&store);
        let server_games = Arc::clone(&games);
        let server = std::thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            serve_qr_request(stream, &server_store, &server_games);
        });
        let mut client = std::net::TcpStream::connect(address).unwrap();
        client
            .set_read_timeout(Some(std::time::Duration::from_secs(2)))
            .unwrap();
        use std::io::{BufRead, Write};
        client.write_all(b"GET_QR\n").unwrap();
        let mut response = String::new();
        std::io::BufReader::new(client)
            .read_line(&mut response)
            .unwrap();
        server.join().unwrap();

        let response: serde_json::Value = serde_json::from_str(response.trim_end()).unwrap();
        let current = store.ensure_fresh_qr();
        assert_eq!(response["uri"], current.qr_uri);
        assert_eq!(response["cemu_live"], false);
        assert_eq!(
            response["modules"],
            serde_json::json!(twinpad_server::qr_render::encode_matrix(&current.qr_uri).unwrap())
        );
        assert!(twinpad_core::qr::parse(response["uri"].as_str().unwrap()).is_ok());
    }

    // TEST-050 — verifies SPEC-006/CR-009: Cemu registration is reported before QR pairing.
    #[test]
    fn cemu_qr_reports_adapter_registration_before_pairing() {
        struct TestRng;
        impl RandomBytes for TestRng {
            fn fill(&self, buf: &mut [u8]) {
                buf.fill(0x2a);
            }
        }
        struct TestClock;
        impl Clock for TestClock {
            fn now_ms(&self) -> u64 {
                42_000
            }
        }
        let store = TokenStore::new(TestRng, TestClock, "192.168.1.8".into(), 8420);
        let games = twinpad_server::games::GameRegistry::new();
        assert_eq!(cemu_qr_response(&store, &games)["cemu_live"], false);

        let game_listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let game_client =
            std::net::TcpStream::connect(game_listener.local_addr().unwrap()).unwrap();
        let (game_stream, _) = game_listener.accept().unwrap();
        games
            .register(
                twinpad_core::game::GameInfo {
                    game_id: "cemu".into(),
                    game_name: "Cemu".into(),
                    capabilities: vec!["second_screen".into(), "audio".into()],
                },
                game_stream,
            )
            .unwrap();
        assert_eq!(cemu_qr_response(&store, &games)["cemu_live"], true);
        drop(game_client);
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
