//! Listener UDP del canal media (SPEC-003 §4.4, CR-062).
//!
//! Recibe JOINs autenticados al mismo `media_port` (8425) y mantiene UN
//! canal UDP por sesión con propiedad por recencia: un JOIN con nonce
//! nuevo toma la propiedad y pide IDR; el keepalive del MISMO nonce
//! refresca el binding y alimenta la escalera R10 con la señal
//! `last_seq`; el handshake TCP §4.2 devuelve la propiedad a TCP. El
//! relay por sesión reenvía la AU más reciente (latest-frame-wins)
//! fragmentada y sellada con `UdpEmitter` (FEC XOR por grupos de ≤ 4).
//! Sin estado persistente: el canal vive y muere con el Pairing (R8).

use std::collections::HashMap;
use std::net::{SocketAddr, UdpSocket};
use std::sync::Arc;
use std::sync::Mutex;
use std::sync::MutexGuard;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use twinpad_core::SessionId;
#[cfg(test)]
use twinpad_core::SessionKey;
use twinpad_core::bitrate::{WINDOW_MS, WindowStats};
#[cfg(test)]
use twinpad_core::media_udp::seal_join;
use twinpad_core::media_udp::{
    FEC_AU_ID, UdpEmitter, derive_udp_media_key, join_mac, mac_matches, parse_join,
};

use crate::media::{AccessUnit, MediaHub, SelectedGameLookup};

/// Keepalive fresco para la señal R11 (§4.4: ≤ 5 s).
pub const KEEPALIVE_FRESH_MS: u64 = 5_000;
/// Acotado de reevaluación del hub en el relay UDP (igual que el TCP).
const HUB_WAIT: Duration = Duration::from_millis(50);
/// Longitud del buffer de recepción UDP: los JOINs son de 92 B; todo lo
/// demás (datagramas de datos reflejados, basura) se descarta (§14).
const RECV_BUF_BYTES: usize = 2_048;

fn lock<T>(slot: &Mutex<T>) -> MutexGuard<'_, T> {
    slot.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Estado compartido del canal UDP vigente de una sesión.
pub struct UdpChannelState {
    addr: Mutex<SocketAddr>,
    pub join_nonce: [u8; 32],
    pub media_key: [u8; 32],
    pub generation: u64,
    /// `seq_corriente` del relay (emitter); la actualiza el worker.
    current_seq: Mutex<u32>,
    /// Último `last_seq` recibido en keepalive (señal R11).
    last_seq: Mutex<u32>,
    /// Instante del último keepalive (dominio del reloj de la escalera).
    last_keepalive_ms: Mutex<u64>,
    window: Mutex<RelayWindow>,
}

struct RelayWindow {
    start_ms: u64,
    published: u64,
    gap_sum: u64,
    samples: u64,
}

impl UdpChannelState {
    /// `seq_corriente` del relay vigente; señal R11.
    #[must_use]
    pub fn current_seq_value(&self) -> u32 {
        *lock(&self.current_seq)
    }
}

/// Registro de canales UDP por sesión + generación de propiedad.
pub struct UdpChannelRegistry {
    generation: Mutex<HashMap<SessionId, u64>>,
    channels: Mutex<HashMap<SessionId, Arc<UdpChannelState>>>,
}

impl Default for UdpChannelRegistry {
    fn default() -> Self {
        Self::new()
    }
}

impl UdpChannelRegistry {
    /// Registro inicial: ninguna generación ni canal vivo.
    #[must_use]
    pub fn new() -> Self {
        Self {
            generation: Mutex::new(HashMap::new()),
            channels: Mutex::new(HashMap::new()),
        }
    }

    fn bump(&self, sid: &SessionId) -> u64 {
        let value = {
            let mut generation = lock(&self.generation);
            let next = generation.entry(*sid).or_insert(0);
            *next += 1;
            *next
        };
        lock(&self.channels).remove(sid);
        value
    }

    /// Generación vigente del `sid` (0 = nunca hubo canal).
    #[must_use]
    pub fn current_generation(&self, sid: &SessionId) -> u64 {
        lock(&self.generation).get(sid).copied().unwrap_or(0)
    }

    /// Handshake TCP §4.2 válido: la propiedad vuelve a TCP (§4.4).
    pub fn bump_tcp_owner(&self, sid: &SessionId) {
        let _ = self.bump(sid);
    }

