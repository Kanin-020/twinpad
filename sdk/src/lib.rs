//! SDK Twinpad — binding para juegos huésped (REQ-006, ADR-006).
//!
//! Frontera ARCH §3: el SDK habla con el `Servidor PC` solo vía la API de
//! anuncio/registro del juego; nunca escribe directo en la red/transporte.
//!
//! v1 (SPEC-006 `Approved` + ADR-008): anuncio de presencia por el canal IPC
//! TCP localhost. La conexión viva **es** el anuncio vivo: al soltar
//! [`TwinpadGame`] (o al crashear el proceso) el juego deja de estar
//! disponible automáticamente. Las actualizaciones de inputs recibidas por
//! el mismo canal se conservan como snapshot consultable por polling.
//!
//! AC-2 (REQ-006): el juego publica los Framebuffers de su Segunda pantalla
//! con [`TwinpadGame::push_frame`]; el server los re-empaqueta como
//! `video_frame` de SPEC-003 hacia la app.

use std::collections::VecDeque;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

use twinpad_core::game::{GAME_ID_MAX, GAME_NAME_MAX, GameInfo, GameMsg};
use twinpad_core::input::InputMsg;

/// Puerto IPC del Servidor por defecto (SPEC-006/ADR-008).
pub const DEFAULT_IPC_PORT: u16 = 8421;
/// Puerto IPC local dedicado a fuentes PCM (CR-003/SPEC-006).
pub const DEFAULT_AUDIO_IPC_PORT: u16 = 8422;
/// Capacidad de cola de audio; los bloques viejos se descartan (CR-003).
const AUDIO_QUEUE_CAPACITY: usize = 8;

/// Timeout para el anuncio (el server debe aceptarlo al momento).
const ANNOUNCE_TIMEOUT: Duration = Duration::from_secs(10);

/// Sondeo de rechazo: el server no responde en el flujo feliz (§7) — solo
/// cierra el socket si el anuncio es inválido o duplicado (§9). Esperar este
/// lapso sin datos ni cierre confirma el registro.
const REJECT_PROBE: Duration = Duration::from_millis(500);

/// Error del SDK (§9: errores locales, sin códigos de sesión).
#[derive(Debug)]
pub enum SdkError {
    /// El server no está corriendo o no acepta conexiones en el puerto IPC.
    ServerUnreachable(std::io::Error),
    /// El server cerró la conexión: anuncio inválido o `game_id` duplicado.
    AnnouncementRejected,
    /// Los campos no cumplen SPEC-006 §4 (se valida antes de conectar).
    InvalidConfig,
}

/// Identificación del juego (SPEC-006 §4). Valídada al construir.
#[derive(Clone, Debug)]
pub struct GameConfig {
    /// 1–64 chars `[a-z0-9_.-]`, único entre juegos vivos.
    pub game_id: String,
    /// 1–128 chars legibles.
    pub game_name: String,
    /// Subconjunto de capacidades (`second_screen`, `audio`,
    /// `input_consumption`); vacío válido (§10).
    pub capabilities: Vec<String>,
}

impl GameConfig {
    /// Crea la config validando los constraints de §4.
    ///
    /// # Errors
    /// [`SdkError::InvalidConfig`] si algún campo está fuera de rango.
    pub fn new(
        game_id: impl Into<String>,
        game_name: impl Into<String>,
        capabilities: Vec<String>,
    ) -> Result<Self, SdkError> {
        let game_id = game_id.into();
        let game_name = game_name.into();
        let valid_id = !game_id.is_empty()
            && game_id.len() <= GAME_ID_MAX
            && game_id.chars().all(|c| {
                c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, '_' | '.' | '-')
            });
        if !valid_id || game_name.is_empty() || game_name.len() > GAME_NAME_MAX {
            return Err(SdkError::InvalidConfig);
        }
        for cap in &capabilities {
            if !twinpad_core::game::CAPABILITIES.contains(&cap.as_str()) {
                return Err(SdkError::InvalidConfig);
            }
        }
        Ok(Self {
            game_id,
            game_name,
            capabilities,
        })
    }

    fn info(&self) -> GameInfo {
        GameInfo {
            game_id: self.game_id.clone(),
            game_name: self.game_name.clone(),
            capabilities: self.capabilities.clone(),
        }
    }
}

