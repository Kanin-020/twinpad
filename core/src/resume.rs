//! Reanudación del canal de control — SPEC-001 §8.2 (CR-056).
//!
//! Política pura: dada una ligadura retenida (session_id + session_key con
//! una ventana de retención), decide si un `pair_resume` es admisible y
//! verifica su proof en tiempo constante. Los efectos (sockets, maps del
//! registro) viven en `server/`; aquí solo reglas y fechas (ENG §5 P11).

use crate::crypto::mac;
use crate::error::{PairResult, PairError};
use crate::message;
use crate::{Nonce, SessionId, SessionKey};
use hmac::Mac as _;

/// Ventana de retención de la ligadura tras la caída del socket de control
/// (SPEC-001 §8.2): 60 000 ms. Vencida, exige re-scan (QR).
pub const RETENTION_MS: u64 = 60_000;

/// Dominio fijo del proof de reanudación (§4.2, CR-056):
/// `proof = HMAC-SHA256(key = session_key, data = "twinpad.v1 resume" ‖ session_id ‖ nonce_c)`.
pub const RESUME_PROOF_DOMAIN: &[u8] = b"twinpad.v1 resume";

/// Ligadura retenida en el servidor mientras dura la ventana (§2/S4).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RetainedSession {
    pub session_id: SessionId,
    pub session_key: SessionKey,
    /// Reloj del servidor al entrar en retención.
    pub retained_since_ms: u64,
}

/// Modelo para el lado app: lo que el teléfono recuerda de la sesión para
/// poder reintentar dentro de la ventana.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ClientResumeState {
    pub session_id: SessionId,
    pub session_key: SessionKey,
    /// Reloj del teléfono al perder el socket de control.
    pub dropped_at_ms: u64,
}

impl RetainedSession {
    /// ¿Sigue la retención viva en `now_ms`? Frontera exacta: `> RETENTION_MS`
    /// → expirada; `== RETENTION_MS` → vivo (§8.2).
    pub fn retention_alive(&self, now_ms: u64) -> bool {
        now_ms.saturating_sub(self.retained_since_ms) <= RETENTION_MS
    }
}

impl ClientResumeState {
    /// ¿Puede el teléfono intentar reanudar en `now_ms`? (§8.2: reintentos
    /// ilimitados dentro de la ventana).
    pub fn window_alive(&self, now_ms: u64) -> bool {
        now_ms.saturating_sub(self.dropped_at_ms) <= RETENTION_MS
    }
}

/// Proof de reanudación: HMAC-SHA256 sobre `RESUME_PROOF_DOMAIN ‖
/// session_id ‖ nonce_c` con la session_key (§4.2, CR-056).
pub fn resume_proof(
    session_key: &SessionKey,
    session_id: &SessionId,
    nonce_c: &Nonce,
) -> [u8; 32] {
    let mut mac = mac(&session_key.0);
    mac.update(RESUME_PROOF_DOMAIN);
    mac.update(&session_id.0);
    mac.update(&nonce_c.0);
    mac.finalize().into_bytes().into()
}

/// Verificación en tiempo constante del proof del `pair_resume` (§8.2).
pub fn verify_resume_proof(
    session_key: &SessionKey,
    session_id: &SessionId,
    nonce_c: &Nonce,
    proof: &[u8],
) -> bool {
    let mut mac = mac(&session_key.0);
    mac.update(RESUME_PROOF_DOMAIN);
    mac.update(&session_id.0);
    mac.update(&nonce_c.0);
    mac.verify_slice(proof).is_ok()
}

/// Decodifica un `pair_resume` ya validado por el codec y clasifica la
/// decisión del servidor (§8.2/§9): aceptar requiere ligadura retenida,
/// ventana viva y proof válido. Sin desglosar la causa al cliente (§9:
/// siempre E3).
///
/// `now_ms` es el reloj del servidor; `retained` es la ligadura guardada
/// (`None` = session_id desconocido o ya consumida).
pub fn evaluate_resume(
    retained: Option<&RetainedSession>,
    resume: &message::HandshakeMsg,
    now_ms: u64,
) -> PairResult<(SessionId, SessionKey)> {
    let message::HandshakeMsg::PairResume {
        session_id: sid_b64,
        nonce_c: nc_b64,
        proof: proof_b64,
    } = resume
    else {
        return Err(PairError::MalformedMessage);
    };
    let session_id = decode_session_id(sid_b64)?;
    let nonce_c = message::nonce_bytes(nc_b64)?;
    let proof = message::nonce_bytes(proof_b64)?;
    let Some(retained) = retained else {
        // session_id desconocido, ligadura ya consumida o servidor reiniciado.
        return Err(PairError::AuthRejected);
    };
    if retained.session_id != session_id {
        return Err(PairError::AuthRejected);
    }
    if !retained.retention_alive(now_ms) {
        // Retención expirada justo en el intento (§10).
        return Err(PairError::TokenExpired);
    }
    if !verify_resume_proof(&retained.session_key, &session_id, &nonce_c, &proof.0) {
        return Err(PairError::AuthRejected);
    }
    Ok((retained.session_id, retained.session_key))
}

