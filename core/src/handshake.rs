//! Máquinas de estado del Handshake — SPEC-001 §6/§7, DES-001 §3/§6.
//!
//! Puras: reciben mensajes decodificados, reloj (`now_ms`) y entropía por
//! parámetro; devuelven salidas tipadas. El adaptador TCP (`server/`) las
//! alimenta y aplica los timeouts de conexión (E1).

use crate::crypto;
use crate::error::{PairError, PairResult};
use crate::message::{self, HandshakeMsg, SCHEMA};
use crate::qr;
use crate::{Nonce, SessionId, SessionKey, Token};

/// Timeout de espera de `pair_proof`: 10 s (SPEC-001 §9). Al vencer, el
/// token NO queda consumido (§14).
pub const PROOF_TIMEOUT_MS: u64 = 10_000;

/// Registro lógico de un token emitido (lo posee el adaptador `server/`;
/// DES-001 §5). `consumed` es la materialización de R2.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TokenRecord {
    pub token: Token,
    /// Emisión según el reloj del servidor (R3: sin reloj del teléfono).
    pub issued_at_ms: u64,
    pub consumed: bool,
}

/// Entropía fresca que el adaptador entrega en cada `step` del servidor.
#[derive(Clone, Copy, Debug)]
pub struct ServerEntropy {
    pub nonce_s: Nonce,
    pub session_id: SessionId,
}

/// Entrada de un `step`: mensaje decodificado o pulso de reloj.
#[derive(Clone, Copy, Debug)]
pub enum StepIn<'a> {
    Msg(&'a HandshakeMsg),
    Tick,
}

/// Salida de un `step`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum StepOut {
    /// Enviar este mensaje por el canal.
    Out(HandshakeMsg),
    /// Sin novedad; esperar más entrada.
    NeedMore,
    /// Handshake exitoso (SPEC-001 §7 pasos 10–11).
    Established {
        session_id: SessionId,
        session_key: SessionKey,
    },
    /// Rechazo: el adaptador envía `pair_error` y cierra la conexión.
    Rejected(PairError),
}

enum ServerState {
    WaitRequest,
    WaitProof {
        nonce_s: Nonce,
        session_key: SessionKey,
        req_line: String,
        challenge_line: String,
        since_ms: u64,
    },
    Established,
}

/// Máquina del lado Servidor PC (SPEC-001 §7, §9, §14).
pub struct ServerHandshake {
    record: TokenRecord,
    state: ServerState,
}

impl ServerHandshake {
    pub fn new(record: TokenRecord) -> Self {
        Self {
            record,
            state: ServerState::WaitRequest,
        }
    }

    /// Estado del token visto por esta máquina (el registro del adaptador
    /// debe sincronizarse tras `Established` vía `into_record`).
    pub fn token_record(&self) -> &TokenRecord {
        &self.record
    }

    /// Devuelve el registro actualizado (consumo único R2).
    pub fn into_record(self) -> TokenRecord {
        self.record
    }

    /// Avanza la máquina (DES-001 §6). Un único consumo por token;
    /// intentos fallidos no lo consumen (SPEC-001 §14).
    pub fn step(&mut self, input: StepIn<'_>, now_ms: u64, entropy: ServerEntropy) -> StepOut {
        let (out, next, consume) = self.evaluate(input, now_ms, entropy);
        if consume {
            self.record.consumed = true; // R2: un solo uso
        }
        if let Some(next) = next {
            self.state = next;
        }
        out
    }

