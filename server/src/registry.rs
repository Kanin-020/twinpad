//! Registro de sesiones activas del Servidor PC — SPEC-001 §8.2 (la sesión
//! autenticada persiste tras el Handshake) y TEST-007/008. Owner: `server/`
//! (ARCH §5); la persistencia cifrada en disco es posterior.

use std::collections::HashMap;
use std::net::TcpStream;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};

use std::sync::Arc;
use twinpad_core::resume::RetainedSession;
use twinpad_core::{SessionId, SessionKey};

impl crate::session::ControlRetention for SessionRegistry {
    fn take_retained(&self, session_id: &SessionId, now_ms: u64) -> Option<RetainedSession> {
        let taken = lock_or_reset(&self.retained).remove(session_id);
        if let Some(retained) = taken {
            if !retained.retention_alive(now_ms) {
                // La ventana murió entre retener y el intento: el resume
                // será rechazado y el estado de media se destruye ya.
                self.destroy_media_state(session_id);
            }
            return Some(retained);
        }
        // CR-094: dispositivo registrado en disco → reanudación sin límite de 60 s.
        let devices = self.devices.as_ref()?;
        let (sid, sk) = devices
            .load()
            .ok()?
            .into_iter()
            .find(|(sid, _)| sid == session_id)?;
        Some(RetainedSession {
            session_id: sid,
            session_key: sk,
            retained_since_ms: now_ms,
        })
    }
}

type StoredMediaCredential = (Arc<SessionKey>, Arc<AtomicBool>);

/// Toma el lock; envenenado → degrada a mapa vacío (seguro: las sesiones
/// "perdidas" se cierran al caer sus streams). Genérico: sirve igual para
/// streams, credenciales, selecciones y retenciones (CR-056).
fn lock_or_reset<'a, V>(
    mutex: &'a Mutex<HashMap<SessionId, V>>,
) -> std::sync::MutexGuard<'a, HashMap<SessionId, V>> {
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
    keys: Mutex<HashMap<SessionId, StoredMediaCredential>>,
    selected_games: Mutex<HashMap<SessionId, String>>,
    media_games: Mutex<HashMap<SessionId, String>>,
    /// CR-056 (SPEC-001 §8.2): ligaduras retenidas tras la caída del socket
    /// de control, con su ventana de 60 000 ms (estado de sesión S4).
    retained: Mutex<HashMap<SessionId, RetainedSession>>,
    /// CR-094: dispositivos emparejados persistidos en disco (carpeta de datos).
    devices: Option<crate::device_store::DeviceStore>,
}

impl SessionRegistry {
    /// Registro vacío.
    pub fn new() -> Self {
        Self::default()
    }

    /// CR-094: registro con dispositivos persistidos en `dir`.
    pub fn with_device_store(dir: &std::path::Path) -> Self {
        Self {
            devices: Some(crate::device_store::DeviceStore::new(dir)),
            ..Self::default()
        }
    }

    /// CR-094: revoca un dispositivo: lo quita del almacén en disco y cierra su sesión viva.
    /// `false` si el id no es válido o no estaba registrado.
    pub fn revoke_device(&self, session_id_b64: &str) -> bool {
        let Ok(session_id) = twinpad_core::resume::session_id_from_b64(session_id_b64) else {
            return false;
        };
        let Some(devices) = self.devices.as_ref() else {
            return false;
        };
        let mut all = devices.load().unwrap_or_default();
        let before = all.len();
        all.retain(|(sid, _)| *sid != session_id);
        if all.len() == before {
            return false;
        }
        if let Err(error) = devices.save(&all) {
            eprintln!("Twinpad: no se pudo revocar el dispositivo: {error}");
            return false;
        }
        self.remove(&session_id);
        true
    }

    /// CR-094: guarda el dispositivo recién emparejado (sin el token del QR).
    pub fn remember_device(&self, session_id: SessionId, session_key: SessionKey) {
        let Some(devices) = self.devices.as_ref() else {
            return;
        };
        let mut all = devices.load().unwrap_or_default();
        all.retain(|(sid, _)| *sid != session_id);
        all.push((session_id, session_key));
        if let Err(error) = devices.save(&all) {
            eprintln!("Twinpad: no se pudo guardar el dispositivo: {error}");
        }
    }

    /// Registra una sesión establecida y retiene su stream (medio de
    /// escritura; la lectura la sigue haciendo el hilo del cliente).
    pub fn insert(&self, session: ActiveSession, stream: TcpStream) -> bool {
        let previous = lock_or_reset(&self.streams).insert(session.session_id, stream);
        // HB7 (SPEC-001 §4.3): el socket de control anterior de esta sesión
        // deja de escribir (sus pings) al aceptarse la reanudación. El
        // shutdown actúa sobre el socket subyacente, así que también corta
        // el emisor de ping y el lector de la conexión vieja.
        if let Some(old) = &previous {
            let _ = old.shutdown(std::net::Shutdown::Both);
        }
        let _ = session; // los campos quedan disponibles para UI/logs futuros
        previous.is_none()
    }

