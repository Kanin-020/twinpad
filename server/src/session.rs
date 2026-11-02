//! Sesión TCP del lado Servidor PC — alimenta `ServerHandshake` (DES-001 §6)
//! con mensajes NDJSON reales sobre el transporte LAN (ADR-002).
//!
//! Sin I/O propia de dominio: el reloj y el RNG llegan inyectados (ENG §5 P11);
//! este módulo solo traduce bytes ↔ mensajes y aplica los timeouts de §9.
//!
//! CR-056 (SPEC-001 §8.2): la PRIMERA línea de una conexión de control puede
//! ser `pair_resume` de una sesión retenida; en ese caso el servidor valida
//! el proof sobre la ligadura retenida y re-admite con el MISMO session_id.

use std::io::{BufRead, BufReader, Write};
use std::net::TcpStream;
use std::time::Duration;

use twinpad_core::error::{PairError, PairResult};
use twinpad_core::handshake::{ServerEntropy, ServerHandshake, StepIn, StepOut, TokenRecord};
use twinpad_core::message::{self, HandshakeMsg};
use twinpad_core::resume::{RetainedSession, evaluate_resume};
use twinpad_core::{Nonce, SessionId, SessionKey};

/// Timeout de espera de `pair_proof` tras el challenge: 10 s (SPEC-001 §9).
pub const PROOF_TIMEOUT: Duration = Duration::from_secs(10);

/// Retención de ligaduras de la sesión (CR-056, SPEC-001 §8.2): el adaptador
/// la materializa sobre el registro de sesiones. `take` consume la ligadura
/// retenida si sigue viva (§10: doble resume simultáneo → el segundo falla).
pub trait ControlRetention {
    fn take_retained(&self, session_id: &SessionId, now_ms: u64) -> Option<RetainedSession>;
}

/// Proveedores de entropía y reloj del servidor (inyectados, DES-001 §11).
pub struct SessionDeps<'a> {
    pub now_ms: &'a mut dyn FnMut() -> u64,
    pub next_nonce: &'a mut dyn FnMut() -> Nonce,
    pub next_session_id: &'a mut dyn FnMut() -> SessionId,
}

/// Resultado de un Handshake completo (§7/§9/§8.2).
#[derive(Debug)]
pub enum SessionOutcome {
    /// Handshake exitoso; el caller marca el token consumido (R2, §14).
    Established {
        session_id: SessionId,
        session_key: SessionKey,
    },
    /// Reanudación exitosa (§8.2, CR-056): ligadura retenida re-admitida con
    /// el MISMO session_id; el canal media anclado no se recredencia.
    Resumed {
        session_id: SessionId,
        session_key: SessionKey,
    },
    /// Rechazo: el `pair_error` ya fue enviado al cliente.
    Rejected(PairError),
}

/// Ejecuta una conexión de control completa: `pair_resume` (§8.2, CR-056) si
/// la primera línea lo es; `Handshake` normal (§7 pasos 4–11) en caso
/// contrario.
pub fn run_control_session(
    stream: &mut TcpStream,
    record: TokenRecord,
    retention: &dyn ControlRetention,
    deps: &mut SessionDeps<'_>,
) -> SessionOutcome {
    let Ok(reader_stream) = stream.try_clone() else {
        return SessionOutcome::Rejected(PairError::ServerUnavailable);
    };
    let mut reader = BufReader::new(reader_stream);
    // §7 paso 5 / §8.2: primera línea de la conexión.
    let first_line = match read_line(&mut reader) {
        Some(line) => line,
        None => return SessionOutcome::Rejected(PairError::ServerUnavailable),
    };
    let first_msg = match message::decode(&first_line) {
        Ok(msg) => msg,
        Err(e) => return reject(stream, e),
    };
    if matches!(first_msg, HandshakeMsg::PairResume { .. }) {
        return run_pair_resume(stream, &first_msg, retention, deps);
    }
    run_handshake_from(stream, &mut reader, record, deps, first_msg)
}

