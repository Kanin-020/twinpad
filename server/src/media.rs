//! Listener TCP media autenticado y relay opaco H.264 (SPEC-003 v1.4.0).

use std::collections::BTreeMap;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;
#[cfg(test)]
use std::sync::atomic::AtomicU64;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Condvar, Mutex};
use std::time::{Duration, Instant};

use twinpad_core::bitrate::{BitrateLadder, CongestionObserver, WindowStats};
use twinpad_core::media_crypto::{
    MAX_MEDIA_RECORD_BYTES, client_proof, derive_media_key, open_record, seal_record, server_proof,
};
use twinpad_core::{SessionId, SessionKey};

/// Puerto del canal media autenticado; reexport del core (§4.2, CR-051).
pub const MEDIA_PORT: u16 = twinpad_core::media_crypto::MEDIA_PORT;
const MEDIA_HANDSHAKE_BYTES: usize = 16 + 32 + 32;
/// CR-051: SNDBUF del socket media acotado (misma técnica anti-lag de la
/// sesión NDJSON en main.rs): escribir AU antiguas al buffer del kernel
/// reventaría el relay en el tramo hacia el teléfono.
const MEDIA_STREAM_SND_BUF_BYTES: usize = 4 * 1024 * 1024;
/// CR-051: tipo del enganche post-handshake; el server lo conecta a
/// `send_cemu_video_force_idr` para que el teléfono (re)conectado reciba
/// IDR inmediata (SPEC-003 §8.2) sin esperar la cadencia periódica de
/// ~1 s del encoder.
pub type MediaJoinedHook = dyn Fn() -> bool + Send + Sync;
/// CR-058 (SPEC-003 §4.3): enganche del cambio de rung de la escalera; el
/// server lo conecta al reenvío de `video_config` (CR-027) + IDR (CR-051).
pub type MediaBitrateApply = dyn Fn(u32) -> bool + Send + Sync;

/// CR-083 (pieza A): pedido de IDR con reintentos y desenlace visible.
/// El hook de CR-051 puede fallar (writer del juego no listo en el
/// instante del join); el código previo ignoraba el resultado y logeaba
/// igualmente «IDR solicitado». Backoff corto (0/250/500 ms) alineado con
/// el banco: la sesión Macedonia siega un IDR que nunca llega.
pub fn idr_with_retry(hook: &Arc<MediaJoinedHook>) -> bool {
    for attempt in 0..3_u32 {
        if attempt > 0 {
            std::thread::sleep(Duration::from_millis(250));
        }
        if hook() {
            if attempt > 0 {
                println!("media IDR: ok tras reintento {} (CR-083)", attempt + 1);
            }
            return true;
        }
    }
    println!("media IDR falló tras 3 intentos (sin retransmisión de IDR)");
    false
}

/// CR-058 (SPEC-003 §4.3): ámbito global de la escalera de bitrate del
/// encoder compartido. Cada sesión de media observa su propia ventana P/D
/// (descartes latest-frame-wins) y la ingiere aquí; el rung es global y el
/// cambio pendiente dispara `apply(effective_kbps)` (mismo mecanismo que
/// CR-027/CR-030; el Android nunca solicita degradación, R5 vigente).
pub struct CongestionScope {
    now_ms: Box<dyn Fn() -> u64 + Send + Sync>,
    configured_kbps: Box<dyn Fn() -> u32 + Send + Sync>,
    ladder: Mutex<BitrateLadder>,
    apply: Arc<MediaBitrateApply>,
}

impl CongestionScope {
    /// Ámbito nuevo: reloj y bitrate configurado inyectados; escalera en
    /// rung 0 (sin degradación).
    pub fn new(
        now_ms: Box<dyn Fn() -> u64 + Send + Sync>,
        configured_kbps: Box<dyn Fn() -> u32 + Send + Sync>,
        apply: Arc<MediaBitrateApply>,
    ) -> Self {
        Self {
            now_ms,
            configured_kbps,
            ladder: Mutex::new(BitrateLadder::new()),
            apply,
        }
    }

    /// Reloj vigente en ms (el mismo dominio que los observadores). Método
    /// público para que el caller observe con ticks consistentes.
    pub fn now_ms(&self) -> u64 {
        (self.now_ms)()
    }