    /// Clasifica la entrada sin mutar `self`; devuelve la salida, el
    /// próximo estado (si cambia) y si el token se consume (R2).
    fn evaluate(
        &self,
        input: StepIn<'_>,
        now_ms: u64,
        entropy: ServerEntropy,
    ) -> (StepOut, Option<ServerState>, bool) {
        match (&self.state, input) {
            (
                ServerState::WaitRequest,
                StepIn::Msg(HandshakeMsg::PairRequest { schema, token }),
            ) => self.evaluate_request(schema, token, now_ms, entropy),
            (ServerState::WaitRequest, StepIn::Msg(_)) => {
                (StepOut::Rejected(PairError::AuthRejected), None, false)
            }
            (ServerState::WaitRequest, StepIn::Tick) => (StepOut::NeedMore, None, false),
            (
                ServerState::WaitProof {
                    nonce_s,
                    session_key,
                    req_line,
                    challenge_line,
                    since_ms,
                },
                StepIn::Msg(HandshakeMsg::PairProof { nonce_c, proof }),
            ) => {
                if proof_timed_out(*since_ms, now_ms) {
                    // §14: el intento vencido no consume el token.
                    return (
                        StepOut::Rejected(PairError::AuthRejected),
                        Some(ServerState::WaitRequest),
                        false,
                    );
                }
                evaluate_proof(
                    nonce_s,
                    session_key,
                    req_line,
                    challenge_line,
                    nonce_c,
                    proof,
                    entropy,
                )
            }
            (ServerState::WaitProof { .. }, StepIn::Msg(_)) => {
                (StepOut::Rejected(PairError::AuthRejected), None, false)
            }
            (ServerState::WaitProof { since_ms, .. }, StepIn::Tick) => {
                if proof_timed_out(*since_ms, now_ms) {
                    (
                        StepOut::Rejected(PairError::AuthRejected),
                        Some(ServerState::WaitRequest),
                        false,
                    )
                } else {
                    (StepOut::NeedMore, None, false)
                }
            }
            (ServerState::Established, _) => {
                (StepOut::Rejected(PairError::AuthRejected), None, false)
            }
        }
    }

    /// SPEC-001 §7 pasos 6–7: valida schema/token/expiración y desafía.
    fn evaluate_request(
        &self,
        schema: &str,
        token: &str,
        now_ms: u64,
        entropy: ServerEntropy,
    ) -> (StepOut, Option<ServerState>, bool) {
        if self.record.consumed
            || schema != SCHEMA
            || token != message::b64_32(&self.record.token.0)
        {
            return (StepOut::Rejected(PairError::AuthRejected), None, false); // R2 / token desconocido
        }
        if crypto::is_expired(self.record.issued_at_ms, now_ms) {
            return (StepOut::Rejected(PairError::TokenExpired), None, false); // R3 → E2
        }
        let Ok(token_bytes) = message::token_bytes(token) else {
            return (StepOut::Rejected(PairError::MalformedMessage), None, false);
        };
        let session_key = crypto::derive_session_key(&token_bytes, &entropy.nonce_s);
        let req_line = match message::encode(&HandshakeMsg::PairRequest {
            schema: SCHEMA.to_string(),
            token: token.to_string(),
        }) {
            Ok(line) => line,
            Err(e) => return (StepOut::Rejected(e), None, false),
        };
        let nonce_s = entropy.nonce_s;
        let Ok(challenge_line) = message::encode(&HandshakeMsg::PairChallenge {
            nonce_s: message::b64_32(&nonce_s.0),
        }) else {
            return (StepOut::Rejected(PairError::MalformedMessage), None, false);
        };
        let state = ServerState::WaitProof {
            nonce_s,
            session_key,
            req_line,
            challenge_line,
            since_ms: now_ms,
        };
        (
            StepOut::Out(HandshakeMsg::PairChallenge {
                nonce_s: message::b64_32(&nonce_s.0),
            }),
            Some(state),
            false,
        )
    }
}

/// SPEC-001 §7 pasos 8–10: verifica el proof en tiempo constante; consume
/// solo en éxito (§14).
fn evaluate_proof(
    nonce_s: &Nonce,
    session_key: &SessionKey,
    req_line: &str,
    challenge_line: &str,
    nonce_c: &str,
    proof: &str,
    entropy: ServerEntropy,
) -> (StepOut, Option<ServerState>, bool) {
    let (nonce_c, proof) = match (message::nonce_bytes(nonce_c), decode_proof(proof)) {
        (Ok(nc), Ok(p)) => (nc, p),
        (Err(e), _) | (_, Err(e)) => return (StepOut::Rejected(e), None, false),
    };
    if crypto::verify_proof(
        session_key,
        req_line,
        challenge_line,
        nonce_s,
        &nonce_c,
        &proof,
    ) {
        (
            StepOut::Established {
                session_id: entropy.session_id,
                session_key: *session_key,
            },
            Some(ServerState::Established),
            true, // R2: el token se consume exactamente aquí
        )
    } else {
        (
            StepOut::Rejected(PairError::AuthRejected),
            Some(ServerState::WaitRequest), // el token NO se consume (§14)
            false,
        )
    }
}