/// Handle del juego anunciado: manténgalo vivo mientras el juego quiera
/// estar disponible. Soltarlo (o crashear) lo retira del server (R2).
pub struct TwinpadGame {
    stream: TcpStream,
    game_id: String,
    /// Número de frame publicado (AC-2): monotónico desde 0 por conexión.
    gfn: u64,
    audio: Option<AudioSourceHandle>,
    capability_audio: bool,
    input_snapshot: Arc<Mutex<Option<InputMsg>>>,
    input_reader: TcpStream,
}

#[derive(Default)]
struct AudioQueue {
    state: Mutex<AudioQueueState>,
    ready: Condvar,
}

#[derive(Default)]
struct AudioQueueState {
    chunks: VecDeque<Vec<u8>>,
    finished: bool,
}

impl AudioQueue {
    fn push(&self, chunk: Vec<u8>) {
        let Ok(mut state) = self.state.lock() else {
            return;
        };
        if state.finished {
            return;
        }
        if state.chunks.len() == AUDIO_QUEUE_CAPACITY {
            state.chunks.pop_front();
        }
        state.chunks.push_back(chunk);
        self.ready.notify_one();
    }

    fn finish(&self) {
        if let Ok(mut state) = self.state.lock() {
            state.finished = true;
            self.ready.notify_all();
        }
    }

    fn pop(&self) -> Option<Vec<u8>> {
        let mut state = self.state.lock().ok()?;
        loop {
            if let Some(chunk) = state.chunks.pop_front() {
                return Some(chunk);
            }
            if state.finished {
                return None;
            }
            state = self.ready.wait(state).ok()?;
        }
    }
}

struct AudioSourceHandle {
    queue: Arc<AudioQueue>,
    cancelled: Arc<std::sync::atomic::AtomicBool>,
    producer: Option<std::thread::JoinHandle<()>>,
    consumer: Option<std::thread::JoinHandle<()>>,
}

impl Drop for AudioSourceHandle {
    fn drop(&mut self) {
        self.cancelled
            .store(true, std::sync::atomic::Ordering::Release);
        self.queue.finish();
        if let Some(handle) = self.producer.take() {
            let _ = handle.join();
        }
        if let Some(handle) = self.consumer.take() {
            let _ = handle.join();
        }
    }
}

impl Drop for TwinpadGame {
    fn drop(&mut self) {
        let _ = self.input_reader.shutdown(std::net::Shutdown::Both);
    }
}

impl TwinpadGame {
    /// Anuncia el juego al Servidor PC (SPEC-006 §7 pasos 1–2).
    pub fn announce(config: GameConfig) -> Result<Self, SdkError> {
        Self::announce_on(("127.0.0.1", DEFAULT_IPC_PORT), config)
    }

    /// Anuncia el juego contra un endpoint IPC explícito (útil para pruebas).
    pub fn announce_on(
        endpoint: impl std::net::ToSocketAddrs,
        config: GameConfig,
    ) -> Result<Self, SdkError> {
        let mut stream = TcpStream::connect(endpoint).map_err(SdkError::ServerUnreachable)?;
        stream
            .set_write_timeout(Some(ANNOUNCE_TIMEOUT))
            .map_err(SdkError::ServerUnreachable)?;
        stream
            .set_read_timeout(Some(REJECT_PROBE))
            .map_err(SdkError::ServerUnreachable)?;
        let line = GameMsg::GameAnnounce {
            game: config.info(),
        }
        .encode()
        .map_err(|_| SdkError::InvalidConfig)?;
        stream
            .write_all(line.as_bytes())
            .and_then(|_| stream.write_all(b"\n"))
            .and_then(|_| stream.flush())
            .map_err(SdkError::ServerUnreachable)?;
        let mut probe = [0u8; 1];
        let mut reader = stream.try_clone().map_err(SdkError::ServerUnreachable)?;
        match reader.read(&mut probe) {
            Ok(0) | Ok(_) => Err(SdkError::AnnouncementRejected),
            Err(error)
                if error.kind() == std::io::ErrorKind::WouldBlock
                    || error.kind() == std::io::ErrorKind::TimedOut =>
            {
                stream
                    .set_read_timeout(None)
                    .map_err(SdkError::ServerUnreachable)?;
                let input_snapshot = Arc::new(Mutex::new(None));
                let input_reader = reader.try_clone().map_err(SdkError::ServerUnreachable)?;
                let thread_reader = input_reader
                    .try_clone()
                    .map_err(SdkError::ServerUnreachable)?;
                let shared_snapshot = Arc::clone(&input_snapshot);
                std::thread::spawn(move || read_input_snapshots(thread_reader, shared_snapshot));
                let capability_audio = config.capabilities.iter().any(|cap| cap == "audio");
                Ok(Self {
                    stream,
                    game_id: config.game_id,
                    gfn: 0,
                    audio: None,
                    capability_audio,
                    input_snapshot,
                    input_reader,
                })
            }
            Err(error) => Err(SdkError::ServerUnreachable(error)),
        }
    }