/// Compatibilidad: la forma anterior sin reanudación (los tests existentes).
pub fn run_handshake(
    stream: &mut TcpStream,
    record: TokenRecord,
    deps: &mut SessionDeps<'_>,
) -> SessionOutcome {
    let Ok(reader_stream) = stream.try_clone() else {
        return SessionOutcome::Rejected(PairError::ServerUnavailable);
    };
    let mut reader = BufReader::new(reader_stream);
    let first_line = match read_line(&mut reader) {
        Some(line) => line,
        None => return SessionOutcome::Rejected(PairError::ServerUnavailable),
    };
    let first_msg = match message::decode(&first_line) {
        Ok(msg) => msg,
        Err(e) => return reject(stream, e),
    };
    run_handshake_from(stream, &mut reader, record, deps, first_msg)
}

/// Reanudación §8.2 (CR-056): valida ligadura + ventana + proof y responde
/// `pair_ack` con el session_id retenido. §9: todo rechazo sale por la misma
/// puerta (sin desglosar la causa al cliente).
fn run_pair_resume(
    stream: &mut TcpStream,
    resume_msg: &HandshakeMsg,
    retention: &dyn ControlRetention,
    deps: &mut SessionDeps<'_>,
) -> SessionOutcome {
    let now = (deps.now_ms)();
    // session_id maldecodificado ya es E4 (el codec lo valida; si llegó
    // aquí, es válido) — la toma de retención exige el id decodificable.
    let twinpad_core::message::HandshakeMsg::PairResume { session_id, .. } = resume_msg else {
        return reject(stream, PairError::MalformedMessage);
    };
    let sid = match twinpad_core::resume::session_id_from_b64(session_id) {
        Ok(sid) => sid,
        Err(e) => return reject(stream, e),
    };
    let retained = retention.take_retained(&sid, now);
    match evaluate_resume(retained.as_ref(), resume_msg, now) {
        Ok((session_id, session_key)) => {
            let ack = HandshakeMsg::PairAck {
                session_id: message::b64_16(&session_id.0),
            };
            match message::encode(&ack).and_then(|l| write_line(stream, &l)) {
                Ok(()) => SessionOutcome::Resumed {
                    session_id,
                    session_key,
                },
                Err(_) => SessionOutcome::Rejected(PairError::ServerUnavailable),
            }
        }
        // §9 (CR-056): E3 sin desglosar causa (desconocida/expirada/proof).
        Err(_) => reject(stream, PairError::AuthRejected),
    }
}

/// Ejecuta el Handshake §7 con la línea inicial ya leída del socket.
fn run_handshake_from(
    stream: &mut TcpStream,
    reader: &mut BufReader<TcpStream>,
    record: TokenRecord,
    deps: &mut SessionDeps<'_>,
    request_msg: HandshakeMsg,
) -> SessionOutcome {
    let mut machine = ServerHandshake::new(record);

    // §7 pasos 6–7: validación + pair_challenge.
    let challenge = match machine.step(
        StepIn::Msg(&request_msg),
        (deps.now_ms)(),
        ServerEntropy {
            nonce_s: (deps.next_nonce)(),
            session_id: SessionId([0; 16]), // se reemplaza en el paso 10
        },
    ) {
        StepOut::Out(msg) => msg,
        StepOut::Rejected(e) => return reject(stream, e),
        _ => return reject(stream, PairError::AuthRejected),
    };
    if write_line(
        stream,
        &match message::encode(&challenge) {
            Ok(l) => l,
            Err(e) => return reject(stream, e),
        },
    )
    .is_err()
    {
        return SessionOutcome::Rejected(PairError::ServerUnavailable);
    }

    // §7 paso 8: pair_proof con timeout de 10 s (§9); vencido → E3 sin
    // consumir el token (§14).
    let proof_line = match read_line_with_timeout(reader, PROOF_TIMEOUT) {
        Some(line) => line,
        None => return reject(stream, PairError::AuthRejected),
    };
    let proof_msg = match message::decode(&proof_line) {
        Ok(msg) => msg,
        Err(e) => return reject(stream, e),
    };

    // §7 pasos 9–11: verificación del proof y pair_ack.
    match machine.step(
        StepIn::Msg(&proof_msg),
        (deps.now_ms)(),
        ServerEntropy {
            nonce_s: Nonce([0; 32]),
            session_id: (deps.next_session_id)(),
        },
    ) {
        StepOut::Established {
            session_id,
            session_key,
        } => {
            let ack = HandshakeMsg::PairAck {
                session_id: message::b64_16(&session_id.0),
            };
            match message::encode(&ack).and_then(|l| write_line(stream, &l)) {
                Ok(()) => SessionOutcome::Established {
                    session_id,
                    session_key,
                },
                Err(_) => SessionOutcome::Rejected(PairError::ServerUnavailable),
            }
        }
        StepOut::Rejected(e) => reject(stream, e),
        _ => reject(stream, PairError::AuthRejected),
    }
}