fn proof_timed_out(since_ms: u64, now_ms: u64) -> bool {
    now_ms.saturating_sub(since_ms) > PROOF_TIMEOUT_MS
}

fn decode_proof(proof: &str) -> Result<Vec<u8>, PairError> {
    message::nonce_bytes(proof).map(|n| n.0.to_vec())
}

enum ClientState {
    /// Token validado; `pair_request` ya emitido.
    Start {
        token_str: String,
        req_line: String,
    },
    /// `pair_proof` enviado; esperando `pair_ack`.
    WaitAck {
        session_key: SessionKey,
    },
    Established,
}

/// Máquina del lado App Android (SPEC-001 §7/§8.1).
pub struct ClientHandshake {
    state: ClientState,
}

impl ClientHandshake {
    /// Inicia el Handshake con el token del QR (SPEC-001 §7 pasos 2–5).
    /// Token inválido → E5 (viene del payload del QR).
    pub fn start(token_str: &str) -> PairResult<(Self, HandshakeMsg)> {
        qr::validate_token(token_str)?;
        let req = HandshakeMsg::PairRequest {
            schema: SCHEMA.to_string(),
            token: token_str.to_string(),
        };
        let req_line = message::encode(&req)?;
        Ok((
            Self {
                state: ClientState::Start {
                    token_str: token_str.to_string(),
                    req_line,
                },
            },
            req,
        ))
    }

    /// Avanza la máquina del cliente. `nonce_c` entra por parámetro
    /// (el RNG vive en el adaptador). Sin timeout propio: los timeouts de
    /// conexión (E1) los aplica el adaptador.
    pub fn step(&mut self, input: StepIn<'_>, nonce_c: Nonce) -> StepOut {
        let (out, next) = self.evaluate(input, nonce_c);
        if let Some(next) = next {
            self.state = next;
        }
        out
    }

    /// Clasifica sin mutar; devuelve salida y próximo estado (si cambia).
    fn evaluate(&self, input: StepIn<'_>, nonce_c: Nonce) -> (StepOut, Option<ClientState>) {
        match (&self.state, input) {
            (
                ClientState::Start {
                    token_str,
                    req_line,
                },
                StepIn::Msg(HandshakeMsg::PairChallenge { nonce_s }),
            ) => {
                let (nonce_s, token) = match (
                    message::nonce_bytes(nonce_s),
                    message::token_bytes(token_str),
                ) {
                    (Ok(ns), Ok(t)) => (ns, t),
                    (Err(e), _) | (_, Err(e)) => return (StepOut::Rejected(e), None),
                };
                let session_key = crypto::derive_session_key(&token, &nonce_s);
                let challenge_line = match message::encode(&HandshakeMsg::PairChallenge {
                    nonce_s: message::b64_32(&nonce_s.0),
                }) {
                    Ok(line) => line,
                    Err(e) => return (StepOut::Rejected(e), None),
                };
                let proof = crypto::compute_proof(
                    &session_key,
                    req_line,
                    &challenge_line,
                    &nonce_s,
                    &nonce_c,
                );
                (
                    StepOut::Out(HandshakeMsg::PairProof {
                        nonce_c: message::b64_32(&nonce_c.0),
                        proof: message::b64_32(&proof),
                    }),
                    Some(ClientState::WaitAck { session_key }),
                )
            }
            // `pair_error` del servidor se mapea desde cualquier estado
            // pre-establecido (SPEC-001 §9: el cliente muestra el mensaje).
            (
                ClientState::Start { .. } | ClientState::WaitAck { .. },
                StepIn::Msg(HandshakeMsg::PairError { code, .. }),
            ) => (StepOut::Rejected(PairError::from_code(code)), None),
            (ClientState::Start { .. }, StepIn::Msg(_)) => {
                (StepOut::Rejected(PairError::AuthRejected), None)
            }
            (ClientState::Start { .. }, StepIn::Tick) => (StepOut::NeedMore, None),
            (
                ClientState::WaitAck { session_key },
                StepIn::Msg(HandshakeMsg::PairAck { session_id }),
            ) => {
                let session_id = match decode_session_id(session_id) {
                    Ok(id) => id,
                    Err(e) => return (StepOut::Rejected(e), None),
                };
                (
                    StepOut::Established {
                        session_id,
                        session_key: *session_key,
                    },
                    Some(ClientState::Established),
                )
            }
            (ClientState::WaitAck { .. }, StepIn::Msg(_)) => {
                (StepOut::Rejected(PairError::AuthRejected), None)
            }
            (ClientState::WaitAck { .. }, StepIn::Tick) => (StepOut::NeedMore, None),
            (ClientState::Established, _) => (StepOut::Rejected(PairError::AuthRejected), None),
        }
    }
}