    /// kbps vigentes tras la degradación de la escalera (CR-058 §4.3).
    pub fn effective_kbps(&self) -> u32 {
        let configured = (self.configured_kbps)();
        self.ladder
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .effective_kbps(configured)
    }

    /// Rung actual (0 = sin degradación). Diagnóstico/integraciones.
    pub fn rung(&self) -> u32 {
        self.ladder
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .rung()
    }

    /// Ingesta de una ventana cerrada del relay de una sesión (§4.3).
    pub fn ingest_window(&self, stats: WindowStats) {
        let now = (self.now_ms)();
        let configured = (self.configured_kbps)();
        let mut ladder = self
            .ladder
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        ladder.on_window(stats, now);
        if ladder.take_change_pending() {
            // Nunca supera el bitrate del usuario (§8.4): la escalera parte
            // de `configured`; el caller reenvía video_config + IDR.
            let apply = Arc::clone(&self.apply);
            let effective = ladder.effective_kbps(configured);
            drop(ladder); // evita mantener la escalera bloqueada durante el apply
            let _ = apply(effective);
        }
    }
}
/// CR-051: tope de espera en el hub; acota la reevaluación de estado
/// externo al hub (selección/par media vencidos) sin sondeo activo.
const HUB_WAIT_TIMEOUT: Duration = Duration::from_millis(50);

/// Unidad de acceso (AU) del canal binario: `fn`, marca de captura,
/// bandera IDR y payload Annex-B (CR-051/CR-062).
#[derive(Clone, Debug)]
pub struct AccessUnit {
    pub fn_: u64,
    pub timestamp_us: u64,
    pub idr: bool,
    pub annex_b: Arc<[u8]>,
}

/// Fuente viva de media: juego publicado por el SDK, dimensiones del
/// `stream_start` vigente y la AU más reciente de esa fuente.
#[derive(Clone, Debug)]
pub struct MediaSource {
    pub game_id: String,
    pub width: u16,
    pub height: u16,
    pub latest: AccessUnit,
}

/// CR-051: el hub despierta a los relays al publicar/caducar una fuente
/// (Condvar) en lugar de que cada relay sondee con `sleep`. Predicado y
/// bloqueo comparten exclusión, sin carrera de notificación perdida.
#[derive(Default)]
pub struct MediaHub {
    sources: Mutex<BTreeMap<String, MediaSource>>,
    changed: Condvar,
}

impl MediaHub {
    /// Publica o actualiza la fuente del juego y despierta a los relays
    /// que esperan una AU reenviable (CR-051).
    pub fn publish(&self, source: MediaSource) {
        if let Ok(mut sources) = self.sources.lock() {
            sources.insert(source.game_id.clone(), source);
            self.changed.notify_all();
        }
    }

    /// Retira la fuente del juego desaparecido (§14: sin plugin vivo no
    /// hay contenido).
    pub fn clear_game(&self, game_id: &str) {
        if let Ok(mut sources) = self.sources.lock() {
            sources.remove(game_id);
            self.changed.notify_all();
        }
    }

    /// Espera hasta que la fuente `game_id` tenga una AU que satisfaga
    /// `forwardable` (o expira el timeout). Evalúa el predicado bajo el
    /// mismo lock que notifica `publish`, de modo que ninguna AU vale
    /// cero. `None` solo al agotar el timeout; el caller repite sus
    /// lookups externos (selección/pair Activados).
    pub fn wait_for_forwardable(
        &self,
        game_id: &str,
        forwardable: impl Fn(&AccessUnit) -> bool,
        timeout: Duration,
    ) -> Option<MediaSource> {
        let mut guard = self
            .sources
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let deadline = Instant::now() + timeout;
        loop {
            if let Some(source) = guard
                .get(game_id)
                .filter(|source| forwardable(&source.latest))
                .cloned()
            {
                return Some(source);
            }
            let now = Instant::now();
            if now >= deadline {
                return None;
            }
            let Ok((next, result)) = self.changed.wait_timeout(guard, deadline - now) else {
                return None;
            };
            if result.timed_out() {
                return None;
            }
            guard = next;
        }
    }

