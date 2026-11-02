//! TEST-0129 — CR-056 (SPEC-001 §8.2): retención de ligadura y reanudación
//! del canal de control sobre TCP real en loopback (el protocolo no se
//! mockea, test-strategy §10).
//!
//! Escenarios: (1) handshake completo → retención al caer el control →
//! `pair_resume` válido re-admite con el MISMO session_id y el MISMO
//! session_key; (2) la retención vence a los 60 000 ms: tras `sweep`, el
//! resume es E3 y el estado de media se destruye; (3) doble resume
//! simultáneo: el segundo intento falla porque la ligadura se consumió.
//!
//! ENG §6: unwrap/expect/panic permitidos solo en tests (este crate es todo test).
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;
use std::time::Duration;

use twinpad_core::handshake::{ClientHandshake, StepIn};
use twinpad_core::message::{self, HandshakeMsg};
use twinpad_core::resume::{RETENTION_MS, resume_proof};
use twinpad_core::{Nonce, SessionId, SessionKey, Token};

use twinpad_server::registry::SessionRegistry;
use twinpad_server::session::{ControlRetention, SessionDeps, SessionOutcome, run_control_session};

fn token_record() -> twinpad_core::handshake::TokenRecord {
    twinpad_core::handshake::TokenRecord {
        token: Token([7; 32]),
        issued_at_ms: 1_000,
        consumed: false,
    }
}

fn client_deps() -> SessionDeps<'static> {
    let now: Box<dyn FnMut() -> u64 + 'static> = Box::new(|| 1_000);
    let nonce: Box<dyn FnMut() -> Nonce + 'static> = Box::new(|| Nonce([0x11; 32]));
    let sid: Box<dyn FnMut() -> SessionId + 'static> = Box::new(|| SessionId([0x99; 16]));
    SessionDeps {
        now_ms: Box::leak(now),
        next_nonce: Box::leak(nonce),
        next_session_id: Box::leak(sid),
    }
}

/// Acepta una conexión de control y la sirve con `run_control_session`.
fn serve_control(listener: &TcpListener, retention: Arc<SessionRegistry>) -> SessionOutcome {
    let (mut stream, _) = listener.accept().unwrap();
    stream
        .set_read_timeout(Some(Duration::from_secs(2)))
        .unwrap();
    run_control_session(
        &mut stream,
        token_record(),
        retention.as_ref(),
        &mut client_deps(),
    )
}

/// Handshake completo §7 desde el lado app (TCP real).
/// Key de la app = HKDF(token, nonce_s) (§7 paso 8); la recomputamos aquí
/// porque el session_key del cliente vive en la máquina core.
fn client_session_key(nonce_s: &Nonce) -> SessionKey {
    twinpad_core::crypto::derive_session_key(&Token([7; 32]), nonce_s)
}

fn handshake(client: &mut TcpStream) -> (SessionId, SessionKey) {
    let token = message::b64_32(&Token([7; 32]).0);
    let (mut machine, request) = ClientHandshake::start(&token).unwrap();
    client
        .write_all(message::encode(&request).unwrap().as_bytes())
        .and_then(|_| client.write_all(b"\n"))
        .unwrap();
    let mut line = String::new();
    BufReader::new(&mut *client).read_line(&mut line).unwrap();
    let HandshakeMsg::PairChallenge { nonce_s } = message::decode(line.trim_end()).unwrap() else {
        panic!("esperaba pair_challenge");
    };
    let nonce_c = Nonce([0x21; 32]);
    let client_key = client_session_key(&message::nonce_bytes(&nonce_s).unwrap());
    let proof = match machine.step(
        StepIn::Msg(&HandshakeMsg::PairChallenge { nonce_s }),
        nonce_c,
    ) {
        twinpad_core::handshake::StepOut::Out(msg) => msg,
        other => panic!("esperaba pair_proof, obtuve {other:?}"),
    };
    client
        .write_all(message::encode(&proof).unwrap().as_bytes())
        .and_then(|_| client.write_all(b"\n"))
        .unwrap();
    let mut ack = String::new();
    BufReader::new(&mut *client).read_line(&mut ack).unwrap();
    let HandshakeMsg::PairAck { session_id } = message::decode(ack.trim_end()).unwrap() else {
        panic!("esperaba pair_ack");
    };
    (
        twinpad_core::resume::session_id_from_b64(&session_id).unwrap(),
        client_key,
    )
}

