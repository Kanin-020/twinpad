//! Detección de juegos con SDK Twinpad — SPEC-006 (REQ-006 AC-1) + ADR-008.
//!
//! El canal IPC es TCP localhost NDJSON (ADR-008): la **conexión viva es el
//! anuncio vivo** — al caer (cierre o crash del juego), el juego deja de estar
//! disponible (§6 D1→D0, R2). `GameRegistry` mantiene la lista exacta de los
//! anuncios vivos (invariante §14) y rechaza `game_id` duplicados (§9).
//!
//! Además retiene el **último `game_frame`** publicado por cada juego (REQ-006
//! AC-2: el juego publica su Segunda pantalla vía SDK): el emisor de
//! SPEC-003 (`server::video`) lo lee como fuente preferente del stream. El
//! invariant §14 se mantiene: sin anuncio vivo no hay frames ni stream.

use std::collections::BTreeMap;
use std::io::{BufRead, BufReader};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use crate::media::{AccessUnit, MediaHub, MediaSource};
use twinpad_core::game::{GameInfo, GameMsg};

/// Timeout para recibir el anuncio tras conectar (§9: el SDK anuncia al
/// arrancar; una conexión muda no queda viva).
pub const ANNOUNCE_TIMEOUT: Duration = Duration::from_secs(10);

/// Un Framebuffer publicado por un juego (REQ-006 AC-2), ya decodificado y
/// listo para el emisor de SPEC-003 (contiene `w×h×bpp` bytes).
#[derive(Clone, PartialEq, Eq, Debug)]
pub struct GameFrame {
    /// `fn` del stream de la app: el server lo asigna al consumir (SPEC-003
    /// §4: monotónico desde 0 por stream, R2). Aquí viaja el `gfn` del juego.
    pub gfn: u64,
    pub w: u16,
    pub h: u16,
    pub fmt: twinpad_core::stream::FrameFormat,
    pub ts_capture_us: Option<u64>,
    pub idr: Option<bool>,
    /// Framebuffer crudo o access unit (sin base64).
    pub data: Arc<[u8]>,
}

/// Error de registro de un anuncio (§9).
#[derive(Debug)]
pub enum RegisterError {
    /// Ya hay un juego vivo con el mismo `game_id`.
    Duplicate,
}

struct GameEntry {
    info: GameInfo,
    _stream: TcpStream,
    /// Último `game_frame` publicado (AC-2); `None` hasta que el juego
    /// publique el primero (§10: capacidades sin usar → sin stream).
    last_frame: Option<GameFrame>,
    audio_start: Option<(u32, u8)>,
    audio_chunks: std::collections::VecDeque<Vec<u8>>,
    audio_connection_active: bool,
    /// Patrones de vibración pendientes (CR-033), acotados como el audio.
    rumble_queue: std::collections::VecDeque<Vec<u8>>,
    /// Estados de demanda de cámara pendientes (CR-038), acotados como el audio.
    camera_request_queue: std::collections::VecDeque<bool>,
    /// Último estado de demanda de camera_request vigente (CR-038) para
    /// reenviarlo a una sesión que seleccione el Plugin.
    camera_request_last: Option<bool>,
    /// Estados de demanda de micrófono pendientes (CR-035), acotados como el audio.
    mic_request_queue: std::collections::VecDeque<bool>,
    /// Último estado de demanda de mic_request vigente (CR-035).
    mic_request_last: Option<bool>,
}

/// Registro en memoria de juegos compatibles vivos (§6: D0/D1 por juego).
#[derive(Default)]
pub struct GameRegistry {
    games: Mutex<BTreeMap<String, GameEntry>>,
}