    /// Espera cualquier cambio del hub hasta `timeout`: despierta con
    /// publish/clear_game aunque no haya fuente para el juego consultado.
    pub fn wait_for_change(&self, timeout: Duration) {
        let guard = self
            .sources
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let _ = self.changed.wait_timeout(guard, timeout);
    }
}

/// Socket listo para streaming: TCP_NODELAY + SNDBUF acotado (CR-051:
/// mismo anti-lag aplicado por `StreamSink::prepare_for_streaming` a la
/// sesión NDJSON, ahora al canal binario media y a las conexiones de la
/// respuesta).
fn prepare_stream_socket(stream: &TcpStream) {
    use socket2::SockRef;
    let sock = SockRef::from(stream);
    let _ = sock.set_tcp_nodelay(true);
    let _ = sock.set_send_buffer_size(MEDIA_STREAM_SND_BUF_BYTES);
}

/// Credencial media: clave de sesión compartida y bandera de vigencia.
pub type MediaCredential = (Arc<SessionKey>, Arc<AtomicBool>);

/// Resuelve la credencial de una sesión al autenticar el handshake (§4.2).
pub type MediaCredentialLookup = dyn Fn(&SessionId) -> Option<MediaCredential> + Send + Sync;

/// Resuelve el juego seleccionado por sesión (§8.2).
pub type SelectedGameLookup = dyn Fn(&SessionId) -> Option<String> + Send + Sync;

/// Resuelve el juego con canal media activo (CR-051/CR-052) por sesión.
pub type MediaGameLookup = dyn Fn(&SessionId) -> Option<String> + Send + Sync;

/// CR-062 (SPEC-003 §4.4): enganche al cerrar el handshake TCP §4.2
/// válido: devuelve la propiedad del relay a TCP (recencia del dueño).
pub type TcpMediaJoined = dyn Fn(&SessionId) + Send + Sync;

/// Dependencias compartidas de los canales media (TCP §4.2 y UDP §4.4).
pub struct MediaDeps {
    pub keys: Arc<MediaCredentialLookup>,
    pub selected: Arc<SelectedGameLookup>,
    pub media_game: Arc<MediaGameLookup>,
    pub on_media_joined: Arc<MediaJoinedHook>,
    pub congestion: Arc<CongestionScope>,
    pub tcp_media_joined: Arc<TcpMediaJoined>,
}

pub fn run_media_listener(listener: TcpListener, hub: Arc<MediaHub>, deps: Arc<MediaDeps>) {
    for incoming in listener.incoming() {
        let Ok(stream) = incoming else { continue };
        let hub = Arc::clone(&hub);
        let deps = Arc::clone(&deps);
        std::thread::spawn(move || handle_media_client(stream, hub, deps));
    }
}