/// Envía `pair_error` con el código exacto (§4.2) y devuelve el rechazo.
fn reject(stream: &mut TcpStream, error: PairError) -> SessionOutcome {
    let payload = HandshakeMsg::PairError {
        code: error.code().to_string(),
        message: "Solicitud de vinculación rechazada".to_string(),
    };
    if let Ok(line) = message::encode(&payload) {
        let _ = write_line(stream, &line);
    }
    SessionOutcome::Rejected(error)
}

/// Lee una línea NDJSON; `None` = EOF o error de E/S (el caller decide E1/E3).
fn read_line(reader: &mut BufReader<TcpStream>) -> Option<String> {
    let mut buf = String::new();
    match reader.read_line(&mut buf) {
        Ok(0) | Err(_) => None,
        Ok(_) => Some(buf.trim_end_matches('\n').to_string()),
    }
}

/// Lee con `timeout`; `None` si vence (§9) o la conexión cae.
fn read_line_with_timeout(reader: &mut BufReader<TcpStream>, timeout: Duration) -> Option<String> {
    reader.get_ref().set_read_timeout(Some(timeout)).ok()?;
    let line = read_line(reader)?;
    // Un timeout vencido y un EOF llegan como error/None: el caller aplica §9.
    Some(line)
}

fn write_line(stream: &mut TcpStream, line: &str) -> PairResult<()> {
    stream
        .write_all(line.as_bytes())
        .and_then(|_| stream.write_all(b"\n"))
        .and_then(|_| stream.flush())
        .map_err(|_| PairError::ServerUnavailable)
}