    /// CR-056: registra la credencial media activa de la sesión; al
    /// reemplazar una viva, apaga la bandera anterior (el cableado viejo
    /// del relay deja de validar).
    pub fn insert_active_key(&self, session_id: SessionId, key: SessionKey) {
        let mut keys = self
            .keys
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if let Some((_, active)) =
            keys.insert(session_id, (Arc::new(key), Arc::new(AtomicBool::new(true))))
        {
            active.store(false, Ordering::Release);
        }
    }

    /// Devuelve la clave y el estado de vida del Pairing para el listener media.
    pub fn media_credential(
        &self,
        session_id: &SessionId,
    ) -> Option<(Arc<SessionKey>, Arc<AtomicBool>)> {
        if !self.contains(session_id) && !self.retained_contains(session_id) {
            return None;
        }
        self.keys
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .get(session_id)
            .cloned()
    }

    /// Selección de Plugin confirmada para esta sesión (SPEC-006 §6).
    pub fn set_selected_game(&self, session_id: SessionId, game_id: Option<String>) {
        let mut selected_games = self
            .selected_games
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let mut media_games = self
            .media_games
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        match game_id {
            Some(game_id) => {
                selected_games.insert(session_id, game_id);
                media_games.remove(&session_id);
            }
            None => {
                selected_games.remove(&session_id);
                media_games.remove(&session_id);
            }
        }
    }

    /// Plugin actualmente seleccionado por esta sesión, si lo hay.
    pub fn selected_game(&self, session_id: &SessionId) -> Option<String> {
        self.selected_games
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .get(session_id)
            .cloned()
    }

    /// Fuente H.264 cuya configuración `stream_start` ya fue anunciada.
    pub fn set_media_game(&self, session_id: SessionId, game_id: Option<String>) {
        let selected = self.selected_game(&session_id);
        let mut media_games = self
            .media_games
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        match game_id {
            Some(active_game) if selected.as_deref() == Some(active_game.as_str()) => {
                media_games.insert(session_id, active_game);
            }
            _ => {
                media_games.remove(&session_id);
            }
        }
    }

    pub fn media_game(&self, session_id: &SessionId) -> Option<String> {
        self.media_games
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .get(session_id)
            .cloned()
    }

    /// CR-056 (SPEC-001 §8.2): mueve la sesión a retención tras la caída del
    /// socket de control en vez de destruirla (TEST-007 la destruía). Mantiene
    /// session_key y el estado de media; solo libera el stream muerto y llena
    /// el mapa de retención con ventana de `twinpad_core::resume::RETENTION_MS`
    /// (estado S4).
    pub fn retain(&self, session_id: &SessionId, session_key: &SessionKey, now_ms: u64) {
        lock_or_reset(&self.streams).remove(session_id); // cierra el stream muerto
        lock_or_reset(&self.retained).insert(
            *session_id,
            RetainedSession {
                session_id: *session_id,
                session_key: *session_key,
                retained_since_ms: now_ms,
            },
        );
    }

    /// CR-056: destruye las ligaduras cuya retención venció (requerirá QR).
    /// Devuelve cuántas expiraron (para el diagnóstico del server).
    pub fn sweep_retained(&self, now_ms: u64) -> usize {
        let expired: Vec<SessionId> = {
            let mut map = lock_or_reset(&self.retained);
            let expired: Vec<SessionId> = map
                .values()
                .filter(|r| !r.retention_alive(now_ms))
                .map(|r| r.session_id)
                .collect();
            for sid in &expired {
                map.remove(sid);
            }
            expired
        };
        for sid in &expired {
            self.destroy_media_state(sid);
        }
        expired.len()
    }

    /// Destruye credencial y estado de media (el “remove” sin stream).
    fn destroy_media_state(&self, session_id: &SessionId) {
        if let Some((_, active)) = self
            .keys
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(session_id)
        {
            active.store(false, Ordering::Release);
        }
        self.selected_games
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(session_id);
        self.media_games
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(session_id);
        lock_or_reset(&self.retained).remove(session_id);
    }

    /// ¿Hay ligadura retenida viva para este id? (canal media en S4).
    pub fn retained_contains(&self, session_id: &SessionId) -> bool {
        lock_or_reset(&self.retained).contains_key(session_id)
    }

    /// Ligadura retenida (sin remover) — para el diagnóstico y las pruebas.
    pub fn retained(&self, session_id: &SessionId) -> Option<RetainedSession> {
        lock_or_reset(&self.retained).get(session_id).cloned()
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
        if let Some((_, active)) = self
            .keys
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(session_id)
        {
            active.store(false, Ordering::Release);
        }
        self.selected_games
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(session_id);
        self.media_games
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(session_id);
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