fn handle_media_client(mut stream: TcpStream, hub: Arc<MediaHub>, deps: Arc<MediaDeps>) {
    prepare_stream_socket(&stream);
    let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(5)));
    let mut request = [0_u8; MEDIA_HANDSHAKE_BYTES];
    if stream.read_exact(&mut request).is_err() {
        return;
    }
    let mut session_bytes = [0_u8; 16];
    session_bytes.copy_from_slice(&request[..16]);
    let session_id = SessionId(session_bytes);
    let nonce_c: [u8; 32] = match request[16..48].try_into() {
        Ok(value) => value,
        Err(_) => return,
    };
    let proof: [u8; 32] = match request[48..].try_into() {
        Ok(value) => value,
        Err(_) => return,
    };
    let Some((key, pair_active)) = (deps.keys)(&session_id) else {
        return;
    };
    let expected = client_proof(&key.0, &session_id.0, &nonce_c);
    if !constant_time_eq(&expected, &proof) || !pair_active.load(Ordering::Acquire) {
        return;
    }

    let mut nonce_s = [0_u8; 32];
    if getrandom::fill(&mut nonce_s).is_err() {
        return;
    }
    let response_proof = server_proof(&key.0, &session_id.0, &nonce_c, &nonce_s);
    if stream
        .write_all(&nonce_s)
        .and_then(|_| stream.write_all(&response_proof))
        .is_err()
    {
        return;
    }
    let media_key = derive_media_key(&key.0, &nonce_c, &nonce_s);
    // CR-062 (SPEC-003 §4.4): el handshake TCP válido devuelve la
    // propiedad del relay a TCP (el dueño UDP vigente deja de enviar).
    (deps.tcp_media_joined)(&session_id);
    // CR-051: SPEC-003 §8.2 — el servidor solicita IDR para el nuevo
    // suscriptor al (re)entrar el canal media; el plugin la emite en el
    // siguiente frame en lugar de esperar la cadencia periódica.
    // CR-083 (pieza A): reintentos con desenlace visible — sin IDR el
    // gate §14 del decoder jamás abre y la sesión muere a 0 fps.
    let idr_ok = idr_with_retry(&deps.on_media_joined);
    println!(
        "tcp media join sid-ok: IDR {}",
        if idr_ok {
            "solicitada"
        } else {
            "falló: sin retransmisión"
        }
    );
    let mut counter = 0_u64;
    let mut current_game = None::<String>;
    let mut last_fn = None;
    let mut last_timestamp_us = None;
    // CR-058 (SPEC-003 §4.3): ventana P/D de esta sesión; al cerrar la
    // ventana móvil se ingiere en la escalera global (encoder compartido).
    let mut congestion_window = CongestionObserver::new(deps.congestion.now_ms());

    while pair_active.load(Ordering::Acquire) {
        let Some(game_id) = (deps.selected)(&session_id) else {
            current_game = None;
            last_fn = None;
            last_timestamp_us = None;
            // CR-051: la espera despierta con cualquier publicar/caducar;
            // el timeout acota la reevaluación de la selección.
            hub.wait_for_change(Duration::from_millis(5));
            continue;
        };
        if current_game.as_deref() != Some(game_id.as_str()) {
            current_game = Some(game_id.clone());
            last_fn = None;
            last_timestamp_us = None;
        }
        if (deps.media_game)(&session_id).as_deref() != Some(game_id.as_str()) {
            hub.wait_for_change(Duration::from_millis(2));
            continue;
        }
        // CR-051: una sola espera resuelve "AU nueva (o IDR si es el
        // primer envío)"; antes eran sondeos con sleep de 1–2 ms que
        // reconsultaban el casi todo loop.
        let forwardable = |au: &AccessUnit| match last_fn {
            None => au.idr,
            Some(last) => au.fn_ > last && au.timestamp_us > last_timestamp_us.unwrap_or(0),
        };
        let Some(source) = hub.wait_for_forwardable(&game_id, forwardable, HUB_WAIT_TIMEOUT) else {
            continue;
        };
        if source.width < 16
            || source.width > 3840
            || source.height < 16
            || source.height > 2160
            || source.latest.annex_b.is_empty()
        {
            return;
        }
        let mut plaintext = Vec::with_capacity(17 + source.latest.annex_b.len());
        plaintext.extend_from_slice(&source.latest.fn_.to_be_bytes());
        plaintext.extend_from_slice(&source.latest.timestamp_us.to_be_bytes());
        plaintext.push(u8::from(source.latest.idr));
        plaintext.extend_from_slice(&source.latest.annex_b);
        if plaintext.len() + 24 > MAX_MEDIA_RECORD_BYTES {
            return;
        }
        let Ok(record) = seal_record(&media_key, counter, &plaintext) else {
            return;
        };
        if stream.write_all(&record).is_err() {
            return;
        }
        counter = match counter.checked_add(1) {
            Some(value) => value,
            None => return,
        };
        // CR-058 (SPEC-003 §4.3): el hueco entre AUs reenviadas son los
        // descartes latest-frame-wins; observados antes de actualizar last_fn.
        let prev_fn = last_fn;
        last_fn = Some(source.latest.fn_);
        last_timestamp_us = Some(source.latest.timestamp_us);
        congestion_window.observe(prev_fn, source.latest.fn_);
        if let Some(stats) = congestion_window.rollover(deps.congestion.now_ms()) {
            deps.congestion.ingest_window(stats);
        }
        // CR-029: sin pacing por fps; el bucle vuelve a esperar en el hub
        // (Condvar) hasta la AU siguiente y retransmite en cuanto llega.
    }
}

pub fn validate_client_record(key: &[u8; 32], expected_counter: u64, record: &[u8]) -> bool {
    open_record(key, expected_counter, record).is_ok()
}