    /// Canal vivo de la sesión, con sus workers, si existe (§4.4).
    #[must_use]
    pub fn channel(&self, sid: &SessionId) -> Option<Arc<UdpChannelState>> {
        lock(&self.channels).get(sid).cloned()
    }

    /// Retira el canal solo si sigue siendo el de `generation` (el worker
    /// terminado lo hace; evita borrar un canal nuevo por el viejo).
    fn drop_if_generation(&self, sid: &SessionId, generation: u64) {
        let mut channels = lock(&self.channels);
        if channels
            .get(sid)
            .is_some_and(|chan| chan.generation == generation)
        {
            channels.remove(sid);
        }
    }

    fn start_channel(
        &self,
        sid: &SessionId,
        addr: SocketAddr,
        join_nonce: [u8; 32],
        media_key: [u8; 32],
        now_ms: u64,
    ) -> Arc<UdpChannelState> {
        let generation = self.bump(sid);
        let chan = Arc::new(UdpChannelState {
            addr: Mutex::new(addr),
            join_nonce,
            media_key,
            generation,
            current_seq: Mutex::new(0),
            last_seq: Mutex::new(0),
            last_keepalive_ms: Mutex::new(now_ms),
            window: Mutex::new(RelayWindow {
                start_ms: now_ms,
                published: 0,
                gap_sum: 0,
                samples: 0,
            }),
        });
        lock(&self.channels).insert(*sid, Arc::clone(&chan));
        chan
    }
}

/// Dependencias del canal UDP (comparten hub/escalera con el TCP).
pub struct UdpMediaDeps {
    pub keys: Arc<crate::media::MediaCredentialLookup>,
    pub hub: Arc<MediaHub>,
    pub selected: Arc<SelectedGameLookup>,
    pub media_game: Arc<crate::media::MediaGameLookup>,
    pub on_media_joined: Arc<crate::media::MediaJoinedHook>,
    pub congestion: Arc<crate::media::CongestionScope>,
}

/// Bucle de recepción de JOINs; cada canal válido obtiene su relay.
pub fn run_media_udp_listener(
    socket: Arc<UdpSocket>,
    registry: Arc<UdpChannelRegistry>,
    deps: Arc<UdpMediaDeps>,
) {
    let mut buffer = vec![0_u8; RECV_BUF_BYTES];
    loop {
        let Ok((read, addr)) = socket.recv_from(&mut buffer) else {
            continue;
        };
        handle_join(
            &Arc::clone(&socket),
            &registry,
            &Arc::clone(&deps),
            &buffer[..read.min(RECV_BUF_BYTES)],
            addr,
        );
    }
}

fn handle_join(
    socket: &Arc<UdpSocket>,
    registry: &Arc<UdpChannelRegistry>,
    deps: &Arc<UdpMediaDeps>,
    datagram: &[u8],
    addr: SocketAddr,
) {
    let Ok(join) = parse_join(datagram) else {
        return;
    };
    let sid = SessionId(join.session_id);
    let Some((key, pair_active)) = (deps.keys)(&sid) else {
        return;
    };
    if !pair_active.load(Ordering::Acquire) {
        return; // sin Pairing activo ni retención viva (§4.2)
    }
    let expected = join_mac(&key.0, &join.session_id, join.last_seq, &join.join_nonce);
    if !mac_matches(&expected, &datagram[60..92]) {
        return;
    }
    let now = deps.congestion.now_ms();
    if let Some(existing) = registry.channel(&sid)
        && existing.join_nonce == join.join_nonce
    {
        // Keepalive del MISMO nonce: refresca el binding y la señal R11
        // sin IDR ni cambio de dueño (§4.4).
        *lock(&existing.addr) = addr;
        *lock(&existing.last_keepalive_ms) = now;
        *lock(&existing.last_seq) = join.last_seq;
        let current_seq = existing.current_seq_value();
        if let Some(stats) = rollover_window(&existing, now, current_seq, join.last_seq) {
            deps.congestion.ingest_window(stats);
        }
        return;
    }
    // Re-join (nonce distinto) o primer JOIN: toma la propiedad e IDR.
    let media_key = derive_udp_media_key(&key.0, &join.join_nonce);
    let chan = registry.start_channel(&sid, addr, join.join_nonce, media_key, now);
    let _ = (deps.on_media_joined)();
    println!("udp-relay join nuevo addr={addr} dueño=UDP (IDR solicitado)");
    let socket = Arc::clone(socket);
    let registry = Arc::clone(registry);
    let deps = Arc::clone(deps);
    std::thread::spawn(move || {
        relay_udp_session(socket, registry, deps, sid, chan, pair_active);
    });
}