    /// Identificación del juego anunciado.
    #[must_use]
    pub fn game_id(&self) -> &str {
        &self.game_id
    }

    /// Snapshot completo más reciente de input, o `None` antes del primer reporte.
    /// El sondeo no bloquea y no conserva una cola de estados históricos.
    #[must_use]
    pub fn poll_input(&self) -> Option<InputMsg> {
        self.input_snapshot
            .lock()
            .ok()
            .and_then(|snapshot| snapshot.clone())
    }

    /// Inicia una fuente PCM por la conexión IPC dedicada (CR-003).
    /// El callback corre en hilo dedicado y la cola limita la memoria a ocho chunks.
    pub fn start_audio_source<F>(
        &mut self,
        rate: u32,
        channels: u8,
        mut source: F,
    ) -> Result<(), SdkError>
    where
        F: FnMut() -> Option<Vec<u8>> + Send + 'static,
    {
        if self.audio.is_some()
            || !self.capability_audio
            || !(twinpad_core::audio::MIN_RATE..=twinpad_core::audio::MAX_RATE).contains(&rate)
            || channels == 0
            || channels > twinpad_core::audio::MAX_CHANNELS
        {
            return Err(SdkError::InvalidConfig);
        }
        let mut socket = TcpStream::connect(("127.0.0.1", DEFAULT_AUDIO_IPC_PORT))
            .map_err(SdkError::ServerUnreachable)?;
        socket
            .set_write_timeout(Some(ANNOUNCE_TIMEOUT))
            .map_err(SdkError::ServerUnreachable)?;
        let start_line = twinpad_core::game_audio::GameAudioMsg::Start {
            game_id: self.game_id.clone(),
            rate,
            channels,
            sample_fmt: twinpad_core::audio::SAMPLE_FMT_S16LE.into(),
        }
        .encode()
        .map_err(|_| SdkError::InvalidConfig)?;
        socket
            .write_all(start_line.as_bytes())
            .and_then(|_| socket.write_all(b"\n"))
            .map_err(SdkError::ServerUnreachable)?;

        let queue = Arc::new(AudioQueue::default());
        let cancelled = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let producer_queue = Arc::clone(&queue);
        let producer_cancelled = Arc::clone(&cancelled);
        let producer = std::thread::spawn(move || {
            while !producer_cancelled.load(std::sync::atomic::Ordering::Acquire) {
                let Some(pcm) = source() else { break };
                if pcm.is_empty()
                    || pcm.len() > twinpad_core::audio::MAX_CHUNK_BYTES
                    || pcm.len() % 2 != 0
                {
                    break;
                }
                producer_queue.push(pcm);
            }
            producer_queue.finish();
        });
        let consumer_queue = Arc::clone(&queue);
        let game_id = self.game_id.clone();
        let consumer = std::thread::spawn(move || {
            let mut seq = 0u64;
            let origin = std::time::Instant::now();
            while let Some(pcm) = consumer_queue.pop() {
                use base64::Engine as _;
                let message = twinpad_core::game_audio::GameAudioMsg::Chunk {
                    game_id: game_id.clone(),
                    seq,
                    ts: origin.elapsed().as_millis() as u64,
                    data: base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(pcm),
                };
                seq = seq.wrapping_add(1);
                let Ok(line) = message.encode() else { break };
                if socket
                    .write_all(line.as_bytes())
                    .and_then(|_| socket.write_all(b"\n"))
                    .is_err()
                {
                    break;
                }
            }
            if let Ok(line) = (twinpad_core::game_audio::GameAudioMsg::Stop { game_id }).encode() {
                let _ = socket
                    .write_all(line.as_bytes())
                    .and_then(|_| socket.write_all(b"\n"));
            }
        });
        self.audio = Some(AudioSourceHandle {
            queue,
            cancelled,
            producer: Some(producer),
            consumer: Some(consumer),
        });
        Ok(())
    }