fn constant_time_eq(left: &[u8; 32], right: &[u8; 32]) -> bool {
    left.iter()
        .zip(right.iter())
        .fold(0_u8, |difference, (a, b)| difference | (a ^ b))
        == 0
}

pub fn read_record(stream: &mut TcpStream) -> std::io::Result<Vec<u8>> {
    let mut length = [0_u8; 4];
    stream.read_exact(&mut length)?;
    let length = u32::from_be_bytes(length) as usize;
    if !(24..=MAX_MEDIA_RECORD_BYTES).contains(&length) {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "invalid media record length",
        ));
    }
    let mut record = Vec::with_capacity(4 + length);
    record.extend_from_slice(&(length as u32).to_be_bytes());
    record.resize(4 + length, 0);
    stream.read_exact(&mut record[4..])?;
    Ok(record)
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::panic)]
mod tests {
    use super::*;
    use twinpad_core::media_crypto::{MediaCryptoError, seal_record};

    /// Reloj inyectable por estado atómico (ENG §5 P10/P11) y ámbito de
    /// congestión neutro para los tests de relay que no ejercitan CR-058.
    #[derive(Clone)]
    struct TestClock(Arc<AtomicU64>);
    impl TestClock {
        fn now_ms(&self) -> u64 {
            self.0.load(Ordering::Relaxed)
        }
        fn advance(&self, ms: u64) {
            self.0.store(ms, Ordering::Relaxed);
        }
    }

    fn congestion_scope_neutral() -> (Arc<CongestionScope>, TestClock) {
        let clock = TestClock(Arc::new(AtomicU64::new(0)));
        let scope = Arc::new(CongestionScope::new(
            Box::new({
                let clock = clock.clone();
                move || clock.now_ms()
            }),
            Box::new(|| 8_000),
            Arc::new(|_| true),
        ));
        (scope, clock)
    }

    // TEST-0132 — CR-058 (SPEC-003 §4.3): integración server — ventanas del
    // relay alimentan la escalera; el rung baja con ≥2 ventanas congestionadas
    // (reenvío video_config + IDR vía `apply`) y sube con el hold de 10 s.
    #[test]
    fn congestion_windows_drive_ladder_down_and_recovery_up() {
        let clock = TestClock(Arc::new(AtomicU64::new(0)));
        let applied: Arc<std::sync::Mutex<Vec<u32>>> = Arc::new(std::sync::Mutex::new(Vec::new()));
        let applied_for_hook = Arc::clone(&applied);
        let scope = CongestionScope::new(
            Box::new({
                let clock = clock.clone();
                move || clock.now_ms()
            }),
            Box::new(|| 8_000),
            Arc::new(move |effective: u32| {
                applied_for_hook.lock().unwrap().push(effective);
                true
            }),
        );
        let congested = WindowStats {
            published: 40,
            discarded: 20,
        };
        clock.advance(100);
        scope.ingest_window(congested);
        assert_eq!(scope.rung(), 0); // 1 ventana: aún no degrada
        clock.advance(2_100);
        scope.ingest_window(congested);
        assert_eq!(scope.rung(), 1);
        assert_eq!(scope.effective_kbps(), 4_000); // 1/2 del bitrate base
        assert_eq!(applied.lock().unwrap().as_slice(), &[4_000]);
        // Recuperación: hold claro de 10 s sube un rung con su reenvío.
        let clear = WindowStats {
            published: 100,
            discarded: 4,
        };
        clock.advance(20_000);
        scope.ingest_window(clear);
        assert_eq!(scope.rung(), 1); // el hold aún no venció
        clock.advance(30_100); // 20_000 + 10_001: hold de recuperación vencido
        scope.ingest_window(clear);
        assert_eq!(scope.rung(), 0);
        assert_eq!(scope.effective_kbps(), 8_000); // nunca supera el config
        assert_eq!(applied.lock().unwrap().as_slice(), &[4_000, 8_000]);
    }

