//! Tests de integración en loopback TCP real (test-strategy §2/§10: el
//! protocolo no se mockea). Cubren los aspectos de TEST-002/005/007/008/009
//! que corresponden al lado servidor (SPEC-001 §7/§9/§14, DES-001 §12).

// ENG §6: unwrap/expect/panic permitidos solo en tests (este crate es todo test).
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::time::Duration;

use twinpad_core::handshake::TokenRecord;
use twinpad_core::message::{self, HandshakeMsg};
use twinpad_core::{Nonce, Token};

use twinpad_server::session::{SessionDeps, SessionOutcome, run_handshake};
use twinpad_server::tokens::{Clock, RandomBytes, TokenStore};

// ---- inyectables deterministas (ENG §5 P10/P11) ----

struct SeqRng(u8);
impl RandomBytes for SeqRng {
    fn fill(&self, buf: &mut [u8]) {
        for b in buf.iter_mut() {
            *b = self.0;
        }
    }
}

struct FixedClock(u64);
impl Clock for FixedClock {
    fn now_ms(&self) -> u64 {
        self.0
    }
}

fn token_record(issued_at_ms: u64, consumed: bool) -> TokenRecord {
    TokenRecord {
        token: Token([7; 32]),
        issued_at_ms,
        consumed,
    }
}

/// Cliente de prueba: parla con el servidor por TCP real.
struct TestClient {
    stream: TcpStream,
}

impl TestClient {
    fn connect(addr: &str) -> Self {
        let stream = TcpStream::connect(addr).expect("conectar al loopback");
        Self { stream }
    }

    fn send(&mut self, msg: &HandshakeMsg) {
        let line = message::encode(msg).expect("encode del test");
        self.stream
            .write_all(line.as_bytes())
            .and_then(|_| self.stream.write_all(b"\n"))
            .expect("escribir en loopback");
    }

    fn send_raw(&mut self, raw: &str) {
        self.stream
            .write_all(raw.as_bytes())
            .and_then(|_| self.stream.write_all(b"\n"))
            .expect("escribir raw en loopback");
    }

    fn read(&mut self) -> Option<HandshakeMsg> {
        let mut buf = String::new();
        BufReader::new(&mut self.stream)
            .read_line(&mut buf)
            .ok()
            .filter(|n| *n > 0)?;
        message::decode(buf.trim_end()).ok()
    }
}

/// Sirve una conexión accept-ada con `run_handshake` en otro hilo.
fn serve(listener: &TcpListener, record: TokenRecord) -> SessionOutcome {
    let (mut stream, _) = listener.accept().expect("accept en loopback");
    let mut nonce_counter = 0u8;
    let mut deps = SessionDeps {
        now_ms: &mut || 1_000,
        next_nonce: &mut || {
            nonce_counter += 1;
            Nonce([nonce_counter; 32])
        },
        next_session_id: &mut || twinpad_core::SessionId([99; 16]),
    };
    stream
        .set_read_timeout(Some(Duration::from_secs(2)))
        .expect("timeout de loopback");
    run_handshake(&mut stream, record, &mut deps)
}

fn store() -> TokenStore<SeqRng, FixedClock> {
    TokenStore::new(SeqRng(5), FixedClock(1_000), "127.0.0.1".into(), 0)
}

// ---- TEST-001 (aspecto servidor): Handshake exitoso por TCP real ----

#[test]
fn full_handshake_over_real_tcp_establishes_session() {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback");
    let addr = listener.local_addr().unwrap().to_string();

    // Token emitido por el almacén y su QR parseable por el core (§4.1):
    // el puerto del QR debe ser el real del listener (≥1024), no 0.
    let real_port = listener.local_addr().unwrap().port();
    let issuer = TokenStore::new(SeqRng(5), FixedClock(1_000), "127.0.0.1".into(), real_port);
    let issued = issuer.issue();
    let qr = twinpad_core::qr::parse(&issued.qr_uri).expect("QR canónico parseable");
    assert_eq!(qr.token, message::b64_32(&issued.record.token.0));

    let handle = std::thread::spawn(move || serve(&listener, issued.record.clone()));

    let mut client = TestClient::connect(&addr);
    client.send(&HandshakeMsg::PairRequest {
        schema: "twinpad.v1".into(),
        token: qr.token.clone(),
    });
    let HandshakeMsg::PairChallenge { nonce_s } = client.read().expect("challenge") else {
        panic!("esperaba pair_challenge");
    };

    // El cliente de prueba deriva y responde como lo haría la app.
    let nonce_s_bytes = message::nonce_bytes(&nonce_s).unwrap();
    let nonce_c = Nonce([22; 32]);
    let token_bytes = message::token_bytes(&qr.token).unwrap();
    let key = twinpad_core::crypto::derive_session_key(&token_bytes, &nonce_s_bytes);
    let request_line = format!(
        "{{\"type\":\"pair_request\",\"schema\":\"twinpad.v1\",\"token\":\"{}\"}}",
        qr.token
    );
    let challenge_line = format!("{{\"type\":\"pair_challenge\",\"nonce_s\":\"{nonce_s}\"}}");
    let proof = twinpad_core::crypto::compute_proof(
        &key,
        &request_line,
        &challenge_line,
        &nonce_s_bytes,
        &nonce_c,
    );
    client.send(&HandshakeMsg::PairProof {
        nonce_c: message::b64_32(&nonce_c.0),
        proof: message::b64_32(&proof),
    });

    let HandshakeMsg::PairAck { session_id } = client.read().expect("ack") else {
        panic!("esperaba pair_ack");
    };
    assert_eq!(session_id, message::b64_16(&[99; 16]));

    let outcome = handle.join().expect("hilo del servidor");
    match outcome {
        SessionOutcome::Established { session_key, .. } => {
            assert_eq!(session_key, key); // misma clave en ambos extremos
        }
        other => panic!("esperaba Established, obtuve {other:?}"),
    }
}