impl GameRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    /// Registra un anuncio vivo. `Duplicate` si el `game_id` ya está vivo (§9).
    ///
    /// # Errors
    /// [`RegisterError::Duplicate`] si el id está tomado; el stream no queda
    /// registrado y el caller debe cerrar la conexión.
    pub fn register(&self, info: GameInfo, stream: TcpStream) -> Result<(), RegisterError> {
        let mut games = lock_or_reset(&self.games);
        if games.contains_key(&info.game_id) {
            return Err(RegisterError::Duplicate);
        }
        games.insert(
            info.game_id.clone(),
            GameEntry {
                info,
                _stream: stream,
                last_frame: None,
                audio_start: None,
                audio_chunks: std::collections::VecDeque::new(),
                audio_connection_active: false,
                rumble_queue: std::collections::VecDeque::new(),
                camera_request_queue: std::collections::VecDeque::new(),
                camera_request_last: None,
                mic_request_queue: std::collections::VecDeque::new(),
                mic_request_last: None,
            },
        );
        Ok(())
    }

    /// Retira un juego (cayó su conexión); `true` si estaba registrado.
    /// El invariante §14 es lo que garantiza que su último frame muere con él.
    pub fn unregister(&self, game_id: &str) -> bool {
        lock_or_reset(&self.games).remove(game_id).is_some()
    }

    /// Lista de juegos vivos ordenada por `game_id` (§5/§14).
    pub fn list(&self) -> Vec<GameInfo> {
        lock_or_reset(&self.games)
            .values()
            .map(|e| e.info.clone())
            .collect()
    }

    /// Indica si un juego sigue registrado aunque aún no haya publicado un frame.
    pub fn has_live_game(&self, game_id: &str) -> bool {
        lock_or_reset(&self.games).contains_key(game_id)
    }

    /// Juegos vivos (para logs).
    pub fn len(&self) -> usize {
        lock_or_reset(&self.games).len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Guarda el último frame publicado por un juego vivo (AC-2). Devuelve
    /// `false` si el juego no está vivo: el frame se descarta (invariante
    /// §14: sin anuncio vivo no hay contenido).
    pub fn store_frame(&self, game_id: &str, frame: GameFrame) -> bool {
        match lock_or_reset(&self.games).get_mut(game_id) {
            Some(entry) => {
                entry.last_frame = Some(frame);
                true
            }
            None => false,
        }
    }

    pub fn store_access_unit(&self, game_id: &str, frame: GameFrame) -> bool {
        if frame.fmt != twinpad_core::stream::FrameFormat::H264
            || frame.data.is_empty()
            || frame.ts_capture_us.is_none()
            || frame.idr.is_none()
        {
            return false;
        }
        self.store_frame(game_id, frame)
    }

    /// Último frame del Plugin vivo indicado (AC-2), o `None` si no ha publicado.
    pub fn latest_frame(&self, game_id: &str) -> Option<GameFrame> {
        lock_or_reset(&self.games)
            .get(game_id)
            .and_then(|e| e.last_frame.clone())
    }

    /// Registra el inicio de una fuente PCM solo para juego vivo con capability audio.
    pub fn start_audio(&self, game_id: &str, rate: u32, channels: u8) -> bool {
        let mut games = lock_or_reset(&self.games);
        let Some(entry) = games.get_mut(game_id) else {
            return false;
        };
        if !entry.info.capabilities.iter().any(|cap| cap == "audio")
            || entry.audio_connection_active
        {
            return false;
        }
        entry.audio_start = Some((rate, channels));
        entry.audio_chunks.clear();
        entry.audio_connection_active = true;
        true
    }

    /// Encola PCM recibido; al saturar descarta los chunks antiguos (CR-003).
    pub fn push_audio(&self, game_id: &str, pcm: Vec<u8>) -> bool {
        let mut games = lock_or_reset(&self.games);
        let Some(entry) = games.get_mut(game_id) else {
            return false;
        };
        if !entry.audio_connection_active {
            return false;
        }
        while entry.audio_chunks.len() >= 8 {
            entry.audio_chunks.pop_front();
        }
        entry.audio_chunks.push_back(pcm);
        true
    }

    /// Encola un patrón de vibración de un juego vivo (CR-033). Al
    /// saturar descarta los más antiguos: solo importa el último estado.
    pub fn push_rumble(&self, game_id: &str, pattern: Vec<u8>) -> bool {
        let mut games = lock_or_reset(&self.games);
        let Some(entry) = games.get_mut(game_id) else {
            return false;
        };
        while entry.rumble_queue.len() >= 8 {
            entry.rumble_queue.pop_front();
        }
        entry.rumble_queue.push_back(pattern);
        true
    }

    /// Siguiente patrón de vibración pendiente del juego (CR-033).
    pub fn pop_rumble(&self, game_id: &str) -> Option<Vec<u8>> {
        lock_or_reset(&self.games)
            .get_mut(game_id)?
            .rumble_queue
            .pop_front()
    }

    /// Registra un cambio de demanda de cámara (CR-038): actualiza el
    /// último estado vigente y encola la entrega a la sesión.
    pub fn push_camera_request(&self, game_id: &str, on: bool) -> bool {
        let mut games = lock_or_reset(&self.games);
        let Some(entry) = games.get_mut(game_id) else {
            return false;
        };
        while entry.camera_request_queue.len() >= 8 {
            entry.camera_request_queue.pop_front();
        }
        entry.camera_request_last = Some(on);
        entry.camera_request_queue.push_back(on);
        true
    }

    /// Reencola el último estado vigente de cámara (CR-038) para una sesión
    /// que acaba de seleccionar el juego; no altera `camera_request_last`.
    pub fn requeue_camera_request(&self, game_id: &str) -> bool {
        let mut games = lock_or_reset(&self.games);
        let Some(entry) = games.get_mut(game_id) else {
            return false;
        };
        let Some(on) = entry.camera_request_last else {
            return false;
        };
        while entry.camera_request_queue.len() >= 8 {
            entry.camera_request_queue.pop_front();
        }
        entry.camera_request_queue.push_back(on);
        true
    }

    /// Siguiente estado de camera_request pendiente (CR-038).
    pub fn pop_camera_request(&self, game_id: &str) -> Option<bool> {
        lock_or_reset(&self.games)
            .get_mut(game_id)?
            .camera_request_queue
            .pop_front()
    }

    /// Registra un cambio de demanda de micrófono (CR-035): actualiza el
    /// último estado vigente y encola la entrega a la sesión.
    pub fn push_mic_request(&self, game_id: &str, on: bool) -> bool {
        let mut games = lock_or_reset(&self.games);
        let Some(entry) = games.get_mut(game_id) else {
            return false;
        };
        while entry.mic_request_queue.len() >= 8 {
            entry.mic_request_queue.pop_front();
        }
        entry.mic_request_last = Some(on);
        entry.mic_request_queue.push_back(on);
        true
    }

    /// Reencola el último estado vigente de micrófono (CR-035) para una
    /// sesión que acaba de seleccionar el juego.
    pub fn requeue_mic_request(&self, game_id: &str) -> bool {
        let mut games = lock_or_reset(&self.games);
        let Some(entry) = games.get_mut(game_id) else {
            return false;
        };
        let Some(on) = entry.mic_request_last else {
            return false;
        };
        while entry.mic_request_queue.len() >= 8 {
            entry.mic_request_queue.pop_front();
        }
        entry.mic_request_queue.push_back(on);
        true
    }

    /// Siguiente estado de mic_request pendiente (CR-035).
    pub fn pop_mic_request(&self, game_id: &str) -> Option<bool> {
        lock_or_reset(&self.games)
            .get_mut(game_id)?
            .mic_request_queue
            .pop_front()
    }

    /// Retira una fuente de audio al recibir stop o perder su conexión.
    pub fn stop_audio(&self, game_id: &str) -> Option<(u32, u8)> {
        let mut games = lock_or_reset(&self.games);
        let entry = games.get_mut(game_id)?;
        entry.audio_connection_active = false;
        entry.audio_chunks.clear();
        entry.audio_start.take()
    }

    /// Indica si el juego mantiene una fuente PCM activa.
    pub fn audio_active(&self, game_id: &str) -> bool {
        lock_or_reset(&self.games)
            .get(game_id)
            .is_some_and(|entry| entry.audio_start.is_some())
    }

    /// Audio pendiente del juego vivo seleccionado, sin clonar si no existe.
    pub fn pop_audio(&self, game_id: &str) -> Option<((u32, u8), Vec<u8>)> {
        let mut games = lock_or_reset(&self.games);
        let entry = games.get_mut(game_id)?;
        Some((entry.audio_start?, entry.audio_chunks.pop_front()?))
    }

    /// Formato vigente de una fuente de audio activa.
    pub fn audio_params(&self, game_id: &str) -> Option<(u32, u8)> {
        lock_or_reset(&self.games)
            .get(game_id)
            .and_then(|entry| entry.audio_start)
    }

    /// Indica si un juego tiene una fuente PCM activa.
    pub fn has_audio_source(&self, game_id: &str) -> bool {
        lock_or_reset(&self.games)
            .get(game_id)
            .is_some_and(|entry| entry.audio_start.is_some())
    }

    /// Fuente preferente heredada para pruebas/compatibilidad interna.
    pub fn preferred_source(&self) -> Option<(String, GameFrame)> {
        lock_or_reset(&self.games)
            .iter()
            .find_map(|(id, entry)| entry.last_frame.clone().map(|frame| (id.clone(), frame)))
    }

    /// Conserva el último `input_report` aceptado para la sesión y lo reenvía
    /// únicamente al Plugin seleccionado. Devuelve false si no hay destino vivo.
    pub fn retain_and_send_input(&self, game_id: Option<&str>, line: &str) -> bool {
        let Some(game_id) = game_id else { return false };
        let mut games = lock_or_reset(&self.games);
        let Some(entry) = games.get_mut(game_id) else {
            return false;
        };
        Self::write_input(entry, line)
    }

    /// Envía al adaptador Cemu la resolución y el bitrate configurados en la
    /// sesión Android (CR-027/CR-030). El framerate ya no viaja ni se
    /// reenvía: sin tope ni pacing (CR-029), la tasa es la que sostenga
    /// captura+encoder. Cemu aplica w/h SOLO al framebuffer del GamePad
    /// (DRC); la resolución del juego en TV no cambia.
    pub fn send_cemu_video_config(&self, width: u16, height: u16, bitrate_kbps: u32) -> bool {
        let mut games = lock_or_reset(&self.games);
        let Some(entry) = games.get_mut("cemu") else {
            return false;
        };
        let (width, height) = (
            width.clamp(
                twinpad_core::stream::MIN_DIM,
                twinpad_core::stream::MAX_WIDTH,
            ),
            height.clamp(
                twinpad_core::stream::MIN_DIM,
                twinpad_core::stream::MAX_HEIGHT,
            ),
        );
        let bitrate_kbps = bitrate_kbps.clamp(1_000, 30_000);
        let line = format!(
            "{{\"type\":\"video_config\",\"w\":{width},\"h\":{height},\"bitrate_kbps\":{bitrate_kbps}}}"
        );
        Self::write_input(entry, &line)
    }

    /// Reenvía a Cemu una access unit de la cámara frontal (CR-034). No se
    /// persiste: si Cemu no está vivo el frame se descarta.
    pub fn send_cemu_camera_frame(&self, line: &str) -> bool {
        let mut games = lock_or_reset(&self.games);
        let Some(entry) = games.get_mut("cemu") else {
            return false;
        };
        Self::write_input(entry, line)
    }

    /// Reenvía a Cemu un chunk de PCM del micrófono del teléfono (CR-035).
    /// No se persiste: si Cemu no está vivo o no está seleccionado, el
    /// chunk se descarta (rachas en tiempo real).
    pub fn send_cemu_mic_chunk(&self, line: &str) -> bool {
        self.send_cemu_camera_frame(line)
    }

    /// Entrega el snapshot inicial a un Plugin recién seleccionado.
    pub fn send_input_snapshot(&self, game_id: &str, snapshot: &str) -> bool {
        let mut games = lock_or_reset(&self.games);
        let Some(entry) = games.get_mut(game_id) else {
            return false;
        };
        Self::write_input(entry, snapshot)
    }

    /// Mantiene el test de forwarding legacy centrado en Cemu.
    #[cfg(test)]
    pub fn send_cemu_input(&self, line: &str) -> bool {
        self.retain_and_send_input(Some("cemu"), line)
    }

    fn write_input(entry: &mut GameEntry, line: &str) -> bool {
        use std::io::Write;
        entry
            ._stream
            .write_all(line.as_bytes())
            .and_then(|_| entry._stream.write_all(b"\n"))
            .and_then(|_| entry._stream.flush())
            .is_ok()
    }
}