    // TEST-072 — CR-014 listener authorization and relay primitives.
    #[test]
    fn only_exact_expected_counter_and_valid_authenticate_tag_are_accepted() {
        let key = [0x71; 32];
        let record = seal_record(&key, 0, b"unit").unwrap();
        assert!(validate_client_record(&key, 0, &record));
        assert!(!validate_client_record(&key, 1, &record));
        let mut bad = record;
        *bad.last_mut().unwrap() ^= 1;
        assert_eq!(
            open_record(&key, 0, &bad),
            Err(MediaCryptoError::AuthenticationFailed)
        );
    }

    fn h264_source(fn_: u64, timestamp_us: u64, idr: bool, bytes: &[u8]) -> MediaSource {
        MediaSource {
            game_id: "cemu".into(),
            width: 64,
            height: 64,
            latest: AccessUnit {
                fn_,
                timestamp_us,
                idr,
                annex_b: Arc::from(bytes),
            },
        }
    }

    #[test]
    fn authenticated_loopback_relay_waits_for_selected_source_and_forwards_latest_aus() {
        use std::io::{Read, Write};

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let mut client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (server, _) = listener.accept().unwrap();
        client
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let session_id = SessionId([0x23; 16]);
        let session_key = Arc::new(SessionKey([0x41; 32]));
        let active = Arc::new(AtomicBool::new(true));
        let session_id_for_lookup = session_id;
        let key_for_lookup = Arc::clone(&session_key);
        let active_for_lookup = Arc::clone(&active);
        let keys: Arc<MediaCredentialLookup> = Arc::new(move |requested| {
            (*requested == session_id_for_lookup)
                .then(|| (Arc::clone(&key_for_lookup), Arc::clone(&active_for_lookup)))
        });
        let selected: Arc<SelectedGameLookup> = Arc::new(|_| Some("cemu".into()));
        let media_game_slot = Arc::new(std::sync::Mutex::new(None::<String>));
        let media_game_for_lookup = Arc::clone(&media_game_slot);
        let media_game: Arc<MediaGameLookup> =
            Arc::new(move |_| media_game_for_lookup.lock().unwrap().clone());
        let hub = Arc::new(MediaHub::default());
        hub.publish(h264_source(0, 100, true, b"idr-sps-pps"));
        // CR-051 (SPEC-003 §8.2): el servidor pide IDR al (re)conectar el
        // canal media; el hook se dispara tras el handshake exitoso.
        let media_joined_count = Arc::new(std::sync::Mutex::new(0_u32));
        let joined_for_client = Arc::clone(&media_joined_count);
        let on_media_joined: Arc<MediaJoinedHook> = Arc::new(move || {
            *joined_for_client.lock().unwrap() += 1;
            true
        });
        let server_hub = Arc::clone(&hub);
        let (test_congestion, _test_clock) = congestion_scope_neutral();
        let deps = Arc::new(MediaDeps {
            keys,
            selected,
            media_game,
            on_media_joined,
            congestion: test_congestion,
            tcp_media_joined: Arc::new(|_| {}),
        });
        let server_thread = std::thread::spawn(move || {
            handle_media_client(server, server_hub, deps);
        });

        let nonce_c = [0x5a; 32];
        let mut request = Vec::with_capacity(MEDIA_HANDSHAKE_BYTES);
        request.extend_from_slice(&session_id.0);
        request.extend_from_slice(&nonce_c);
        request.extend_from_slice(&client_proof(&session_key.0, &session_id.0, &nonce_c));
        client.write_all(&request).unwrap();
        let mut nonce_s = [0_u8; 32];
        let mut proof = [0_u8; 32];
        client.read_exact(&mut nonce_s).unwrap();
        client.read_exact(&mut proof).unwrap();
        assert_eq!(
            proof,
            server_proof(&session_key.0, &session_id.0, &nonce_c, &nonce_s)
        );
        let media_key = derive_media_key(&session_key.0, &nonce_c, &nonce_s);

        client
            .set_read_timeout(Some(Duration::from_millis(80)))
            .unwrap();
        assert!(
            matches!(read_record(&mut client), Err(error) if error.kind() == std::io::ErrorKind::WouldBlock || error.kind() == std::io::ErrorKind::TimedOut)
        );
        *media_game_slot.lock().unwrap() = Some("cemu".into());
        client
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let idr_record = read_record(&mut client).unwrap();
        let idr = open_record(&media_key, 0, &idr_record).unwrap();
        assert_eq!(&idr[..8], &0_u64.to_be_bytes());
        assert_eq!(&idr[8..16], &100_u64.to_be_bytes());
        assert_eq!(idr[16], 1);
        assert_eq!(&idr[17..], b"idr-sps-pps");
        assert_eq!(*media_joined_count.lock().unwrap(), 1);

        hub.publish(h264_source(1, 200, false, b"p-frame"));
        let p_record = read_record(&mut client).unwrap();
        let p_frame = open_record(&media_key, 1, &p_record).unwrap();
        assert_eq!(&p_frame[..8], &1_u64.to_be_bytes());
        assert_eq!(&p_frame[8..16], &200_u64.to_be_bytes());
        assert_eq!(p_frame[16], 0);
        assert_eq!(&p_frame[17..], b"p-frame");

        active.store(false, Ordering::Release);
        let mut after_revoke = [0_u8; 1];
        assert_eq!(client.read(&mut after_revoke).unwrap(), 0);
        server_thread.join().unwrap();
    }