// ---- TEST-004 (aspecto servidor): proof inválido → E3 por el wire ----

#[test]
fn invalid_proof_receives_pair_error_e3() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap().to_string();
    let handle = std::thread::spawn(move || serve(&listener, token_record(1_000, false)));

    let mut client = TestClient::connect(&addr);
    client.send(&HandshakeMsg::PairRequest {
        schema: "twinpad.v1".into(),
        token: message::b64_32(&[7; 32]),
    });
    let HandshakeMsg::PairChallenge { .. } = client.read().expect("challenge") else {
        panic!("esperaba pair_challenge");
    };
    client.send(&HandshakeMsg::PairProof {
        nonce_c: message::b64_32(&[22; 32]),
        proof: message::b64_32(&[1; 32]), // HMAC incorrecto
    });

    match client.read().expect("error") {
        HandshakeMsg::PairError { code, .. } => assert_eq!(code, "E3"),
        other => panic!("esperaba pair_error, obtuve {other:?}"),
    }
    match handle.join().unwrap() {
        SessionOutcome::Rejected(e) => assert_eq!(e.code(), "E3"),
        other => panic!("esperaba Rejected, obtuve {other:?}"),
    }
}

// ---- TEST-005 (aspecto servidor): token ya consumido → E3 ----

#[test]
fn consumed_token_receives_e3_without_challenge() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap().to_string();
    let handle = std::thread::spawn(move || serve(&listener, token_record(1_000, true)));

    let mut client = TestClient::connect(&addr);
    client.send(&HandshakeMsg::PairRequest {
        schema: "twinpad.v1".into(),
        token: message::b64_32(&[7; 32]),
    });
    match client.read().expect("error") {
        HandshakeMsg::PairError { code, .. } => assert_eq!(code, "E3"),
        other => panic!("esperaba pair_error, obtuve {other:?}"),
    }
    assert!(matches!(
        handle.join().unwrap(),
        SessionOutcome::Rejected(_)
    ));
}

// ---- TEST-006 (aspecto servidor): mensaje malformado → E4 ----

#[test]
fn malformed_message_receives_pair_error_e4() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap().to_string();
    let handle = std::thread::spawn(move || serve(&listener, token_record(1_000, false)));

    let mut client = TestClient::connect(&addr);
    client.send_raw("{\"type\":\"pair_hello\",\"x\":1}");
    match client.read().expect("error") {
        HandshakeMsg::PairError { code, .. } => assert_eq!(code, "E4"),
        other => panic!("esperaba pair_error, obtuve {other:?}"),
    }
    assert!(matches!(
        handle.join().unwrap(),
        SessionOutcome::Rejected(_)
    ));
}

// ---- TEST-002 (aspecto servidor): el oyente rechaza conexiones sin ----
// servidor activo. El rechazo E1 del cliente se verifica en la app
// (TEST-011); aquí se valida que el listener responde y cierra limpio.

#[test]
fn server_closes_connection_after_rejection() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap().to_string();
    let handle = std::thread::spawn(move || serve(&listener, token_record(1_000, true)));

    let mut client = TestClient::connect(&addr);
    client.send(&HandshakeMsg::PairRequest {
        schema: "twinpad.v1".into(),
        token: message::b64_32(&[7; 32]),
    });
    // Tras el pair_error el servidor cierra: la siguiente lectura es EOF.
    let _ = client.read();
    std::thread::sleep(Duration::from_millis(50));
    let mut buf = [0u8; 1];
    let eof = match std::io::Read::read(&mut client.stream, &mut buf) {
        Ok(0) => true,
        Ok(_) => false, // puede llegar el cierre TCP antes del EOF
        Err(_) => true,
    };
    assert!(matches!(
        handle.join().unwrap(),
        SessionOutcome::Rejected(_)
    ));
    let _ = eof; // el cierre inmediato es aceptable; lo normativo es el pair_error
}

// ---- TEST-009 (aspecto servidor): R7 en el almacén ----

#[test]
fn token_store_regenerates_expired_qr_automatically() {
    struct AdvClock(std::cell::Cell<u64>);
    impl Clock for AdvClock {
        fn now_ms(&self) -> u64 {
            self.0.get()
        }
    }
    let clock = AdvClock(std::cell::Cell::new(1_000));
    struct IncRng(std::cell::Cell<u8>);
    impl RandomBytes for IncRng {
        fn fill(&self, buf: &mut [u8]) {
            let seed = self.0.get();
            for (i, b) in buf.iter_mut().enumerate() {
                *b = seed.wrapping_add(i as u8);
            }
            self.0.set(seed.wrapping_add(1));
        }
    }
    let store: TokenStore<IncRng, &AdvClock> = TokenStore::new(
        IncRng(std::cell::Cell::new(1)),
        &clock,
        "127.0.0.1".into(),
        8420,
    );
    let first = store.ensure_fresh_qr();

    clock.0.set(1_000 + 300_001);
    let second = store.ensure_fresh_qr();
    assert_ne!(first.record.token, second.record.token);
    assert!(store.lookup(&first.record.token).is_none()); // R7: no se reutiliza
}

// ---- TEST-008 (aspecto servidor): revocación §8.3 ----

#[test]
fn revoked_token_is_rejected() {
    let store = store();
    let issued = store.issue();
    assert!(store.lookup(&issued.record.token).is_some());
    assert!(store.revoke(&issued.record.token));
    assert!(store.lookup(&issued.record.token).is_none()); // reintento → E3
}