/// Toma el lock; envenenado → degrada a registro vacío (seguro: los juegos
/// "perdidos" re-anuncian o se detectan al caer sus conexiones).
fn lock_or_reset<'a>(
    mutex: &'a Mutex<BTreeMap<String, GameEntry>>,
) -> std::sync::MutexGuard<'a, BTreeMap<String, GameEntry>> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Callback de publicación: se invoca con la lista vigente cada vez que el
/// registro cambia (§7 paso 3: el server publica a las sesiones activas).
pub type Publisher = dyn Fn(&[GameInfo]) + Send + Sync;

/// Bucle del listener IPC (ADR-008): un hilo por conexión de juego; al caer,
/// retira el juego y publica la lista nueva. Bloquea el hilo caller.
pub fn run_ipc_listener(
    listener: TcpListener,
    registry: std::sync::Arc<GameRegistry>,
    publish: std::sync::Arc<Publisher>,
) {
    run_ipc_listener_with_media(listener, registry, publish, None);
}

pub fn run_ipc_listener_with_media(
    listener: TcpListener,
    registry: std::sync::Arc<GameRegistry>,
    publish: std::sync::Arc<Publisher>,
    media_hub: Option<Arc<MediaHub>>,
) {
    for stream in listener.incoming() {
        let Ok(stream) = stream else { continue };
        let registry = std::sync::Arc::clone(&registry);
        let publish = std::sync::Arc::clone(&publish);
        let media_hub = media_hub.clone();
        std::thread::spawn(move || {
            handle_game_connection(stream, &registry, publish.as_ref(), media_hub.as_deref())
        });
    }
}

