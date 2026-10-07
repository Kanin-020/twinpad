//! Sesión TCP del lado Servidor PC — alimenta `ServerHandshake` (DES-001 §6)
//! con mensajes NDJSON reales sobre el transporte LAN (ADR-002).
//!
//! Sin I/O propia de dominio: el reloj y el RNG llegan inyectados (ENG §5 P11);
//! este módulo solo traduce bytes ↔ mensajes y aplica los timeouts de §9.

use std::io::{BufRead, BufReader, Write};
use std::net::TcpStream;
use std::time::Duration;

use twinpad_core::error::{PairError, PairResult};
use twinpad_core::handshake::{ServerEntropy, ServerHandshake, StepIn, StepOut, TokenRecord};
use twinpad_core::message::{self, HandshakeMsg};
use twinpad_core::{Nonce, SessionId, SessionKey};

/// Timeout de espera de `pair_proof` tras el challenge: 10 s (SPEC-001 §9).
pub const PROOF_TIMEOUT: Duration = Duration::from_secs(10);

/// Proveedores de entropía y reloj del servidor (inyectados, DES-001 §11).
pub struct SessionDeps<'a> {
    pub now_ms: &'a mut dyn FnMut() -> u64,
    pub next_nonce: &'a mut dyn FnMut() -> Nonce,
    pub next_session_id: &'a mut dyn FnMut() -> SessionId,
}

/// Resultado de un Handshake completo (§7/§9).
#[derive(Debug)]
pub enum SessionOutcome {
    /// Handshake exitoso; el caller marca el token consumido (R2, §14).
    Established {
        session_id: SessionId,
        session_key: SessionKey,
    },
    /// Rechazo: el `pair_error` ya fue enviado al cliente.
    Rejected(PairError),
}

/// Ejecuta un Handshake completo sobre `stream` (§7 pasos 4–11).
pub fn run_handshake(
    stream: &mut TcpStream,
    record: TokenRecord,
    deps: &mut SessionDeps<'_>,
) -> SessionOutcome {
    let mut machine = ServerHandshake::new(record);
    let Ok(reader_stream) = stream.try_clone() else {
        return SessionOutcome::Rejected(PairError::ServerUnavailable);
    };
    let mut reader = BufReader::new(reader_stream);

    // §7 paso 5: pair_request.
    let request = match read_line(&mut reader) {
        Some(line) => line,
        None => return SessionOutcome::Rejected(PairError::ServerUnavailable),
    };
    let request_msg = match message::decode(&request) {
        Ok(msg) => msg,
        Err(e) => return reject(stream, e),
    };

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
    let proof_line = match read_line_with_timeout(&mut reader, PROOF_TIMEOUT) {
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