/// Señal R11 (§4.4): ventana de 2 000 ms con P = datagramas de datos
/// enviados y D = gap promedio (`seq_corriente − last_seq`) de los
/// keepalives frescos; sin señal fresca no se ingiere. Umbral e
/// histéresis idénticos a R10 (§4.3). La ventana P/D del UDP NO reemplaza
/// la del TCP: ambos relays alimentan la MISMA escalera global.
fn rollover_window(
    chan: &UdpChannelState,
    now: u64,
    current_seq: u32,
    last_seq: u32,
) -> Option<WindowStats> {
    let gap = u64::from(current_seq.saturating_sub(last_seq));
    let mut window = lock(&chan.window);
    let mut closed = None;
    if now.saturating_sub(window.start_ms) >= WINDOW_MS {
        // Cierre de la ventana previa: solo con keepalive fresco nueva señal.
        let fresh = now.saturating_sub(*lock(&chan.last_keepalive_ms)) <= KEEPALIVE_FRESH_MS;
        if fresh && window.samples > 0 {
            closed = Some(WindowStats {
                published: window.published,
                discarded: window.gap_sum / window.samples.max(1),
            });
        }
        window.start_ms = now;
        window.published = 0;
        window.gap_sum = 0;
        window.samples = 0;
    }
    window.gap_sum += gap;
    window.samples += 1;
    closed
}