/// Listener de PCM: valida anuncio/capability y mantiene una sola fuente por juego.
pub fn run_audio_ipc_listener(listener: TcpListener, registry: Arc<GameRegistry>) {
    for stream in listener.incoming() {
        let Ok(stream) = stream else { continue };
        let registry = Arc::clone(&registry);
        std::thread::spawn(move || handle_audio_connection(stream, &registry));
    }
}

fn handle_audio_connection(stream: TcpStream, registry: &GameRegistry) {
    let Ok(reader_stream) = stream.try_clone() else {
        return;
    };
    let mut reader = BufReader::new(reader_stream);
    let Some(line) = read_line_with_timeout(&mut reader, ANNOUNCE_TIMEOUT) else {
        return;
    };
    let Ok(twinpad_core::game_audio::GameAudioMsg::Start {
        game_id,
        rate,
        channels,
        ..
    }) = twinpad_core::game_audio::GameAudioMsg::decode(&line)
    else {
        return;
    };
    if !registry.start_audio(&game_id, rate, channels) {
        return;
    }
    while let Some(line) = read_line(&mut reader) {
        match twinpad_core::game_audio::GameAudioMsg::decode(&line) {
            Ok(twinpad_core::game_audio::GameAudioMsg::Chunk {
                game_id: id, data, ..
            }) if id == game_id => {
                use base64::Engine as _;
                if let Ok(pcm) = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(data) {
                    registry.push_audio(&game_id, pcm);
                }
            }
            Ok(twinpad_core::game_audio::GameAudioMsg::Stop { game_id: id }) if id == game_id => {
                break;
            }
            _ => break,
        }
    }
    registry.stop_audio(&game_id);
}