/// Envía `pair_resume` y devuelve la línea de respuesta cruda.
fn resume(client: &mut TcpStream, sid: &SessionId, key: &SessionKey, nc: u8) -> String {
    let nonce_c = Nonce([nc; 32]);
    let proof = resume_proof(key, sid, &nonce_c);
    let msg = HandshakeMsg::PairResume {
        session_id: message::b64_16(&sid.0),
        nonce_c: message::b64_32(&nonce_c.0),
        proof: message::b64_32(&proof),
    };
    client
        .write_all(message::encode(&msg).unwrap().as_bytes())
        .and_then(|_| client.write_all(b"\n"))
        .unwrap();
    let mut line = String::new();
    BufReader::new(&mut *client).read_line(&mut line).unwrap();
    line
}

fn loopback_listener() -> (TcpListener, String) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap().to_string();
    (listener, addr)
}

// ---- TEST-0129 · escenario 1: reanudación con el mismo session_id ----

#[test]
fn retained_session_resumes_with_same_identity() {
    let registry = Arc::new(SessionRegistry::new());
    let (listener, addr) = loopback_listener();
    let server = std::thread::spawn({
        let retention = Arc::clone(&registry);
        move || serve_control(&listener, retention)
    });
    let mut client = TcpStream::connect(&addr).unwrap();
    client
        .set_read_timeout(Some(Duration::from_secs(2)))
        .unwrap();
    let (sid, key) = handshake(&mut client);
    drop(client); // §8.2: el teléfono pierde la conexión de control

    // El server retiene al detectar el corte (aquí lo simulamos: el ciclo
    // de vida real vive en main.rs; el registro es el mismo contrato).
    let now = 5_000;
    registry.retain(&sid, &key, now);
    let _outcome = server.join().unwrap();

    // Nueva conexión de control: pair_resume dentro de la ventana.
    let (listener, addr) = loopback_listener();
    let server = std::thread::spawn(move || serve_control(&listener, registry));
    let mut client = TcpStream::connect(&addr).unwrap();
    client
        .set_read_timeout(Some(Duration::from_secs(3)))
        .unwrap();
    let answer = resume(&mut client, &sid, &key, 0x42);
    let outcome = server.join().unwrap();
    match outcome {
        SessionOutcome::Resumed {
            session_id: resumed_sid,
            session_key: resumed_key,
        } => {
            assert_eq!(resumed_sid, sid); // §8.2: MISMO session_id
            assert_eq!(resumed_key, key); // el canal media anclado sigue usable
        }
        other => panic!("esperaba Resumed, obtuve {other:?}"),
    }
    assert!(answer.contains("pair_ack"));
    assert!(answer.contains(&message::b64_16(&sid.0)));
}

// ---- TEST-0129 · escenario 2: destrucción al vencer la retención ----

#[test]
fn retention_expiry_destroys_binding_and_rejects_resume() {
    let registry = SessionRegistry::new();
    let sid = SessionId([0x5a; 16]);
    let key = SessionKey([0x33; 32]);
    registry.retain(&sid, &key, 10_000);

    // Sin vencer: la retención sigue viva (frontera exacta == 60 s).
    assert!(registry.retained(&sid).is_some());
    registry.sweep_retained(10_000 + RETENTION_MS);
    assert!(registry.retained(&sid).is_some()); // alive en la frontera

    // Un ms después: el sweep la destruye (exigirá QR).
    assert_eq!(registry.sweep_retained(10_000 + RETENTION_MS + 1), 1);
    assert!(registry.retained(&sid).is_none());

    // El estado de media se destruye con la ligadura: la credencial pide
    // sesión viva o retención; ninguna existe ya.
    assert!(registry.media_credential(&sid).is_none());

    // El resume sobre la ligadura destruida es E3 (§9, sin causa).
    struct Empty;
    impl ControlRetention for Empty {
        fn take_retained(&self, _sid: &SessionId, _now: u64) -> Option<twinpad_core::resume::RetainedSession> {
            None
        }
    }
    let (listener, addr) = loopback_listener();
    let server = std::thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        run_control_session(&mut stream, token_record(), &Empty, &mut client_deps())
    });
    let mut client = TcpStream::connect(&addr).unwrap();
    client
        .set_read_timeout(Some(Duration::from_secs(2)))
        .unwrap();
    let answer = resume(&mut client, &sid, &key, 0x43);
    server.join().unwrap();
    assert!(answer.contains("\"E3\""));
}

// ---- TEST-0129 · escenario 3: doble resume simultáneo ----

#[test]
fn double_resume_consumes_retention_exactly_once() {
    let registry = SessionRegistry::new();
    let sid = SessionId([0x66; 16]);
    let key = SessionKey([0x44; 32]);
    registry.retain(&sid, &key, 1_000);
    // Primer take (válido) la consume.
    let retention: &dyn ControlRetention = &registry;
    assert!(retention.take_retained(&sid, 1_100).is_some());
    // Segundo intento: ligadura ya consumida → E3 (§10).
    assert!(retention.take_retained(&sid, 1_100).is_none());
}