    #[test]
    fn media_listener_closes_loopback_client_with_invalid_handshake_proof() {
        use std::io::{Read, Write};

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let mut client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (server, _) = listener.accept().unwrap();
        client
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let session_id = SessionId([0x31; 16]);
        let key = Arc::new(SessionKey([0x72; 32]));
        let active = Arc::new(AtomicBool::new(true));
        let lookup_id = session_id;
        let lookup_key = Arc::clone(&key);
        let lookup_active = Arc::clone(&active);
        let keys: Arc<MediaCredentialLookup> = Arc::new(move |requested| {
            (*requested == lookup_id).then(|| (Arc::clone(&lookup_key), Arc::clone(&lookup_active)))
        });
        let selected: Arc<SelectedGameLookup> = Arc::new(|_| None);
        let media_game: Arc<MediaGameLookup> = Arc::new(|_| None);
        let hub = Arc::new(MediaHub::default());
        let on_media_joined: Arc<MediaJoinedHook> = Arc::new(|| false);
        let (congestion_neutral, _test_clock) = congestion_scope_neutral();
        let deps = Arc::new(MediaDeps {
            keys,
            selected,
            media_game,
            on_media_joined,
            congestion: congestion_neutral,
            tcp_media_joined: Arc::new(|_| {}),
        });
        let server_thread = std::thread::spawn(move || {
            handle_media_client(server, hub, deps);
        });
        let mut request = Vec::with_capacity(MEDIA_HANDSHAKE_BYTES);
        request.extend_from_slice(&session_id.0);
        request.extend_from_slice(&[0x11; 32]);
        request.extend_from_slice(&[0_u8; 32]);
        client.write_all(&request).unwrap();
        let mut response = [0_u8; 1];
        assert_eq!(client.read(&mut response).unwrap(), 0);
        server_thread.join().unwrap();
    }

    // TEST-0167 (CR-083, pieza A): el hook de IDR con reintentos — los
    // 2 primeros intentos fallan y el 3.º retorna ok; el hook que falla
    // siempre agota los 3 intentos y devuelve false (desenlace logeado
    // con «media IDR falló tras 3 intentos»).
    #[test]
    fn idr_with_retry_recovers_on_third_attempt_and_reports_exhaustion() {
        let recovered_calls = Arc::new(AtomicU64::new(0));
        let calls_for_hook = Arc::clone(&recovered_calls);
        let hook_ok_later: Arc<MediaJoinedHook> =
            Arc::new(move || calls_for_hook.fetch_add(1, Ordering::SeqCst) >= 2);
        assert!(idr_with_retry(&hook_ok_later));
        assert_eq!(recovered_calls.load(Ordering::SeqCst), 3);

        let exhausted_calls = Arc::new(AtomicU64::new(0));
        let exhausted_for_hook = Arc::clone(&exhausted_calls);
        let hook_never: Arc<MediaJoinedHook> = Arc::new(move || {
            exhausted_for_hook.fetch_add(1, Ordering::SeqCst);
            false
        });
        assert!(!idr_with_retry(&hook_never));
        assert_eq!(exhausted_calls.load(Ordering::SeqCst), 3);
    }
}