/// Ciclo de vida de una conexión de juego: anuncio → registro → espera → baja.
fn handle_game_connection(
    stream: TcpStream,
    registry: &GameRegistry,
    publish: &Publisher,
    media_hub: Option<&MediaHub>,
) {
    let Ok(reader_stream) = stream.try_clone() else {
        return;
    };
    let mut reader = BufReader::new(reader_stream);
    let Some(line) = read_line_with_timeout(&mut reader, ANNOUNCE_TIMEOUT) else {
        return; // conexión muda o caída: nunca estuvo disponible
    };
    let Ok(GameMsg::GameAnnounce { game }) = GameMsg::decode(&line) else {
        println!("Anuncio de juego inválido descartado.");
        return;
    };

    match registry.register(game.clone(), stream) {
        Ok(()) => {}
        Err(RegisterError::Duplicate) => {
            println!(
                "Anuncio rechazado: game_id '{}' duplicado (§9).",
                game.game_id
            );
            return;
        }
    }
    println!(
        "Juego compatible disponible: {} ({}) — {} en total.",
        game.game_name,
        game.game_id,
        registry.len()
    );
    publish(&registry.list());

    // Liveness: la conexión viva es el anuncio vivo (ADR-008). EOF = fin.
    // REQ-006 AC-2: además de mantener viva la conexión, cada línea puede
    // ser un `game_frame` con el contenido de la Segunda pantalla del juego;
    // se decodifica estrictamente (E4 en la frontera) y se retiene el
    // último para el emisor de SPEC-003.
    loop {
        let Some(line) = read_line(&mut reader) else {
            if registry.unregister(&game.game_id) {
                println!(
                    "Juego compatible fuera de línea: {} ({}).",
                    game.game_name, game.game_id
                );
                publish(&registry.list());
            }
            if let Some(hub) = media_hub {
                hub.clear_game(&game.game_id);
            }
            return;
        };
        if line.is_empty() {
            continue;
        }
        match GameMsg::decode(&line) {
            Ok(GameMsg::GameFrame {
                gfn,
                w,
                h,
                fmt,
                ts_capture_us,
                idr,
                data,
            }) => match decode_frame_data(&data, w, h, fmt) {
                Ok(bytes) => {
                    let frame = GameFrame {
                        gfn,
                        w,
                        h,
                        fmt,
                        ts_capture_us,
                        idr,
                        data: bytes.into(),
                    };
                    if fmt == twinpad_core::stream::FrameFormat::H264 {
                        if registry.store_access_unit(&game.game_id, frame.clone()) {
                            if let (Some(hub), Some(timestamp_us), Some(is_idr)) =
                                (media_hub, ts_capture_us, idr)
                            {
                                hub.publish(MediaSource {
                                    game_id: game.game_id.clone(),
                                    width: w,
                                    height: h,
                                    latest: AccessUnit {
                                        fn_: gfn,
                                        timestamp_us,
                                        idr: is_idr,
                                        annex_b: frame.data,
                                    },
                                });
                            }
                        }
                    } else {
                        registry.store_frame(&game.game_id, frame);
                    }
                }
                Err(_) => println!("game_frame inválido de '{}' descartado.", game.game_id),
            },
            Ok(GameMsg::Rumble { length, data }) => {
                use base64::Engine as _;
                if let Ok(pattern) = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(&data)
                {
                    if pattern.len() == usize::from(length) {
                        registry.push_rumble(&game.game_id, pattern);
                    }
                }
            }
            // CR-038: demanda vigente de la cámara; el drenaje por sesión en
            // `spawn_game_audio_forwarding` la entrega al teléfono.
            Ok(GameMsg::CameraRequest { on }) => {
                registry.push_camera_request(&game.game_id, on);
            }
            // CR-035: demanda vigente del micrófono; el drenaje por sesión
            // la entrega al teléfono.
            Ok(GameMsg::MicRequest { on }) => {
                registry.push_mic_request(&game.game_id, on);
            }
            Ok(_) => {} // otro mensaje sobre la conexión viva: ignorado en v1
            Err(_) => println!("Línea inválida de '{}' descartada (E4).", game.game_id),
        }
    }
}

