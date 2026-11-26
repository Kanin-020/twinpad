//! Revocación del emparejamiento desde la app — SPEC-001 §8.3 (CR-102).
//!
//! Política pura: el proof del `pair_revoke` demuestra que el teléfono
//! conserva la `session_key` de la ligadura que quiere revocar. Los efectos
//! (borrar el registro persistente, cerrar la sesión) viven en `server/`.

use crate::crypto::mac;
use crate::{SessionId, SessionKey};
use hmac::Mac as _;

/// Dominio fijo del proof de revocación (SPEC-001 §4.2, CR-102):
/// `proof = HMAC-SHA256(key = session_key, data = "twinpad.v1 revoke" ‖ session_id)`.
pub const REVOKE_PROOF_DOMAIN: &[u8] = b"twinpad.v1 revoke";

/// Proof de revocación (SPEC-001 §4.2, CR-102).
#[must_use]
pub fn revoke_proof(session_key: &SessionKey, session_id: &SessionId) -> [u8; 32] {
    let mut mac = mac(&session_key.0);
    mac.update(REVOKE_PROOF_DOMAIN);
    mac.update(&session_id.0);
    mac.finalize().into_bytes().into()
}

/// Verificación en tiempo constante del proof del `pair_revoke` (§8.3).
#[must_use]
pub fn verify_revoke_proof(session_key: &SessionKey, session_id: &SessionId, proof: &[u8]) -> bool {
    let mut mac = mac(&session_key.0);
    mac.update(REVOKE_PROOF_DOMAIN);
    mac.update(&session_id.0);
    mac.verify_slice(proof).is_ok()
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

    // TEST-0195 — verifies SPEC-001 §4.2/§8.3 (CR-102): el proof cubre el
    // dominio fijo y el session_id con la session_key de la ligadura.
    #[test]
    fn revoke_proof_is_bound_to_key_and_session_id() {
        let proof = revoke_proof(&key(9), &sid(0x55));
        assert!(verify_revoke_proof(&key(9), &sid(0x55), &proof));
        // Cualquier byte alterado del proof invalida la verificación.
        let mut forged = proof;
        forged[0] ^= 0x01;
        assert!(!verify_revoke_proof(&key(9), &sid(0x55), &forged));
        // Otra ligadura (session_id o clave) no reutiliza el mismo proof.
        assert!(!verify_revoke_proof(&key(9), &sid(0x56), &proof));
        assert!(!verify_revoke_proof(&key(8), &sid(0x55), &proof));
    }
}
