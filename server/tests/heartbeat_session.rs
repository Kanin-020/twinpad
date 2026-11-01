//! TEST-0121 — verifies SPEC-001 (§4.3, CR-055: heartbeat de sesión HB1–HB4).
//!
//! Integración sobre loopback real: pings del emisor HB1, echo/validación
//! HB2/HB3 y expiración de la sesión zombi sin EOF (HB4/HB6). Los
//! intervalos/deadlines son cortos porque el reloj/config entran por
//! parámetro (ENG §5 P11).

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::io::{BufRead, BufReader, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::Duration;

use twinpad_core::message::{self, HandshakeMsg};
use twinpad_server::heartbeat::{self, HeartbeatRead};

struct Harness {
    addr: SocketAddr,
    /// `pong` válidos aceptados por HB3 durante la prueba.
    accepted: Arc<AtomicU64>,
    /// La sesión terminó (EOF, error de E/S o vencimiento HB4).
    ended: Arc<AtomicBool>,
}

/// Lado servidor: emisor HB1 + lector con deadline HB4 sobre un socket real.
fn spawn_harness(interval_ms: u64, deadline_ms: u64) -> Harness {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let accepted = Arc::new(AtomicU64::new(0));
    let ended = Arc::new(AtomicBool::new(false));
    let accepted_clone = Arc::clone(&accepted);
    let ended_clone = Arc::clone(&ended);
    std::thread::spawn(move || {
        let (server, _) = listener.accept().unwrap();
        let stop = Arc::new(AtomicBool::new(false));
        let write_lock: Arc<std::sync::Mutex<()>> = Arc::new(std::sync::Mutex::new(()));
        let (pinger, pings) = heartbeat::spawn_ping_sender(
            true,
            server.try_clone().ok(),
            write_lock.clone(),
            Arc::clone(&stop),
            Duration::from_millis(interval_ms),
        );
        let mut heartbeat = heartbeat::SessionHeartbeat::with_deadline(deadline_ms, pings.clone());
        let mut reader = BufReader::new(server);
        let mut line = String::new();
        loop {
            match heartbeat.read_line(&mut reader, &mut line) {
                HeartbeatRead::Line => {
                    let trimmed = line.trim_end();
                    if trimmed.is_empty() {
                        continue;
                    }
                    if trimmed.starts_with("{\"type\":\"pong\"") {
                        if let Ok(HandshakeMsg::Pong { t }) = message::decode(trimmed) {
                            if heartbeat.on_pong(t) {
                                accepted_clone.fetch_add(1, Ordering::AcqRel);
                            }
                        }
                    }
                    // Otras líneas de sesión (inputs, video_config) no se
                    // procesan aquí: no refrescan la vida (HB3/HB4).
                }
                HeartbeatRead::Ended => {
                    stop.store(true, Ordering::Release);
                    ended_clone.store(true, Ordering::Release);
                    break;
                }
            }
        }
        if let Some(handle) = pinger {
            let _ = handle.join();
        }
    });
    Harness {
        addr,
        accepted,
        ended,
    }
}

fn wait_until(predicate: impl Fn() -> bool, timeout_ms: u64) -> bool {
    let start = std::time::Instant::now();
    while start.elapsed().as_millis() < u128::from(timeout_ms) {
        if predicate() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    predicate()
}

/// TEST-0121 — verifies SPEC-001 (§4.3 HB1/HB2 + HB4: teléfon libre EOF).
#[test]
fn zombie_session_pinging_then_silent_is_expired() {
    let h = spawn_harness(40, 300);
    let mut client = TcpStream::connect(h.addr).unwrap();
    client
        .set_read_timeout(Some(Duration::from_millis(2_000)))
        .unwrap();
    let mut reader = BufReader::new(client.try_clone().unwrap());

    // HB1/HB2: los pings llegan con `t` secuencial y un eco válido HB3 los
    // acepta uno a uno (3 en un plazo menor al deadline).
    let mut echoed = 0;
    while echoed < 3 {
        let mut line = String::new();
        let read = reader.read_line(&mut line).unwrap_or(0);
        if read == 0 {
            panic!("HB1: el servidor dejó de enviar pings antes del eco");
        }
        if let Ok(HandshakeMsg::Ping { t }) = message::decode(line.trim_end()) {
            assert_eq!(t, echoed, "HB1: contador de pings secuencial");
            let pong = message::encode(&HandshakeMsg::Pong { t }).unwrap();
            let ok = client
                .write_all(pong.as_bytes())
                .and_then(|_| client.write_all(b"\n"))
                .and_then(|_| client.flush());
            assert!(ok.is_ok(), "HB2: el `pong` sale por el socket");
            echoed += 1;
        }
    }
    assert!(
        wait_until(|| h.accepted.load(Ordering::Acquire) >= 3, 1_000),
        "los tres pongs válidos HB3 deben aceptarse en la sesión viva"
    );

    // HB4: sin EOF hacia el servidor (teléfono "muerto" sin señal de cierre),
    // el deadline cierra la sesión zombi igual que un corte (HB6).
    assert!(
        wait_until(|| h.ended.load(Ordering::Acquire), 3_000),
        "HB4: sin `pong` válido durante el deadline la sesión zombi expira"
    );
}

/// TEST-0121 — verifies SPEC-001 (§4.3 HB3: echos viejos no refrescan la vida).
#[test]
fn replayed_stale_pongs_do_not_refresh_liveness() {
    let h = spawn_harness(40, 300);
    let client = TcpStream::connect(h.addr).unwrap();
    let mut echo_client = client.try_clone().unwrap();
    // La falla repite SIEMPRE el último `pong` aceptado (t=0).
    std::thread::spawn(move || {
        let mut reader = BufReader::new(echo_client.try_clone().unwrap());
        loop {
            let mut line = String::new();
            match reader.read_line(&mut line) {
                Ok(n) if n > 0 => {
                    if let Ok(HandshakeMsg::Ping { .. }) = message::decode(line.trim_end()) {
                        let _ = echo_client.write_all(b"{\"type\":\"pong\",\"t\":0}\n");
                        let _ = echo_client.flush();
                    }
                }
                _ => break,
            }
        }
    });

    assert!(
        wait_until(|| h.ended.load(Ordering::Acquire), 3_000),
        "HB3/HB4: repetir un `t` viejo no refresca la ligadura"
    );
    assert_eq!(
        h.accepted.load(Ordering::Acquire),
        1,
        "solo el primer eco (t=0) fue aceptado; los repetidos se descartan"
    );
}

/// TEST-0121 — verifies SPEC-001 (§4.3: pings frescos mantienen viva la
/// sesión; EOF sigue terminando con el mismo fin observable, HB6).
#[test]
fn fresh_pongs_keep_session_alive_and_eof_still_ends_it() {
    // Deadline holgado: los 8 ecos (40 ms cada uno) caben holgadamente en la
    // ventana “viva”; el fin se verifica después por EOF (HB6).
    let h = spawn_harness(40, 900);
    let mut client = TcpStream::connect(h.addr).unwrap();
    client
        .set_read_timeout(Some(Duration::from_millis(2_000)))
        .unwrap();

    // HB2: eco fiel del `t` recibido.
    let mut last_echoed = 0;
    let mut reader = BufReader::new(client.try_clone().unwrap());
    while last_echoed < 8 {
        let mut line = String::new();
        let read = reader.read_line(&mut line).unwrap_or(0);
        if read == 0 {
            break; // terminó antes de los 8 ecos: los asserts finales fallarán
        }
        if let Ok(HandshakeMsg::Ping { t }) = message::decode(line.trim_end()) {
            let pong = message::encode(&HandshakeMsg::Pong { t }).unwrap();
            let ok = client
                .write_all(pong.as_bytes())
                .and_then(|_| client.write_all(b"\n"))
                .and_then(|_| client.flush());
            assert!(ok.is_ok());
            last_echoed = t + 1;
        }
    }
    // Mientras el eco es fiel y reciente, la sesión viva NO termina.
    assert!(
        wait_until(|| h.accepted.load(Ordering::Acquire) >= 8, 1_000),
        "HB3: cada eco fresco se acepta una vez"
    );
    assert!(!h.ended.load(Ordering::Acquire));

    // HB6: EOF (el cliente cierra) es el mismo fin que el vencimiento HB4.
    drop(client);
    drop(reader);
    assert!(
        wait_until(|| h.ended.load(Ordering::Acquire), 1_000),
        "el EOF del socket termina la sesión (HB6, mismo fin observable)"
    );
}

// Nota de proceso: el emisor HB1 usa el lock de línea de la sesión (ENG P10):
// un ping nunca intercala una línea NDJSON grande de video/audio.
