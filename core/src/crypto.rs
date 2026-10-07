//! Criptografía del Handshake — HKDF-SHA256 + HMAC-SHA256 (SPEC-001 R4).
//!
//! Primitivas definitivas confirmadas por el dueño (SPEC-001 §17, v1.0.0).
//! HKDF se implementa según RFC 5869 (extract + expand de un bloque) sobre
//! `hmac`/`sha2`; la clave HMAC se cero-rellena a 64 bytes según RFC 2104,
//! lo que hace ambas operaciones infalibles y puras (ENG §5 P10/P11, §6:
//! sin `expect` en producción).

use crate::qr;
use crate::{Nonce, SessionKey, Token};
use digest::KeyInit;
use hmac::Hmac;
use hmac::Mac;
use sha2::Sha256;

/// Info fija de HKDF (DES-001 §4).
const HKDF_INFO: &[u8] = b"twinpad.v1 session";

/// Duración de validez del QR: delega en `qr::QR_VALIDITY_MS` (R3).
pub use qr::QR_VALIDITY_MS;

/// HMAC-SHA256 con clave de 32 bytes, cero-rellena a 64 (RFC 2104 §2).
/// Equivalente bit a bit a usar la clave de 32 bytes directamente.
fn mac(key32: &[u8; 32]) -> Hmac<Sha256> {
    let mut block = [0u8; 64];
    block[..32].copy_from_slice(key32);
    <Hmac<Sha256> as KeyInit>::new((&block).into())
}

/// Deriva la `session_key` = HKDF-SHA256(ikm = token, salt = nonce_s) —
/// SPEC-001 §7 paso 8. RFC 5869: `PRK = HMAC(salt, ikm)`;
/// `OKM = HMAC(PRK, info ‖ 0x01)` (N = 1 para 32 bytes con SHA-256).
pub fn derive_session_key(token: &Token, nonce_s: &Nonce) -> SessionKey {
    let prk: [u8; 32] = {
        let mut mac = mac(&nonce_s.0);
        mac.update(&token.0);
        mac.finalize().into_bytes().into()
    };
    let mut okm = [0u8; 32];
    {
        let mut mac = mac(&prk);
        mac.update(HKDF_INFO);
        mac.update(&[0x01]);
        okm.copy_from_slice(&mac.finalize().into_bytes());
    }
    SessionKey(okm)
}

/// Construye el MAC sobre el transcript fijo (DES-001 §4):
/// `encode(pair_request) ‖ encode(pair_challenge) ‖ nonce_s ‖ nonce_c`.
pub fn compute_proof(
    key: &SessionKey,
    request_line: &str,
    challenge_line: &str,
    nonce_s: &Nonce,
    nonce_c: &Nonce,
) -> [u8; 32] {
    let mut mac = mac(&key.0);
    mac.update(request_line.as_bytes());
    mac.update(challenge_line.as_bytes());
    mac.update(&nonce_s.0);
    mac.update(&nonce_c.0);
    mac.finalize().into_bytes().into()
}

/// Verifica un proof con comparación en tiempo constante (SPEC-001 §7.9).
pub fn verify_proof(
    key: &SessionKey,
    request_line: &str,
    challenge_line: &str,
    nonce_s: &Nonce,
    nonce_c: &Nonce,
    proof: &[u8],
) -> bool {
    let mut mac = mac(&key.0);
    mac.update(request_line.as_bytes());
    mac.update(challenge_line.as_bytes());
    mac.update(&nonce_s.0);
    mac.update(&nonce_c.0);
    mac.verify_slice(proof).is_ok()
}

/// ¿Expiró el QR? Frontera exacta: `> 300_000 ms` → expirado (SPEC-001 R3,
/// E2 = "emitido hace más de 300 s"). Saturación: reloj atrás no expira.
pub fn is_expired(issued_at_ms: u64, now_ms: u64) -> bool {
    now_ms.saturating_sub(issued_at_ms) > QR_VALIDITY_MS
}

// ENG §6: unwrap/expect permitidos solo en tests (ámbito: este módulo).
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::*;

    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{b:02x}")).collect()
    }

    // TEST-003 / TEST-004 — verifies SPEC-001 (E2 frontera R3, proof HMAC).
    fn token(v: u8) -> Token {
        Token([v; 32])
    }
    fn nonce(v: u8) -> Nonce {
        Nonce([v; 32])
    }

    #[test]
    fn derive_is_deterministic_and_separates_by_salt() {
        let k1 = derive_session_key(&token(1), &nonce(2));
        let k2 = derive_session_key(&token(1), &nonce(2));
        let k3 = derive_session_key(&token(1), &nonce(3));
        assert_eq!(k1, k2);
        assert_ne!(k1, k3);
    }

    #[test]
    fn hkdf_follows_rfc5869_extract_expand_procedure() {
        // RFC 5869 A.1: IKM=0x0b x22, salt=0x00..0x0c (13 B), info=0xf0.
        // Valores esperados verificados contra una implementación
        // independiente de HMAC-SHA256 (RFC 2104/5869).
        let ikm = [0x0b_u8; 22];
        let salt = [
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c,
        ];
        let prk: [u8; 32] = {
            let mut salt32 = [0u8; 32];
            salt32[..13].copy_from_slice(&salt);
            let mut m = mac(&salt32);
            m.update(&ikm);
            m.finalize().into_bytes().into()
        };
        assert_eq!(
            hex(&prk),
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"
        );
        let mut m = mac(&prk);
        m.update(&[0xf0]); // info del RFC A.1
        m.update(&[0x01]); // bloque 1 del expand
        let okm: [u8; 32] = m.finalize().into_bytes().into();
        assert_eq!(
            hex(&okm),
            "4da744c384b857b058f5222f751a01020a800c0958a34083106fcffc7642ae12"
        );
    }

    #[test]
    fn proof_verifies_and_rejects_tampering() {
        let key = derive_session_key(&token(7), &nonce(8));
        let req = "{\"type\":\"pair_request\"}";
        let ch = "{\"type\":\"pair_challenge\"}";
        let proof = compute_proof(&key, req, ch, &nonce(1), &nonce(2));
        assert!(verify_proof(&key, req, ch, &nonce(1), &nonce(2), &proof));
        let mut tampered = proof;
        tampered[0] ^= 0x01;
        assert!(!verify_proof(
            &key,
            req,
            ch,
            &nonce(1),
            &nonce(2),
            &tampered
        ));
        assert!(!verify_proof(&key, req, ch, &nonce(1), &nonce(3), &proof));
    }

    #[test]
    fn expiry_boundary_is_strictly_greater_than_300s() {
        assert!(!is_expired(1_000, 1_000 + 300_000)); // exactamente 300 s → vigente
        assert!(is_expired(1_000, 1_000 + 300_001)); // > 300 s → expirado
        assert!(!is_expired(5_000, 0)); // reloj atrás no expira (saturación)
    }
}