/// Decodifica el base64url de un `game_frame` (ya validado en rangos por
/// [`GameMsg::decode`]); error si la longitud no es `w×h×bpp`.
fn decode_frame_data(
    data: &str,
    w: u16,
    h: u16,
    fmt: twinpad_core::stream::FrameFormat,
) -> Result<Vec<u8>, ()> {
    use base64::Engine as _;
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(data)
        .map_err(|_| ())?;
    // `jpeg` (CR-001) tiene tamaño libre: solo los formatos crudos
    // deben medir exactamente w×h×bpp.
    match fmt.bytes_per_pixel() {
        Some(bpp) if bytes.len() == usize::from(w) * usize::from(h) * bpp => Ok(bytes),
        Some(_) => Err(()),
        None => Ok(bytes),
    }
}

fn read_line_with_timeout(reader: &mut BufReader<TcpStream>, timeout: Duration) -> Option<String> {
    reader.get_ref().set_read_timeout(Some(timeout)).ok()?;
    read_line(reader)
}

fn read_line(reader: &mut BufReader<TcpStream>) -> Option<String> {
    let mut buf = String::new();
    match reader.read_line(&mut buf) {
        Ok(0) | Err(_) => None,
        Ok(_) => Some(buf.trim_end_matches('\n').to_string()),
    }
}

