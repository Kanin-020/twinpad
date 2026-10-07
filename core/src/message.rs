//! Mensajes del Handshake en NDJSON — contrato SPEC-001 §4.2.
//!
//! Cada mensaje es una línea JSON UTF-8 con `type` como tag. El decode es
//! estricto: `type` desconocido, campo faltante, campo extra o longitud
//! inválida → E4 (SPEC-001 §9).

use crate::error::{PairError, PairResult};
use crate::qr;
use crate::{Nonce, Token};
use base64::Engine as _;
use serde::Deserialize;
use serde::Serialize;

/// Versión del protocolo (SPEC-001 R1).
pub const SCHEMA: &str = "twinpad.v1";

/// Mensaje del Handshake (SPEC-001 §4.2). `type` es el discriminante wire.
#[derive(Clone, PartialEq, Eq, Debug, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum HandshakeMsg {
    /// app → servidor: apertura del Handshake con el token del QR.
    #[serde(rename = "pair_request")]
    PairRequest {
        /// Fijo `"twinpad.v1"` (R1).
        schema: String,
        /// Token escaneado, igual que §4.1 `t`.
        token: String,
    },
    /// servidor → app: nonce del servidor.
    #[serde(rename = "pair_challenge")]
    PairChallenge {
        /// `nonce_s`: 32 bytes base64url.
        nonce_s: String,
    },
    /// app → servidor: prueba de conocimiento del token.
    #[serde(rename = "pair_proof")]
    PairProof {
        /// `nonce_c`: 32 bytes base64url.
        nonce_c: String,
        /// `proof`: 32 bytes base64url.
        proof: String,
    },
    /// servidor → app: Handshake exitoso.
    #[serde(rename = "pair_ack")]
    PairAck {
        /// `session_id`: 16 bytes base64url.
        session_id: String,
    },
    /// servidor → app: rechazo con código E1–E5.
    #[serde(rename = "pair_error")]
    PairError {
        /// Código textual (E1–E5).
        code: String,
        /// Mensaje legible ≤ 200 chars.
        message: String,
    },
}

/// Serializa un mensaje a su línea NDJSON canónica (sin `\n` final).
pub fn encode(msg: &HandshakeMsg) -> PairResult<String> {
    serde_json::to_string(msg).map_err(|_| PairError::MalformedMessage)
}

/// Decodifica y valida una línea NDJSON. Estricto: longitudes y `schema`.
pub fn decode(line: &str) -> PairResult<HandshakeMsg> {
    let msg: HandshakeMsg = serde_json::from_str(line).map_err(|_| PairError::MalformedMessage)?;
    validate(&msg)?;
    Ok(msg)
}

/// Validación de constraints por mensaje (SPEC-001 §4.2).
fn validate(msg: &HandshakeMsg) -> PairResult<()> {
    match msg {
        HandshakeMsg::PairRequest { schema, token } => {
            if schema != SCHEMA {
                return Err(PairError::MalformedMessage);
            }
            qr::validate_token(token).map_err(|_| PairError::MalformedMessage)
        }
        HandshakeMsg::PairChallenge { nonce_s } => expect_len32(nonce_s),
        HandshakeMsg::PairProof { nonce_c, proof } => {
            expect_len32(nonce_c)?;
            expect_len32(proof)
        }
        HandshakeMsg::PairAck { session_id } => {
            let bytes = decode_b64_32(session_id)?;
            if bytes.len() != 16 {
                return Err(PairError::MalformedMessage);
            }
            Ok(())
        }
        HandshakeMsg::PairError { code, message } => {
            let valid = matches!(
                PairError::from_code(code),
                PairError::ServerUnavailable
                    | PairError::TokenExpired
                    | PairError::AuthRejected
                    | PairError::MalformedMessage
                    | PairError::MalformedQr
            ) && PairError::from_code(code).code() == code.as_str();
            if !valid || message.len() > 200 || message.is_empty() {
                return Err(PairError::MalformedMessage);
            }
            Ok(())
        }
    }
}

fn decode_b64_32(value: &str) -> PairResult<Vec<u8>> {
    base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(value)
        .map_err(|_| PairError::MalformedMessage)
}

fn expect_len32(value: &str) -> PairResult<()> {
    let bytes = decode_b64_32(value)?;
    if bytes.len() != 32 {
        return Err(PairError::MalformedMessage);
    }
    Ok(())
}