/// session_id (16 bytes) desde base64url canónico — lo usa el adaptador
/// del server para la toma de retención (§8.2).
pub fn session_id_from_b64(value: &str) -> PairResult<SessionId> {
    decode_session_id(value)
}

fn decode_session_id(value: &str) -> PairResult<SessionId> {
    use base64::Engine as _;
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(value)
        .map_err(|_| PairError::MalformedMessage)?;
    let arr: [u8; 16] = bytes.try_into().map_err(|_| PairError::MalformedMessage)?;
    Ok(SessionId(arr))
}

// ENG §6: unwrap/expect permitidos solo en tests (ámbito: este módulo).
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::*;

    fn key(v: u8) -> SessionKey {
        SessionKey([v; 32])
    }
    fn sid(v: u8) -> SessionId {
        SessionId([v; 16])
    }
    fn sid_b64(v: u8) -> String {
        message::b64_16(&sid(v).0)
    }
    fn nonce_c(v: u8) -> Nonce {
        Nonce([v; 32])
    }

    fn retained(retained_since_ms: u64) -> RetainedSession {
        RetainedSession {
            session_id: sid(7),
            session_key: key(9),
            retained_since_ms,
        }
    }

    fn resume_msg(sid_v: u8, nc_v: u8, proof: Option<[u8; 32]>) -> message::HandshakeMsg {
        let proof = proof.unwrap_or_else(|| resume_proof(&key(9), &sid(7), &nonce_c(nc_v)));
        message::HandshakeMsg::PairResume {
            session_id: sid_b64(sid_v),
            nonce_c: message::b64_32(&nonce_c(nc_v).0),
            proof: message::b64_32(&proof),
        }
    }

    // TEST-0128 — verifies SPEC-001 §8.2 (pair_resume válido y rechazado).
    #[test]
    fn valid_resume_establishes_same_session_identity() {
        let r = retained(1_000);
        let msg = resume_msg(7, 20, None);
        let (got_sid, got_key) = evaluate_resume(Some(&r), &msg, 1_050).unwrap();
        assert_eq!(got_sid, sid(7));
        assert_eq!(got_key, key(9)); // el canal media anclado sigue usable
    }

    #[test]
    fn unknown_session_or_forged_proof_is_e3() {
        let r = retained(1_000);
        let nc = nonce_c(21);
        let proof = resume_proof(&key(9), &sid(7), &nc);
        let mut forged = proof;
        forged[0] ^= 0x01;
        assert_eq!(
            evaluate_resume(Some(&r), &resume_msg(7, 21, Some(forged)), 1_050),
            Err(PairError::AuthRejected)
        );
        // session_id desconocido (retención de otro id).
        let other = RetainedSession { session_id: sid(8), ..retained(1_000) };
        assert_eq!(
            evaluate_resume(Some(&other), &resume_msg(7, 21, Some(proof)), 1_050),
            Err(PairError::AuthRejected)
        );
        // Ligadura desaparecida (servidor reiniciado / consumida).
        assert_eq!(
            evaluate_resume(None, &resume_msg(7, 21, Some(proof)), 1_050),
            Err(PairError::AuthRejected)
        );
    }

    #[test]
    fn retention_expiry_60s_boundary_is_exact() {
        let r = retained(1_000);
        assert_eq!(
            evaluate_resume(Some(&r), &resume_msg(7, 22, None), 1_000 + RETENTION_MS),
            Ok((sid(7), key(9)))
        );
        assert_eq!(
            evaluate_resume(Some(&r), &resume_msg(7, 22, None), 1_000 + RETENTION_MS + 1),
            Err(PairError::TokenExpired)
        );
    }

    #[test]
    fn malformed_fields_are_e4() {
        let bad_uid = message::HandshakeMsg::PairResume {
            session_id: "A".repeat(20), // 15 bytes
            nonce_c: message::b64_32(&[0u8; 32]),
            proof: message::b64_32(&[0u8; 32]),
        };
        assert_eq!(
            evaluate_resume(Some(&retained(1_000)), &bad_uid, 1_050),
            Err(PairError::MalformedMessage)
        );
        // Mensaje equivocado (no pair_resume).
        let other = message::HandshakeMsg::PairProof {
            nonce_c: message::b64_32(&[0u8; 32]),
            proof: message::b64_32(&[0u8; 32]),
        };
        assert_eq!(
            evaluate_resume(Some(&retained(1_000)), &other, 1_050),
            Err(PairError::MalformedMessage)
        );
    }

    #[test]
    fn client_window_mirrors_retention() {
        let client = ClientResumeState { session_id: sid(3), session_key: key(4), dropped_at_ms: 5_000 };
        assert!(client.window_alive(5_000 + RETENTION_MS));
        assert!(!client.window_alive(5_000 + RETENTION_MS + 1));
    }
}