    /// Publica un Framebuffer de la Segunda pantalla del juego (REQ-006
    /// AC-2). `data` son `w×h×bpp` bytes crudos de `fmt`, fila mayor.
    ///
    /// El `gfn` lo asigna el SDK (monotónico desde 0 por conexión); el
    /// server re-empacará el frame con el `fn` de su stream hacia la app.
    ///
    /// # Errors
    /// [`SdkError::InvalidConfig`] si los parámetros no cumplen SPEC-003 §4
    /// (dimensiones o tamaño de `data`); [`SdkError::AnnouncementRejected`]
    /// si la conexión cayó (el juego dejó de estar disponible, §9).
    pub fn push_frame(
        &mut self,
        w: u16,
        h: u16,
        fmt: twinpad_core::stream::FrameFormat,
        data: &[u8],
    ) -> Result<(), SdkError> {
        // `jpeg` (CR-001) tiene tamaño libre; los formatos crudos deben
        // medir exactamente w×h×bpp.
        let size_ok = match fmt.bytes_per_pixel() {
            Some(bpp) => data.len() == usize::from(w) * usize::from(h) * bpp,
            None => !data.is_empty(),
        };
        if !size_ok {
            return Err(SdkError::InvalidConfig);
        }
        use base64::Engine as _;
        let encoded = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(data);
        self.gfn = self.gfn.wrapping_add(1);
        let line = GameMsg::GameFrame {
            gfn: self.gfn,
            w,
            h,
            fmt,
            data: encoded,
        }
        .encode()
        .map_err(|_| SdkError::InvalidConfig)?;
        self.stream
            .write_all(line.as_bytes())
            .and_then(|_| self.stream.write_all(b"\n"))
            .and_then(|_| self.stream.flush())
            .map_err(|_| SdkError::AnnouncementRejected)
    }
}

fn read_input_snapshots(stream: TcpStream, snapshot: Arc<Mutex<Option<InputMsg>>>) {
    let mut reader = std::io::BufReader::new(stream);
    let mut line = String::new();
    loop {
        line.clear();
        match std::io::BufRead::read_line(&mut reader, &mut line) {
            Ok(0) | Err(_) => break,
            Ok(_) => {
                if let Ok(message) = InputMsg::decode(line.trim_end()) {
                    if let Ok(mut current) = snapshot.lock() {
                        *current = Some(message);
                    }
                }
            }
        }
    }
}

// ENG §6: unwrap permitido solo en tests.
#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    // TEST-021 — verifies SPEC-006 (§4: validación local del anuncio).
    #[test]
    fn config_validates_constraints() {
        assert!(GameConfig::new("pacdemo", "Pac Demo", vec!["second_screen".into()]).is_ok());
        assert!(matches!(
            GameConfig::new("", "X", vec![]),
            Err(SdkError::InvalidConfig)
        ));
        assert!(matches!(
            GameConfig::new("MAYUSCULAS", "X", vec![]),
            Err(SdkError::InvalidConfig)
        ));
        assert!(matches!(
            GameConfig::new("ok", "", vec![]),
            Err(SdkError::InvalidConfig)
        ));
        assert!(matches!(
            GameConfig::new("ok", "X", vec!["no_existe".into()]),
            Err(SdkError::InvalidConfig)
        ));
    }

    // TEST-036 — verifies callback queue capacity, drop-old policy, and finish wakeup.
    #[test]
    fn audio_queue_drops_oldest_and_drains_before_finish() {
        let queue = AudioQueue::default();
        for value in 0..=AUDIO_QUEUE_CAPACITY {
            queue.push(vec![value as u8]);
        }
        queue.finish();
        for value in 1..=AUDIO_QUEUE_CAPACITY {
            assert_eq!(queue.pop(), Some(vec![value as u8]));
        }
        assert_eq!(queue.pop(), None);
    }

    #[test]
    fn announce_rejected_when_no_server() {
        // Puerto sin listener: nadie escucha.
        let cfg = GameConfig::new("pacdemo", "Pac Demo", vec![]).unwrap();
        let result = TwinpadGame::announce_on(("127.0.0.1", 1), cfg);
        assert!(matches!(result, Err(SdkError::ServerUnreachable(_))));
    }
}