/// Token decodificado desde un `pair_request` (32 bytes).
pub fn token_bytes(token_str: &str) -> PairResult<Token> {
    let bytes = qr::decode_token(token_str).map_err(|_| PairError::MalformedMessage)?;
    let arr: [u8; 32] = bytes.try_into().map_err(|_| PairError::MalformedMessage)?;
    Ok(Token(arr))
}

/// Nonce decodificado (32 bytes).
pub fn nonce_bytes(value: &str) -> PairResult<Nonce> {
    let bytes = decode_b64_32(value)?;
    let arr: [u8; 32] = bytes.try_into().map_err(|_| PairError::MalformedMessage)?;
    Ok(Nonce(arr))
}

/// Codifica 32 bytes a base64url canónico.
pub fn b64_32(bytes: &[u8; 32]) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

/// Codifica 16 bytes a base64url canónico.
pub fn b64_16(bytes: &[u8; 16]) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

// ENG §6: unwrap/expect permitidos solo en tests (ámbito: este módulo).
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::*;

    // TEST-006 — verifies SPEC-001 (E4: mensaje malformado / válido).
    const N32: &str = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    const S16: &str = "AAAAAAAAAAAAAAAAAAAAAA";

    #[test]
    fn request_roundtrip() {
        let msg = HandshakeMsg::PairRequest {
            schema: SCHEMA.to_string(),
            token: N32.to_string(),
        };
        let line = encode(&msg).unwrap();
        assert!(line.starts_with("{\"type\":\"pair_request\""));
        assert!(!line.contains('\n'));
        assert_eq!(decode(&line).unwrap(), msg);
    }

    #[test]
    fn all_variants_roundtrip() {
        let msgs = [
            HandshakeMsg::PairChallenge {
                nonce_s: N32.to_string(),
            },
            HandshakeMsg::PairProof {
                nonce_c: N32.to_string(),
                proof: N32.to_string(),
            },
            HandshakeMsg::PairAck {
                session_id: S16.to_string(),
            },
            HandshakeMsg::PairError {
                code: "E2".to_string(),
                message: "QR expirado, generar uno nuevo".to_string(),
            },
        ];
        for msg in msgs {
            assert_eq!(decode(&encode(&msg).unwrap()).unwrap(), msg);
        }
    }

    #[test]
    fn unknown_type_is_e4() {
        assert_eq!(
            decode("{\"type\":\"pair_hello\",\"x\":1}"),
            Err(PairError::MalformedMessage)
        );
    }

    #[test]
    fn missing_field_is_e4() {
        assert_eq!(
            decode("{\"type\":\"pair_request\",\"schema\":\"twinpad.v1\"}"),
            Err(PairError::MalformedMessage)
        );
    }

    #[test]
    fn unknown_field_is_e4() {
        let line = format!("{{\"type\":\"pair_challenge\",\"nonce_s\":\"{N32}\",\"extra\":1}}");
        assert_eq!(decode(&line), Err(PairError::MalformedMessage));
    }

    #[test]
    fn bad_schema_is_e4() {
        let line =
            format!("{{\"type\":\"pair_request\",\"schema\":\"twinpad.v2\",\"token\":\"{N32}\"}}");
        assert_eq!(decode(&line), Err(PairError::MalformedMessage));
    }

    #[test]
    fn wrong_nonce_length_is_e4() {
        let short = "A".repeat(42); // 31 bytes
        let line = format!("{{\"type\":\"pair_challenge\",\"nonce_s\":\"{short}\"}}");
        assert_eq!(decode(&line), Err(PairError::MalformedMessage));
    }

    #[test]
    fn wrong_session_id_length_is_e4() {
        // 20 chars base64url → 15 bytes ≠ 16 (SPEC-001 §4.2)
        let short16 = "A".repeat(20);
        let line = format!("{{\"type\":\"pair_ack\",\"session_id\":\"{short16}\"}}");
        assert_eq!(decode(&line), Err(PairError::MalformedMessage));
    }

    #[test]
    fn error_with_unknown_code_is_e4() {
        let msg = HandshakeMsg::PairError {
            code: "E9".to_string(),
            message: "x".to_string(),
        };
        assert_eq!(validate(&msg), Err(PairError::MalformedMessage));
    }
}