// ENG §6: unwrap/expect/panic permitidos solo en tests (ámbito: este módulo).
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use twinpad_core::Token;

    struct NoRetention;
    impl ControlRetention for NoRetention {
        fn take_retained(&self, _session_id: &SessionId, _now_ms: u64) -> Option<RetainedSession> {
            None
        }
    }

    struct MapRetention {
        item: Option<RetainedSession>,
        at_ms: u64,
    }
    impl ControlRetention for MapRetention {
        fn take_retained(&self, session_id: &SessionId, now_ms: u64) -> Option<RetainedSession> {
            self.item
                .as_ref()
                .filter(|retained| retained.session_id == *session_id)
                .filter(|_| now_ms >= self.at_ms)
                .cloned()
        }
    }

    fn deps() -> SessionDeps<'static> {
        let now: Box<dyn FnMut() -> u64 + 'static> = Box::new(move || 1_000);
        let nonce: Box<dyn FnMut() -> Nonce + 'static> = Box::new(|| Nonce([0x11; 32]));
        let sid: Box<dyn FnMut() -> SessionId + 'static> = Box::new(|| SessionId([0x33; 16]));
        SessionDeps {
            now_ms: Box::leak(now),
            next_nonce: Box::leak(nonce),
            next_session_id: Box::leak(sid),
        }
    }

    fn record() -> TokenRecord {
        TokenRecord {
            token: Token([7; 32]),
            issued_at_ms: 1_000,
            consumed: false,
        }
    }

    /// Loopback: servidor pendiente de `accept` y cliente conectado.
    fn loopback_pair() -> (TcpStream, TcpStream) {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let client = TcpStream::connect(addr).unwrap();
        client.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let (server, _) = listener.accept().unwrap();
        (server, client)
    }

    fn retained_sid() -> SessionId {
        SessionId([0x55; 16])
    }

    fn retained_session() -> RetainedSession {
        RetainedSession {
            session_id: retained_sid(),
            session_key: SessionKey([9; 32]),
            retained_since_ms: 900,
        }
    }

    fn resume_line(nc: u8, proof: [u8; 32]) -> String {
        format!(
            "{{\"type\":\"pair_resume\",\"session_id\":\"{}\",\"nonce_c\":\"{}\",\"proof\":\"{}\"}}",
            message::b64_16(&retained_sid().0),
            message::b64_32(&[nc; 32]),
            message::b64_32(&proof)
        )    }    // TEST-0129 (server-side wiring) — verifies SPEC-001 §8.2: un
    // `pair_resume` válido re-admite la sesión con el MISMO session_id.
    #[test]
    fn valid_pair_resume_is_accepted_with_same_session_id() {
        let (mut server, mut client) = loopback_pair();
        let nc = [0x21u8; 32];
        let nonce_c = Nonce(nc);
        let proof =
            twinpad_core::resume::resume_proof(&SessionKey([9; 32]), &retained_sid(), &nonce_c);
        let line = format!("{}\n", resume_line(0x21, proof));
        client.write_all(line.as_bytes()).unwrap();
        let retention = MapRetention {
            item: Some(retained_session()),
            at_ms: 0,
        };
        let outcome = run_control_session(&mut server, record(), &retention, &mut deps());
        match outcome {
            SessionOutcome::Resumed {
                session_id,
                session_key,
            } => {
                assert_eq!(session_id, retained_sid());
                assert_eq!(session_key, SessionKey([9; 32]));
            }
            other => panic!("esperaba Resumed, obtuve {other:?}"),
        }
        // El cliente recibió pair_ack con el session_id retenido (§8.2).
        let mut buf = [0_u8; 225];
        let n = client.read(&mut buf).unwrap();
        let ack_line = String::from_utf8_lossy(&buf[..n]).to_string();
        assert!(ack_line.contains("pair_ack"));
        assert!(ack_line.contains(&message::b64_16(&retained_sid().0)));
    }

    // TEST-0129 (parte server) — verifies SPEC-001 §8.2/§9: ligadura
    // desconocida o ventana muerta en el take → E3 (sin desglosar causa).
    #[test]
    fn pair_resume_without_or_expired_retention_is_e3() {
        let nc = [0x22_u8; 32];
        let proof = twinpad_core::resume::resume_proof(
            &SessionKey([9; 32]),
            &retained_sid(),
            &Nonce(nc),
        );
        let line = format!("{}\n", resume_line(0x22, proof));

        // Caso A: sin ligadura retenida (servidor reiniciado).
        let (mut server_a, mut client_a) = loopback_pair();
        client_a.write_all(line.as_bytes()).unwrap();
        let outcome = run_control_session(
            &mut server_a,
            record(),
            &MapRetention { item: None, at_ms: 0 },
            &mut deps(),
        );
        assert!(matches!(
            outcome,
            SessionOutcome::Rejected(PairError::AuthRejected)
        ));
        let mut buf = [0_u8; 128];
        let n = client_a.read(&mut buf).unwrap();
        assert!(String::from_utf8_lossy(&buf[..n]).contains("\"E3\""));

        // Caso B: take exige retención vigente (at_ms posterior al reloj del
        // server) → None → E3.
        let (mut server_b, mut client_b) = loopback_pair();
        client_b.write_all(line.as_bytes()).unwrap();
        let outcome_b = run_control_session(
            &mut server_b,
            record(),
            &MapRetention {
                item: Some(retained_session()),
                at_ms: 2_000, // el reloj del test fija now = 1_000
            },
            &mut deps(),
        );
        assert!(matches!(
            outcome_b,
            SessionOutcome::Rejected(PairError::AuthRejected)
        ));
        let n2 = client_b.read(&mut buf).unwrap();
        assert!(String::from_utf8_lossy(&buf[..n2]).contains("\"E3\""));
    }

    // TEST-0129 (parte server) — verifies SPEC-001 §8.2: proof forjado → E3.
    #[test]
    fn pair_resume_with_forged_proof_is_e3() {
        let (mut server, mut client) = loopback_pair();
        let mut proof = twinpad_core::resume::resume_proof(
            &SessionKey([9; 32]),
            &retained_sid(),
            &Nonce([0x23u8; 32]),
        );
        proof[0] ^= 0x01;
        let line = resume_line(0x23, proof);
        client.write_all(line.as_bytes()).unwrap();
        client.write_all(b"\n").unwrap();
        let outcome = run_control_session(
            &mut server,
            record(),
            &MapRetention {
                item: Some(retained_session()),
                at_ms: 0,
            },
            &mut deps(),
        );
        assert!(matches!(outcome, SessionOutcome::Rejected(PairError::AuthRejected)));
        let mut buf = [0_u8; 128];
        let n = client.read(&mut buf).unwrap();
        assert!(String::from_utf8_lossy(&buf[..n]).contains("\"E3\""));
    }

    // §7 sin cambios: la primera línea puede seguir siendo `pair_request`.
    #[test]
    fn first_line_pair_request_uses_normal_handshake() {
        let (mut server, mut client) = loopback_pair();
        let token = message::b64_32(&Token([7; 32]).0);
        let line = format!(
            "{{\"type\":\"pair_request\",\"schema\":\"twinpad.v1\",\"token\":\"{token}\"}}"
        );
        client.write_all(line.as_bytes()).unwrap();
        client.write_all(b"\n").unwrap();

        let outcome = std::thread::scope(|scope| {
            let server_job =
                scope.spawn(|| run_control_session(&mut server, record(), &NoRetention, &mut deps()));
            // Cliente: challenge → proof real (mismo core) → espera pair_ack.
            use std::io::BufRead;
            let mut reader = std::io::BufReader::new(&client);
            let mut split = String::new();
            reader.read_line(&mut split).unwrap();
            let mut client_machine =
                twinpad_core::handshake::ClientHandshake::start(&token).unwrap().0;
            // rebuild: start() genera el pair_request; aquí YA se envió la
            // misma línea, así que reproducimos el paso del challenge sobre
            // la máquina del cliente con el nonce del server.
            let HandshakeMsg::PairChallenge { ref nonce_s } =
                message::decode(split.trim_end()).unwrap()
            else {
                panic!(" esperaba challenge");
            };
            let nonce_c = Nonce([0x77_u8; 32]);
            let proof = match client_machine.step(
                StepIn::Msg(&HandshakeMsg::PairChallenge {
                    nonce_s: nonce_s.clone(),
                }),
                nonce_c,
            ) {
                StepOut::Out(msg) => msg,
                other => panic!("esperaba proof, obtuve {other:?}"),
            };
            client.write_all(message::encode(&proof).unwrap().as_bytes()).unwrap();
            client.write_all(b"\n").unwrap();
            let mut ack = String::new();
            std::io::BufReader::new(&client).read_line(&mut ack).unwrap();
            let outcome = server_job.join().unwrap();
            (outcome, ack)
        });
        match outcome.0 {
            SessionOutcome::Established { session_id, .. } => {
                assert_eq!(session_id, SessionId([0x33; 16]));
            }
            other => panic!("esperaba Established, obtuve {other:?}"),
        }
        assert!(outcome.1.contains("pair_ack"));
    }

    /// smoke: primera línea inexistente (EOF inmediato) → E1/rechazo limpio.
    #[test]
    fn empty_connection_is_rejected() {
        let (mut server, client) = loopback_pair();
        drop(client);
        let outcome = run_control_session(&mut server, record(), &NoRetention, &mut deps());
        assert!(matches!(
            outcome,
            SessionOutcome::Rejected(PairError::AuthRejected)
                | SessionOutcome::Rejected(PairError::ServerUnavailable)
        ));
    }
}