fn decode_session_id(value: &str) -> Result<SessionId, PairError> {
    let bytes = qr::decode_token(value).map_err(|_| PairError::MalformedMessage)?;
    let arr: [u8; 16] = bytes.try_into().map_err(|_| PairError::MalformedMessage)?;
    Ok(SessionId(arr))
}

// ENG §6: unwrap/expect/panic permitidos solo en tests (ámbito: este módulo).
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;
    use crate::{Nonce, SessionId, Token};

    fn token() -> Token {
        Token([7u8; 32])
    }

    /// Representación base64url canónica del token de prueba (el QR
    /// siempre la genera canónica; DES-001 §4).
    fn token_str() -> String {
        message::b64_32(&token().0)
    }
    fn record(issued_at_ms: u64, consumed: bool) -> TokenRecord {
        TokenRecord {
            token: token(),
            issued_at_ms,
            consumed,
        }
    }
    fn entropy(v: u8) -> ServerEntropy {
        ServerEntropy {
            nonce_s: Nonce([v; 32]),
            session_id: SessionId([v.wrapping_add(100); 16]),
        }
    }
    fn nonce_c(v: u8) -> Nonce {
        Nonce([v; 32])
    }

    // TEST-001 — verifies SPEC-001 (§7: flujo principal del Handshake).
    #[test]
    fn main_flow_establishes_session_with_matching_keys() {
        let mut server = ServerHandshake::new(record(1_000, false));
        let (mut client, request) = ClientHandshake::start(&token_str()).unwrap();

        let challenge = match server.step(StepIn::Msg(&request), 1_001, entropy(10)) {
            StepOut::Out(m) => m,
            other => panic!("esperaba challenge, obtuve {other:?}"),
        };
        let proof = match client.step(StepIn::Msg(&challenge), nonce_c(20)) {
            StepOut::Out(m) => m,
            other => panic!("esperaba proof, obtuve {other:?}"),
        };
        let server_out = server.step(StepIn::Msg(&proof), 1_002, entropy(10));
        let (server_key, server_sid) = match server_out {
            StepOut::Established {
                session_id,
                session_key,
            } => (session_key, session_id),
            other => panic!("esperaba Established, obtuve {other:?}"),
        };
        assert!(server.token_record().consumed); // R2

        let ack = HandshakeMsg::PairAck {
            session_id: message::b64_16(&server_sid.0),
        };
        let client_out = client.step(StepIn::Msg(&ack), nonce_c(20));
        match client_out {
            StepOut::Established {
                session_id,
                session_key,
            } => {
                assert_eq!(session_id, server_sid);
                assert_eq!(session_key, server_key); // misma clave en ambos extremos
            }
            other => panic!("esperaba Established del cliente, obtuve {other:?}"),
        }
    }

    // TEST-003 — verifies SPEC-001 (E2: QR expirado, frontera 300 s).
    #[test]
    fn expired_token_is_e2_and_boundary_300s_is_valid() {
        let mut server = ServerHandshake::new(record(1_000, false));
        let (mut client, request) = ClientHandshake::start(&token_str()).unwrap();
        let _ = &mut client;
        // > 300 s desde la emisión
        assert_eq!(
            server.step(StepIn::Msg(&request), 1_000 + 300_001, entropy(10)),
            StepOut::Rejected(PairError::TokenExpired)
        );
        // exactamente 300 s → sigue vigente y desafía
        let mut server = ServerHandshake::new(record(1_000, false));
        assert!(matches!(
            server.step(StepIn::Msg(&request), 1_000 + 300_000, entropy(10)),
            StepOut::Out(HandshakeMsg::PairChallenge { .. })
        ));
    }

    // TEST-004 — verifies SPEC-001 (E3: proof inválido / token consumido).
    #[test]
    fn invalid_proof_rejects_e3_without_consuming_token() {
        let mut server = ServerHandshake::new(record(1_000, false));
        let (mut client, request) = ClientHandshake::start(&token_str()).unwrap();
        let _ = &mut client;
        let challenge = match server.step(StepIn::Msg(&request), 1_001, entropy(10)) {
            StepOut::Out(m) => m,
            other => panic!("esperaba challenge, obtuve {other:?}"),
        };
        let _ = client.step(StepIn::Msg(&challenge), nonce_c(20));
        // proof alterado: reemplazar por bytes de otro nonce
        let forged = HandshakeMsg::PairProof {
            nonce_c: message::b64_32(&nonce_c(20).0),
            proof: message::b64_32(&nonce_c(99).0),
        };
        assert_eq!(
            server.step(StepIn::Msg(&forged), 1_002, entropy(10)),
            StepOut::Rejected(PairError::AuthRejected)
        );
        assert!(!server.token_record().consumed); // §14: intento fallido no consume
    }

    #[test]
    fn consumed_token_is_e3_for_new_handshake() {
        let mut rec = record(1_000, false);
        let mut server = ServerHandshake::new(rec.clone());
        let (mut client, request) = ClientHandshake::start(&token_str()).unwrap();
        let challenge = match server.step(StepIn::Msg(&request), 1_001, entropy(10)) {
            StepOut::Out(m) => m,
            other => panic!("esperaba challenge, obtuve {other:?}"),
        };
        let proof = match client.step(StepIn::Msg(&challenge), nonce_c(20)) {
            StepOut::Out(m) => m,
            other => panic!("esperaba proof, obtuve {other:?}"),
        };
        assert!(matches!(
            server.step(StepIn::Msg(&proof), 1_002, entropy(10)),
            StepOut::Established { .. }
        ));
        rec.consumed = server.into_record().consumed;
        assert!(rec.consumed);

        // TEST-005 — verifies SPEC-001 (E3: segundo dispositivo con el mismo QR).
        let mut server2 = ServerHandshake::new(rec);
        let (_client2, request2) = ClientHandshake::start(&token_str()).unwrap();
        assert_eq!(
            server2.step(StepIn::Msg(&request2), 2_000, entropy(11)),
            StepOut::Rejected(PairError::AuthRejected)
        );
    }

    #[test]
    fn proof_timeout_is_e3_without_consuming_and_boundary_10s_is_valid() {
        // > 10 s esperando proof → E3, token no consumido
        let mut server = ServerHandshake::new(record(1_000, false));
        let (mut client, request) = ClientHandshake::start(&token_str()).unwrap();
        let _ = &mut client;
        let _ = server.step(StepIn::Msg(&request), 1_001, entropy(10));
        assert_eq!(
            server.step(StepIn::Tick, 1_001 + 10_001, entropy(10)),
            StepOut::Rejected(PairError::AuthRejected)
        );
        assert!(!server.token_record().consumed);
        // exactamente 10 s → sin timeout
        let mut server = ServerHandshake::new(record(1_000, false));
        let _ = server.step(StepIn::Msg(&request), 1_001, entropy(10));
        assert_eq!(
            server.step(StepIn::Tick, 1_001 + 10_000, entropy(10)),
            StepOut::NeedMore
        );
    }

    #[test]
    fn out_of_sequence_message_is_e3() {
        let mut server = ServerHandshake::new(record(1_000, false));
        let proof = HandshakeMsg::PairProof {
            nonce_c: message::b64_32(&nonce_c(20).0),
            proof: message::b64_32(&nonce_c(20).0),
        };
        assert_eq!(
            server.step(StepIn::Msg(&proof), 1_001, entropy(10)),
            StepOut::Rejected(PairError::AuthRejected)
        );
    }

    #[test]
    fn client_maps_pair_error_code() {
        let mut server = ServerHandshake::new(record(1_000, false));
        let (mut client, request) = ClientHandshake::start(&token_str()).unwrap();
        let _ = &mut client;
        let _ = server.step(StepIn::Msg(&request), 1_001, entropy(10));
        let error = HandshakeMsg::PairError {
            code: "E2".to_string(),
            message: "QR expirado, generar uno nuevo".to_string(),
        };
        assert_eq!(
            client.step(StepIn::Msg(&error), nonce_c(20)),
            StepOut::Rejected(PairError::TokenExpired)
        );
    }
}