// ENG §6: unwrap permitido solo en tests.
#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use std::net::TcpListener;

    fn loopback_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let client = TcpStream::connect(addr).unwrap();
        let (server_side, _) = listener.accept().unwrap();
        (server_side, client)
    }

    fn info(id: &str) -> GameInfo {
        GameInfo {
            game_id: id.into(),
            game_name: format!("Juego {id}"),
            capabilities: vec!["second_screen".into()],
        }
    }

    // TEST-033 — verifies SPEC-006: preferred-source snapshots share frame bytes without copying.
    #[test]
    fn preferred_source_shares_rgb_frame_storage() {
        let registry = GameRegistry::new();
        let (stream, _peer) = loopback_pair();
        registry.register(info("pacdemo"), stream).unwrap();
        let pixels: Arc<[u8]> = vec![9; 16 * 16 * 3].into();
        let original_ptr = pixels.as_ptr();
        assert!(registry.store_frame(
            "pacdemo",
            GameFrame {
                gfn: 0,
                w: 16,
                h: 16,
                fmt: twinpad_core::stream::FrameFormat::Rgb8,
                ts_capture_us: None,
                idr: None,
                data: Arc::clone(&pixels),
            },
        ));

        let (_, shared) = registry.preferred_source().unwrap();
        assert_eq!(shared.data.as_ptr(), original_ptr);
    }

    #[test]
    fn registered_game_is_listed_sorted() {
        let registry = GameRegistry::new();
        let (s1, _c1) = loopback_pair();
        let (s2, _c2) = loopback_pair();
        registry.register(info("zeta"), s1).unwrap();
        registry.register(info("alfa"), s2).unwrap();
        let list = registry.list();
        assert_eq!(list.len(), 2);
        assert_eq!(list[0].game_id, "alfa"); // §5: orden por game_id
        assert_eq!(registry.len(), 2);
    }

    // TEST-044 — verifies SPEC-006/CR-005: NDJSON input is forwarded to a live Cemu adapter.
    #[test]
    fn cemu_input_is_forwarded_to_live_adapter_only() {
        use std::io::{BufRead, BufReader};

        let registry = GameRegistry::new();
        let (server_side, client) = loopback_pair();
        registry
            .register(
                GameInfo {
                    game_id: "cemu".into(),
                    game_name: "Cemu".into(),
                    capabilities: vec!["second_screen".into(), "audio".into()],
                },
                server_side,
            )
            .unwrap();
        assert!(registry.send_cemu_input(r#"{"type":"input_report","seq":1}"#));
        let mut line = String::new();
        BufReader::new(client.try_clone().unwrap())
            .read_line(&mut line)
            .unwrap();
        assert_eq!(line, "{\"type\":\"input_report\",\"seq\":1}\n");
        assert!(registry.unregister("cemu"));
        assert!(!registry.send_cemu_input("{}"));
    }

    // TEST-094 — verifies SPEC-006 (CR-034): `camera_frame` reaches only the live Cemu
    // Plugin, is forwarded as-is, and is dropped (not stored) when Cemu is not live.
    #[test]
    fn camera_frame_routes_only_to_selected_cemu() {
        use std::io::{BufRead, BufReader};

        let registry = GameRegistry::new();
        let line =
            r#"{"type":"camera_frame","w":320,"h":240,"ts_capture_us":0,"idr":true,"data":"AAEC"}"#;
        assert!(!registry.send_cemu_camera_frame(line));

        let (server_side, client) = loopback_pair();
        registry
            .register(
                GameInfo {
                    game_id: "cemu".into(),
                    game_name: "Cemu".into(),
                    capabilities: vec!["second_screen".into()],
                },
                server_side,
            )
            .unwrap();
        assert!(registry.send_cemu_camera_frame(line));
        let mut received = String::new();
        BufReader::new(client.try_clone().unwrap())
            .read_line(&mut received)
            .unwrap();
        assert_eq!(received, format!("{line}\n"));
        assert!(registry.unregister("cemu"));
        assert!(!registry.send_cemu_camera_frame(line));
    }

    // TEST-068 — verifies SPEC-003/CR-030: the runtime video_config forwarded to Cemu
    // carries the configured resolution and bitrate (resolution applies to the GamePad
    // screen only); values outside SPEC-003 ranges are clamped before forwarding.
    #[test]
    fn cemu_video_config_forwarding_includes_resolution_and_bitrate() {
        use std::io::{BufRead, BufReader};

        let registry = GameRegistry::new();
        let (server_side, client) = loopback_pair();
        registry
            .register(
                GameInfo {
                    game_id: "cemu".into(),
                    game_name: "Cemu".into(),
                    capabilities: vec!["second_screen".into(), "audio".into()],
                },
                server_side,
            )
            .unwrap();
        assert!(registry.send_cemu_video_config(1280, 720, 8_000));
        let mut line = String::new();
        BufReader::new(client.try_clone().unwrap())
            .read_line(&mut line)
            .unwrap();
        assert_eq!(
            line,
            "{\"type\":\"video_config\",\"w\":1280,\"h\":720,\"bitrate_kbps\":8000}\n"
        );
        assert!(registry.send_cemu_video_config(5000, 4000, 500));
        let mut line = String::new();
        BufReader::new(client.try_clone().unwrap())
            .read_line(&mut line)
            .unwrap();
        assert_eq!(
            line,
            "{\"type\":\"video_config\",\"w\":3840,\"h\":2160,\"bitrate_kbps\":1000}\n"
        );
        assert!(registry.unregister("cemu"));
        assert!(!registry.send_cemu_video_config(1280, 720, 8_000));
    }

    // TEST-098 — verifies SPEC-006 (CR-038): camera_request transitions are
    // queued in order, the last state survives for a re-selection, and an
    // unknown game has nothing to deliver.
    #[test]
    fn camera_request_queues_transitions_and_keeps_last_state() {
        let registry = GameRegistry::new();
        let (server_side, _client) = loopback_pair();
        registry
            .register(
                GameInfo {
                    game_id: "cemu".into(),
                    game_name: "Cemu".into(),
                    capabilities: vec!["second_screen".into()],
                },
                server_side,
            )
            .unwrap();
        assert!(!registry.push_camera_request("otro-juego", true));

        registry.push_camera_request("cemu", true); // 0 → 1 instancias HLE
        registry.push_camera_request("cemu", false); // de vuelta a 0
        registry.push_camera_request("cemu", true);
        assert_eq!(registry.pop_camera_request("cemu"), Some(true));
        assert_eq!(registry.pop_camera_request("cemu"), Some(false));
        assert_eq!(registry.pop_camera_request("cemu"), Some(true));
        assert_eq!(registry.pop_camera_request("cemu"), None);

        // El último estado vigente se reencola para una nueva sesión.
        assert!(registry.requeue_camera_request("cemu"));
        assert_eq!(registry.pop_camera_request("cemu"), Some(true));
        assert!(registry.unregister("cemu"));
        assert_eq!(registry.pop_camera_request("cemu"), None);
    }

    // TEST-0102 — verifies SPEC-006 (CR-035): mic_request transitions are
    // queued in order with the same last-state requeue, and a live plugin
    // receives mic_chunk lines only while it stays selected.
    #[test]
    fn mic_request_queues_and_mic_chunk_reaches_selected_cemu() {
        let registry = GameRegistry::new();
        let (server_side, client) = loopback_pair();
        registry
            .register(
                GameInfo {
                    game_id: "cemu".into(),
                    game_name: "Cemu".into(),
                    capabilities: vec!["second_screen".into()],
                },
                server_side,
            )
            .unwrap();
        assert!(!registry.push_mic_request("otro-juego", true));

        registry.push_mic_request("cemu", true);
        registry.push_mic_request("cemu", false);
        assert_eq!(registry.pop_mic_request("cemu"), Some(true));
        assert_eq!(registry.pop_mic_request("cemu"), Some(false));
        assert_eq!(registry.pop_mic_request("cemu"), None);
        assert!(registry.requeue_mic_request("cemu"));
        assert_eq!(registry.pop_mic_request("cemu"), Some(false));

        // El chunk vivo llega al plugin seleccionado por la conexión real.
        assert!(
            registry.send_cemu_mic_chunk(r#"{"type":"mic_chunk","samples":32,"data":"AAECAwQ"}"#)
        );
        let mut line = String::new();
        std::io::BufRead::read_line(&mut std::io::BufReader::new(&client), &mut line).unwrap();
        assert_eq!(
            line.trim(),
            r#"{"type":"mic_chunk","samples":32,"data":"AAECAwQ"}"#
        );

        // Sin plugin vivo se descarta sin persistencia.
        assert!(registry.unregister("cemu"));
        assert!(
            !registry.send_cemu_mic_chunk(r#"{"type":"mic_chunk","samples":32,"data":"AAECAwQ"}"#)
        );
        assert_eq!(registry.pop_mic_request("cemu"), None);
    }

    // TEST-049 — verifies SPEC-006/CR-008: a live Cemu adapter is discoverable before its first DRC frame.
    #[test]
    fn live_cemu_is_detected_before_first_frame() {
        let registry = GameRegistry::new();
        let (server_side, _client) = loopback_pair();
        registry
            .register(
                GameInfo {
                    game_id: "cemu".into(),
                    game_name: "Cemu".into(),
                    capabilities: vec!["second_screen".into(), "audio".into()],
                },
                server_side,
            )
            .unwrap();
        assert!(registry.has_live_game("cemu"));
        assert!(registry.preferred_source().is_none());
        assert!(registry.latest_frame("cemu").is_none());
        assert!(registry.unregister("cemu"));
        assert!(!registry.has_live_game("cemu"));
    }

    #[test]
    fn duplicate_game_id_is_rejected() {
        let registry = GameRegistry::new();
        let (s1, _c1) = loopback_pair();
        let (s2, _c2) = loopback_pair();
        registry.register(info("pacdemo"), s1).unwrap();
        assert!(matches!(
            registry.register(info("pacdemo"), s2),
            Err(RegisterError::Duplicate)
        ));
        assert_eq!(registry.len(), 1);
    }

    #[test]
    fn unregister_removes_and_reports() {
        let registry = GameRegistry::new();
        let (s, _c) = loopback_pair();
        registry.register(info("pacdemo"), s).unwrap();
        assert!(registry.unregister("pacdemo"));
        assert!(!registry.unregister("pacdemo"));
        assert!(registry.is_empty());
    }
}
