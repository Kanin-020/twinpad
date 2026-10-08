//! Detección de juegos con SDK Twinpad — SPEC-006 (REQ-006 AC-1) + ADR-008.
//!
//! El canal IPC es TCP localhost NDJSON (ADR-008): la **conexión viva es el
//! anuncio vivo** — al caer (cierre o crash del juego), el juego deja de estar
//! disponible (§6 D1→D0, R2). `GameRegistry` mantiene la lista exacta de los
//! anuncios vivos (invariante §14) y rechaza `game_id` duplicados (§9).

use std::collections::BTreeMap;
use std::io::{BufRead, BufReader};
use std::net::{TcpListener, TcpStream};
use std::sync::Mutex;
use std::time::Duration;

use twinpad_core::game::{GameInfo, GameMsg};

/// Timeout para recibir el anuncio tras conectar (§9: el SDK anuncia al
/// arrancar; una conexión muda no queda viva).
pub const ANNOUNCE_TIMEOUT: Duration = Duration::from_secs(10);

/// Error de registro de un anuncio (§9).
#[derive(Debug)]
pub enum RegisterError {
    /// Ya hay un juego vivo con el mismo `game_id`.
    Duplicate,
}

struct GameEntry {
    info: GameInfo,
    _stream: TcpStream,
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
            },
        );
        Ok(())
    }

    /// Retira un juego (cayó su conexión); `true` si estaba registrado.
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
    loop {
        if read_line(&mut reader).is_none() {
            if registry.unregister(&game.game_id) {
                println!(
                    "Juego compatible fuera de línea: {} ({}).",
                    game.game_name, game.game_id
                );
                publish(&registry.list());
            }
            return;
        }
        // Tráfico adicional del juego (frames, etc.) llega en iteraciones
        // posteriores (AC-2/AC-3); v1 solo mantiene viva la conexión.
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
