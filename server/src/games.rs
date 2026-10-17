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
    /// Framebuffer crudo (sin base64), `w×h×bpp` bytes.
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

    /// Último frame del juego vivo indicado (AC-2), o `None` si no hay juego
    /// vivo con ese id o aún no publicó frames (§10).
    pub fn latest_frame(&self, game_id: &str) -> Option<GameFrame> {
        lock_or_reset(&self.games)
            .get(game_id)
            .and_then(|e| e.last_frame.clone())
    }

    /// Fuente preferente del stream de SPEC-003: el primer juego vivo (por
    /// `game_id`, determinista) que haya publicado frames. `None` si ninguno
    /// (el server usa su fuente de escritorio/demo, SPEC-003 §17).
    pub fn preferred_source(&self) -> Option<(String, GameFrame)> {
        lock_or_reset(&self.games)
            .iter()
            .find_map(|(id, entry)| entry.last_frame.clone().map(|frame| (id.clone(), frame)))
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
    for stream in listener.incoming() {
        let Ok(stream) = stream else { continue };
        let registry = std::sync::Arc::clone(&registry);
        let publish = std::sync::Arc::clone(&publish);
        std::thread::spawn(move || handle_game_connection(stream, &registry, publish.as_ref()));
    }
}

/// Ciclo de vida de una conexión de juego: anuncio → registro → espera → baja.
fn handle_game_connection(stream: TcpStream, registry: &GameRegistry, publish: &Publisher) {
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
                data,
            }) => match decode_frame_data(&data, w, h, fmt) {
                Ok(pixels) => {
                    registry.store_frame(
                        &game.game_id,
                        GameFrame {
                            gfn,
                            w,
                            h,
                            fmt,
                            data: pixels.into(),
                        },
                    );
                }
                Err(_) => println!("game_frame inválido de '{}' descartado.", game.game_id),
            },
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
