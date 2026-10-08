//! Registro de sesiones activas del Servidor PC — SPEC-001 §8.2 (la sesión
//! autenticada persiste tras el Handshake) y TEST-007/008. Owner: `server/`
//! (ARCH §5); la persistencia cifrada en disco es posterior.

use std::collections::HashMap;
use std::net::TcpStream;
use std::sync::Mutex;

use twinpad_core::SessionId;

/// Toma el lock; envenenado → degrada a registro vacío (seguro: las
/// sesiones "perdidas" se cierran al caer sus streams).
fn lock_or_reset<'a>(
    mutex: &'a Mutex<HashMap<SessionId, TcpStream>>,
) -> std::sync::MutexGuard<'a, HashMap<SessionId, TcpStream>> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Una sesión autenticada: su stream TCP queda retenido por el server.
#[derive(Clone, Debug)]
pub struct ActiveSession {
    pub session_id: SessionId,
    /// Duplicado legible del id para logs/UI.
    pub session_id_b64: String,
}

/// Registro en memoria de sesiones establecidas. Cada stream retenido se
/// mantiene abierto (sin FIN) mientras la sesión exista — conexión
/// persistente. `Duplicate` reemplaza una sesión previa con el mismo id.
#[derive(Default)]
pub struct SessionRegistry {
    streams: Mutex<HashMap<SessionId, TcpStream>>,
}

impl SessionRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    /// Registra una sesión establecida y retiene su stream (medio de
    /// escritura; la lectura la sigue haciendo el hilo del cliente).
    pub fn insert(&self, session: ActiveSession, stream: TcpStream) -> bool {
        let was_new = lock_or_reset(&self.streams)
            .insert(session.session_id, stream)
            .is_none();
        let _ = session; // los campos quedan disponibles para UI/logs futuros
        was_new
    }

    /// ¿Existe una sesión activa con este id? (TEST-007: reanudación §8.2.)
    pub fn contains(&self, session_id: &SessionId) -> bool {
        lock_or_reset(&self.streams).contains_key(session_id)
    }

    /// Envía una línea NDJSON a **todas** las sesiones activas (p. ej. la
    /// lista de juegos disponibles de SPEC-006 §5). Ignora sesiones cuyo
    /// envío falla; no reporta errores parciales.
    pub fn broadcast_line(&self, line: &str) {
        use std::io::Write;
        for stream in lock_or_reset(&self.streams).values_mut() {
            let _ = stream
                .write_all(line.as_bytes())
                .and_then(|_| stream.write_all(b"\n"))
                .and_then(|_| stream.flush());
        }
    }

    /// Envía una línea NDJSON a la sesión indicada (canal del server → app).
    /// Devuelve `false` si la sesión no existe o el envío falla.
    pub fn send_line(&self, session_id: &SessionId, line: &str) -> bool {
        let mut map = lock_or_reset(&self.streams);
        let Some(stream) = map.get_mut(session_id) else {
            return false;
        };
        use std::io::Write;
        stream
            .write_all(line.as_bytes())
            .and_then(|_| stream.write_all(b"\n"))
            .and_then(|_| stream.flush())
            .is_ok()
    }

    /// Cierra y elimina una sesión (TEST-008: revocación §8.3; el stream
    /// cae y la app ve el corte).
    pub fn remove(&self, session_id: &SessionId) -> bool {
        lock_or_reset(&self.streams).remove(session_id).is_some()
    }

    /// Sesiones activas (para UI/logs).
    pub fn len(&self) -> usize {
        lock_or_reset(&self.streams).len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
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

    fn session(id_byte: u8) -> (SessionId, ActiveSession) {
        let sid = SessionId([id_byte; 16]);
        (
            sid,
            ActiveSession {
                session_id: sid,
                session_id_b64: String::new(),
            },
        )
    }

    #[test]
    fn inserted_session_is_retained_and_queryable() {
        let registry = SessionRegistry::new();
        let (sid, session) = session(1);
        let (server_side, _client) = loopback_pair();
        assert!(registry.insert(session, server_side));
        assert!(registry.contains(&sid));
        assert_eq!(registry.len(), 1);
    }

    #[test]
    fn duplicate_session_id_replaces_previous() {
        let registry = SessionRegistry::new();
        let (_sid, session) = session(2);
        let (first, _c1) = loopback_pair();
        let (second, _c2) = loopback_pair();
        assert!(registry.insert(session.clone(), first));
        assert!(!registry.insert(session, second)); // reemplazo
        assert_eq!(registry.len(), 1);
    }

    #[test]
    fn send_line_delivers_ndjson_to_the_client() {
        let registry = SessionRegistry::new();
        let (sid, session) = session(3);
        let (server_side, mut client) = loopback_pair();
        registry.insert(session, server_side);
        assert!(registry.send_line(&sid, "{\"ping\":1}"));
        use std::io::Read;
        let mut buf = [0u8; 11]; // {"ping":1} = 10 chars + \n
        client.read_exact(&mut buf).unwrap();
        assert_eq!(&buf, b"{\"ping\":1}\n");
    }

    #[test]
    fn remove_closes_the_session() {
        let registry = SessionRegistry::new();
        let (sid, session) = session(4);
        let (server_side, _client) = loopback_pair();
        registry.insert(session, server_side);
        assert!(registry.remove(&sid));
        assert!(!registry.contains(&sid));
        assert!(!registry.remove(&sid));
    }

    #[test]
    fn send_to_unknown_session_fails_cleanly() {
        let registry = SessionRegistry::new();
        let (sid, _) = session(5);
        assert!(!registry.send_line(&sid, "x"));
    }

    #[test]
    fn broadcast_reaches_every_session() {
        let registry = SessionRegistry::new();
        let (sid_a, sa) = session(6);
        let (sid_b, sb) = session(7);
        let (server_a, mut client_a) = loopback_pair();
        let (server_b, mut client_b) = loopback_pair();
        registry.insert(sa, server_a);
        registry.insert(sb, server_b);
        registry.broadcast_line("{\"ping\":1}");
        use std::io::Read;
        let mut buf_a = [0u8; 11];
        let mut buf_b = [0u8; 11];
        client_a.read_exact(&mut buf_a).unwrap();
        client_b.read_exact(&mut buf_b).unwrap();
        assert_eq!(&buf_a, b"{\"ping\":1}\n");
        assert_eq!(&buf_b, b"{\"ping\":1}\n");
        let _ = (sid_a, sid_b);
    }
}