/// Relay de una sesión: reenvía la AU más reciente (latest-frame-wins)
/// mientras el canal sea el dueño vigente y el Pairing esté activo.
fn relay_udp_session(
    socket: Arc<UdpSocket>,
    registry: Arc<UdpChannelRegistry>,
    deps: Arc<UdpMediaDeps>,
    session_id: SessionId,
    chan: Arc<UdpChannelState>,
    pair_active: Arc<AtomicBool>,
) {
    let mut emitter = UdpEmitter::new(chan.media_key, 0, 0);
    let mut current_game: Option<String> = None;
    let mut last_fn: Option<u64> = None;
    let mut last_timestamp_us: Option<u64> = None;
    let mut park_log_ms: u64 = 0;
    loop {
        if !pair_active.load(Ordering::Acquire) {
            println!("udp-relay salida: Pairing inactivo");
            registry.drop_if_generation(&session_id, chan.generation);
            return; // el Pairing finalizado elimina el canal (R8)
        }
        if registry.current_generation(&session_id) != chan.generation {
            println!("udp-relay salida: perdió el dueño (TCP o re-join)");
            return; // perdió la propiedad (TCP o re-join)
        }
        let Some(game_id) = (deps.selected)(&session_id) else {
            if current_game.is_some() {
                println!("udp-relay park: sin juego seleccionado");
            }
            current_game = None;
            last_fn = None;
            last_timestamp_us = None;
            deps.hub.wait_for_change(Duration::from_millis(5));
            continue;
        };
        if current_game.as_deref() != Some(game_id.as_str()) {
            current_game = Some(game_id.clone());
            last_fn = None;
            last_timestamp_us = None;
        }
        if (deps.media_game)(&session_id).as_deref() != Some(game_id.as_str()) {
            let now_ms = deps.congestion.now_ms();
            if now_ms >= park_log_ms {
                println!("udp-relay park: media_game no coincide con seleccionado ({game_id})");
                park_log_ms = now_ms + 2_000;
            }
            deps.hub.wait_for_change(Duration::from_millis(2));
            continue;
        }
        let forwardable = |au: &AccessUnit| match last_fn {
            None => au.idr,
            Some(last) => au.fn_ > last && au.timestamp_us > last_timestamp_us.unwrap_or(0),
        };
        let Some(source) = deps
            .hub
            .wait_for_forwardable(&game_id, forwardable, HUB_WAIT)
        else {
            let now_ms = deps.congestion.now_ms();
            if now_ms >= park_log_ms {
                println!(
                    "udp-relay park: sin AU forwardable en {} ms (last_fn={last_fn:?})",
                    HUB_WAIT.as_millis(),
                );
                park_log_ms = now_ms + 2_000;
            }
            continue;
        };
        // La propiedad puede cambiar durante el wait (bump TCP §4.2 o
        // re-join): re-chequeo antes de sellar/enviar (§4.4, recencia).
        if registry.current_generation(&session_id) != chan.generation {
            println!("udp-relay salida: dueño cambió durante el wait");
            return;
        }
        if source.width < 16
            || source.width > 3840
            || source.height < 16
            || source.height > 2160
            || source.latest.annex_b.is_empty()
        {
            println!(
                "udp-relay salida: fuente inválida {}×{} annex_b={}",
                source.width,
                source.height,
                source.latest.annex_b.len(),
            );
            registry.drop_if_generation(&session_id, chan.generation);
            return;
        }
        let Ok(datagrams) = emitter.seal_au(
            source.latest.fn_,
            source.latest.timestamp_us,
            source.latest.idr,
            &source.latest.annex_b,
        ) else {
            println!("udp-relay salida: seal_au falló (drop canal)");
            registry.drop_if_generation(&session_id, chan.generation);
            return;
        };
        let addr = *lock(&chan.addr);
        let mut send_failed = 0_usize;
        for datagram in &datagrams {
            if socket.send_to(datagram, addr).is_err() {
                send_failed += 1;
            }
        }
        if send_failed > 0 {
            println!(
                "udp-relay envío fallido: {send_failed}/{} datagramas",
                datagrams.len()
            );
        }
        println!(
            "udp-relay AU fn={} idr={} ts={} datagrams={} addr={addr}",
            source.latest.fn_,
            source.latest.idr,
            source.latest.timestamp_us,
            datagrams.len(),
        );
        let data_datagrams = datagrams
            .iter()
            .filter(|d| d[8..10] != FEC_AU_ID.to_be_bytes())
            .count();
        *lock(&chan.current_seq) = emitter.current_seq();
        let mut window = lock(&chan.window);
        window.published += data_datagrams as u64;
        drop(window);
        last_fn = Some(source.latest.fn_);
        last_timestamp_us = Some(source.latest.timestamp_us);
        // Como en el TCP (CR-029): sin pacing; el bucle vuelve al hub,
        // donde la siguiente AU esperará en el Condvar de CR-051.
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;
    use std::net::UdpSocket;
    use std::sync::atomic::AtomicU64;
    use twinpad_core::media_udp::UdpReassembler;

    /// Reloj inyectable (ENG §5 P10/P11), como en media.rs.
    #[derive(Clone)]
    struct TestClock(Arc<AtomicU64>);
    impl TestClock {
        fn now_ms(&self) -> u64 {
            self.0.load(Ordering::Relaxed)
        }
    }

    fn congestion_neutral(clock: TestClock) -> Arc<crate::media::CongestionScope> {
        Arc::new(crate::media::CongestionScope::new(
            Box::new(move || clock.now_ms()),
            Box::new(|| 8_000),
            Arc::new(|_| true),
        ))
    }

    struct TestRig {
        sid: SessionId,
        session_key: SessionKey,
        pair_active: Arc<AtomicBool>,
        idr_count: Arc<Mutex<u32>>,
        hub: Arc<MediaHub>,
        registry: Arc<UdpChannelRegistry>,
        server_port: u16,
    }

    fn h264_source(fn_: u64, ts: u64, idr: bool, bytes: &[u8]) -> crate::media::MediaSource {
        crate::media::MediaSource {
            game_id: "cemu".into(),
            width: 64,
            height: 64,
            latest: AccessUnit {
                fn_,
                timestamp_us: ts,
                idr,
                annex_b: bytes.to_vec().into(),
            },
        }
    }

    fn setup(now_clock: TestClock) -> TestRig {
        let sid = SessionId([0x23; 16]);
        let session_key = SessionKey([0x41; 32]);
        let pair_active = Arc::new(AtomicBool::new(true));
        let idr_count = Arc::new(Mutex::new(0_u32));
        let idr_for_hook = Arc::clone(&idr_count);
        let sid_for_keys = sid;
        let key_for_keys = session_key;
        let active_for_keys = Arc::clone(&pair_active);
        let keys: Arc<crate::media::MediaCredentialLookup> = Arc::new(move |requested| {
            (*requested == sid_for_keys)
                .then_some((Arc::new(key_for_keys), Arc::clone(&active_for_keys)))
        });
        let selected: Arc<SelectedGameLookup> = Arc::new(|_| Some("cemu".into()));
        let media_game: Arc<crate::media::MediaGameLookup> = Arc::new(|_| Some("cemu".into()));
        let on_media_joined: Arc<crate::media::MediaJoinedHook> = Arc::new(move || {
            *idr_for_hook.lock().unwrap() += 1;
            true
        });
        let hub = Arc::new(MediaHub::default());
        let deps = Arc::new(UdpMediaDeps {
            keys,
            hub: Arc::clone(&hub),
            selected,
            media_game,
            on_media_joined,
            congestion: congestion_neutral(now_clock),
        });
        let registry = Arc::new(UdpChannelRegistry::new());
        let socket = Arc::new(UdpSocket::bind("127.0.0.1:0").unwrap());
        let server_port = socket.local_addr().unwrap().port();
        let registry_for_listener = Arc::clone(&registry);
        std::thread::spawn(move || {
            run_media_udp_listener(Arc::clone(&socket), registry_for_listener, deps)
        });
        TestRig {
            sid,
            session_key,
            pair_active,
            idr_count,
            hub,
            registry,
            server_port,
        }
    }

    /// Recibe datagramas hasta ensamblar una AU (tope 2 s).
    fn recv_au(
        client: &UdpSocket,
        reassembler: &mut UdpReassembler,
    ) -> Option<twinpad_core::media_udp::AssembledAu> {
        let deadline = std::time::Instant::now() + Duration::from_secs(2);
        client
            .set_read_timeout(Some(Duration::from_millis(200)))
            .unwrap();
        while std::time::Instant::now() < deadline {
            let mut buffer = vec![0_u8; 2_048];
            match client.recv_from(&mut buffer) {
                Ok((read, _)) => {
                    if let Ok(Some(au)) = reassembler.ingest(&buffer[..read]) {
                        return Some(au);
                    }
                }
                Err(_) => continue,
            }
        }
        None
    }

    fn send_join(client: &UdpSocket, rig: &TestRig, nonce: &[u8; 32], last_seq: u32) {
        let join = seal_join(&rig.session_key.0, &rig.sid.0, last_seq, nonce);
        client
            .send_to(&join, ("127.0.0.1", rig.server_port))
            .unwrap();
    }

    /// Vacía lo pendiente en el socket del cliente (datagramas FEC
    /// residuales de grupos ya ensamblados) para aserciones negativas.
    fn drain(client: &UdpSocket) {
        client
            .set_read_timeout(Some(Duration::from_millis(100)))
            .unwrap();
        let mut buffer = vec![0_u8; 2_048];
        while client.recv_from(&mut buffer).is_ok() {}
    }

    fn wait_generation(rig: &TestRig, generation: u64) {
        let deadline = std::time::Instant::now() + Duration::from_secs(2);
        while std::time::Instant::now() < deadline {
            if rig.registry.current_generation(&rig.sid) >= generation {
                return;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        panic!("el_join nunca tomó la generación {generation}");
    }

    // TEST-0139 — CR-062 (SPEC-003 §4.4): el JOIN válido toma la
    // propiedad UDP y dispara IDR; el relay entrega la AU (con su
    // recovery FEC en los datagramas); el keepalive del MISMO nonce
    // refresca sin IDR ni cambio de dueño; el TCP recupera la propiedad;
    // el re-join (nonce distinto) recupera con clave nueva.
    #[test]
    fn join_takes_ownership_relay_fec_and_tcp_takeover() {
        let clock = TestClock(Arc::new(AtomicU64::new(0)));
        let rig = setup(clock);
        rig.hub.publish(h264_source(0, 100, true, b"idr-sps-pps"));
        let client = UdpSocket::bind("127.0.0.1:0").unwrap();
        let nonce_a = [0xAA; 32];
        send_join(&client, &rig, &nonce_a, 0);
        wait_generation(&rig, 1);
        assert_eq!(*rig.idr_count.lock().unwrap(), 1);

        let key_a = derive_udp_media_key(&rig.session_key.0, &nonce_a);
        let mut reassembler = UdpReassembler::new(key_a);
        let au = recv_au(&client, &mut reassembler).expect("AU IDR por UDP");
        assert_eq!(au.fn_, 0);
        assert!(au.idr);
        assert_eq!(au.annex_b, b"idr-sps-pps");

        // Keepalive del MISMO nonce: sin IDR ni cambio de dueño.
        let before_generation = rig.registry.current_generation(&rig.sid);
        send_join(&client, &rig, &nonce_a, 2);
        std::thread::sleep(Duration::from_millis(100));
        assert_eq!(*rig.idr_count.lock().unwrap(), 1);
        assert_eq!(rig.registry.current_generation(&rig.sid), before_generation);

        // Segunda AU (P) por UDP.
        rig.hub.publish(h264_source(1, 200, false, b"p-frame"));
        let au = recv_au(&client, &mut reassembler).expect("AU P por UDP");
        assert_eq!(au.fn_, 1);
        assert_eq!(au.annex_b, b"p-frame");

        // TCP toma la propiedad: el relay UDP deja de enviar.
        drain(&client);
        rig.registry.bump_tcp_owner(&rig.sid);
        rig.hub.publish(h264_source(2, 300, false, b"tcp-owned"));
        client
            .set_read_timeout(Some(Duration::from_millis(300)))
            .unwrap();
        let mut buffer = vec![0_u8; 2_048];
        assert!(client.recv_from(&mut buffer).is_err());

        // Re-join con nonce distinto: nueva generación + IDR + clave nueva.
        let nonce_b = [0xBB; 32];
        send_join(&client, &rig, &nonce_b, 0);
        wait_generation(&rig, 3);
        assert_eq!(*rig.idr_count.lock().unwrap(), 2);
        // Único datagrama de datos hasta aquí: el relay NO entrega AU
        // hasta IDR (hub.latest es fn2 P); publicamos la IDR de recuperación.
        rig.hub.publish(h264_source(3, 400, true, b"post-rejoin"));
        let key_b = derive_udp_media_key(&rig.session_key.0, &nonce_b);
        let mut reassembler_b = UdpReassembler::new(key_b);
        let au = recv_au(&client, &mut reassembler_b).expect("AU post re-join");
        assert_eq!(au.fn_, 3);
        assert_eq!(au.annex_b, b"post-rejoin");
    }

    // TEST-0139 (CR-062): JOIN sin Pairing activo no abre canal ni IDR.
    #[test]
    fn join_without_active_pairing_is_rejected() {
        let clock = TestClock(Arc::new(AtomicU64::new(0)));
        let rig = setup(clock);
        rig.pair_active.store(false, Ordering::Release);
        let client = UdpSocket::bind("127.0.0.1:0").unwrap();
        send_join(&client, &rig, &[0xCC; 32], 0);
        std::thread::sleep(Duration::from_millis(150));
        assert_eq!(*rig.idr_count.lock().unwrap(), 0);
        assert!(rig.registry.channel(&rig.sid).is_none());
        client
            .set_read_timeout(Some(Duration::from_millis(300)))
            .unwrap();
        let mut buffer = vec![0_u8; 2_048];
        assert!(client.recv_from(&mut buffer).is_err());
    }

    // TEST-0139 (CR-062): JOIN con HMAC inválido queda fuera (§4.2).
    #[test]
    fn join_with_invalid_mac_is_rejected() {
        let clock = TestClock(Arc::new(AtomicU64::new(0)));
        let rig = setup(clock);
        let client = UdpSocket::bind("127.0.0.1:0").unwrap();
        let mut join = seal_join(&rig.session_key.0, &rig.sid.0, 0, &[0xDD; 32]);
        join[92 - 1] ^= 1; // MAC inválido
        client
            .send_to(&join, ("127.0.0.1", rig.server_port))
            .unwrap();
        std::thread::sleep(Duration::from_millis(150));
        assert_eq!(*rig.idr_count.lock().unwrap(), 0);
        assert!(rig.registry.channel(&rig.sid).is_none());
        drop(client);
    }

    // TEST-0139 (CR-062): señal R11 — ventana de 2 000 ms con P/D del
    // último keepalive fresco; sin señal fresca no se ingiere.
    #[test]
    fn keepalive_window_rollover_yields_last_seq_stats() {
        let clock = TestClock(Arc::new(AtomicU64::new(0)));
        let rig = setup(clock);
        let client = UdpSocket::bind("127.0.0.1:0").unwrap();
        send_join(&client, &rig, &[0xEE; 32], 0);
        wait_generation(&rig, 1);
        // Sin flujo de AU no hay P; la señal solo aparece con gap y P > 0.
        // Simulación directa de la ventana:
        let chan = rig.registry.channel(&rig.sid).unwrap();
        {
            let mut window = lock(&chan.window);
            window.published = 40;
            window.gap_sum = 50;
            window.samples = 2;
        }
        *lock(&chan.last_keepalive_ms) = 1_000;
        assert_eq!(
            rollover_window(&chan, 2_100, 15, 5),
            Some(WindowStats {
                published: 40,
                discarded: 25,
            })
        );
        // Tras el cierre la ventana reinicia; señal sin keepalive fresco:
        *lock(&chan.last_keepalive_ms) = 0;
        assert_eq!(rollover_window(&chan, 2_300, 15, 5), None);
    } // TEST-0139 (CR-062): programación del relay sin eventos de la fuente
    // seleccionada — el relay sale limpio si el juego deja de estar vivo.
    #[test]
    fn missing_selected_game_keeps_waiting_not_spinning() {
        let clock = TestClock(Arc::new(AtomicU64::new(0)));
        let rig = setup(clock);
        let client = UdpSocket::bind("127.0.0.1:0").unwrap();
        send_join(&client, &rig, &[0xFF; 32], 0);
        wait_generation(&rig, 1);
        // Sin fuente publicada el relay espera en el hub sin enviar nada.
        client
            .set_read_timeout(Some(Duration::from_millis(300)))
            .unwrap();
        let mut buffer = vec![0_u8; 2_048];
        assert!(client.recv_from(&mut buffer).is_err());
        assert_eq!(*rig.idr_count.lock().unwrap(), 1);
        drop(client);
    } // TEST-0139 (CR-062): revocación del Pairing — el relay sale y el
    // canal desaparece (R8). Combinada con la AU perdida == sin entrega.
    #[test]
    fn revoked_pairing_stops_relay_and_drops_channel() {
        let clock = TestClock(Arc::new(AtomicU64::new(0)));
        let rig = setup(clock);
        rig.hub.publish(h264_source(0, 100, true, b"before-revoke"));
        let client = UdpSocket::bind("127.0.0.1:0").unwrap();
        send_join(&client, &rig, &[0x11; 32], 0);
        wait_generation(&rig, 1);
        let key = derive_udp_media_key(&rig.session_key.0, &[0x11; 32]);
        let mut reassembler = UdpReassembler::new(key);
        assert_eq!(recv_au(&client, &mut reassembler).unwrap().fn_, 0);
        rig.pair_active.store(false, Ordering::Release);
        let deadline = std::time::Instant::now() + Duration::from_secs(2);
        while std::time::Instant::now() < deadline && rig.registry.channel(&rig.sid).is_some() {
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(rig.registry.channel(&rig.sid).is_none());
    } // TEST-0139 (CR-062): guardrail del ensamblado del receptor en
    // server: una AU con fn menor (stale, latest-frame-wins) no se entrega.
    #[test]
    fn stale_au_not_forwarded_after_already_delivered() {
        let clock = TestClock(Arc::new(AtomicU64::new(0)));
        let rig = setup(clock);
        rig.hub.publish(h264_source(5, 500, true, b"first"));
        let client = UdpSocket::bind("127.0.0.1:0").unwrap();
        send_join(&client, &rig, &[0x22; 32], 0);
        wait_generation(&rig, 1);
        let key = derive_udp_media_key(&rig.session_key.0, &[0x22; 32]);
        let mut reassembler = UdpReassembler::new(key);
        assert_eq!(recv_au(&client, &mut reassembler).unwrap().fn_, 5);
        drain(&client);
        // Una AU con fn menor (stale, latest-frame-wins) no debe llegar.
        rig.hub.publish(h264_source(4, 400, false, b"stale"));
        client
            .set_read_timeout(Some(Duration::from_millis(300)))
            .unwrap();
        let mut buffer = vec![0_u8; 2_048];
        assert!(client.recv_from(&mut buffer).is_err());
        drop(client);
    }
}
