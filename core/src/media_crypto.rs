//! Primitivas del canal media cifrado (SPEC-003 §4.2–4.3, CR-014).
//!
//! El transporte de socket y el RNG pertenecen a los adaptadores. Este módulo
//! puro deriva subclaves por conexión y sella/abre registros con nonce derivado
//! del contador; nunca acepta un contador distinto del esperado.

use aes_gcm::aead::{AeadInPlace, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce, Tag};
use sha2::{Digest, Sha256};

const MEDIA_INFO: &[u8] = b"twinpad.v1 media server-to-client aes-256-gcm";
const CLIENT_CONTEXT: &[u8] = b"twinpad.v1 media client";
const SERVER_CONTEXT: &[u8] = b"twinpad.v1 media server";
pub const MEDIA_TAG_BYTES: usize = 16;
pub const MEDIA_COUNTER_BYTES: usize = 8;
pub const MEDIA_RECORD_OVERHEAD: usize = MEDIA_COUNTER_BYTES + MEDIA_TAG_BYTES;
pub const MAX_MEDIA_RECORD_BYTES: usize = 4 * 1024 * 1024;
pub const MEDIA_PORT: u16 = 8425;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MediaCryptoError {
    InvalidLength,
    InvalidCounter,
    AuthenticationFailed,
}

/// HMAC-SHA256 según RFC 2104.
#[must_use]
pub fn hmac_sha256(key: &[u8], parts: &[&[u8]]) -> [u8; 32] {
    let mut key_block = [0_u8; 64];
    if key.len() > key_block.len() {
        key_block[..32].copy_from_slice(&Sha256::digest(key));
    } else {
        key_block[..key.len()].copy_from_slice(key);
    }
    let mut inner_pad = [0x36_u8; 64];
    let mut outer_pad = [0x5c_u8; 64];
    for index in 0..64 {
        inner_pad[index] ^= key_block[index];
        outer_pad[index] ^= key_block[index];
    }
    let mut inner = Sha256::new();
    inner.update(inner_pad);
    for part in parts {
        inner.update(part);
    }
    let inner_digest = inner.finalize();
    let mut outer = Sha256::new();
    outer.update(outer_pad);
    outer.update(inner_digest);
    outer.finalize().into()
}

/// Client proof: context || session_id || nonce_c.
#[must_use]
pub fn client_proof(key: &[u8; 32], session_id: &[u8; 16], nonce_c: &[u8; 32]) -> [u8; 32] {
    hmac_sha256(key, &[CLIENT_CONTEXT, session_id, nonce_c])
}

/// Server proof: context || session_id || nonce_c || nonce_s.
#[must_use]
pub fn server_proof(
    key: &[u8; 32],
    session_id: &[u8; 16],
    nonce_c: &[u8; 32],
    nonce_s: &[u8; 32],
) -> [u8; 32] {
    hmac_sha256(key, &[SERVER_CONTEXT, session_id, nonce_c, nonce_s])
}

/// Deriva la clave direccional AES-256-GCM para una conexión media.
#[must_use]
pub fn derive_media_key(
    session_key: &[u8; 32],
    nonce_c: &[u8; 32],
    nonce_s: &[u8; 32],
) -> [u8; 32] {
    let mut salt = [0_u8; 64];
    salt[..32].copy_from_slice(nonce_c);
    salt[32..].copy_from_slice(nonce_s);
    let prk = hmac_sha256(&salt, &[session_key]);
    hmac_sha256(&prk, &[MEDIA_INFO, &[1]])
}

fn nonce_for_counter(counter: u64) -> [u8; 12] {
    let mut nonce = [0_u8; 12];
    nonce[4..].copy_from_slice(&counter.to_be_bytes());
    nonce
}

/// Sella registro wire `length:u32be || counter:u64be || ciphertext+tag`.
/// AAD = bytes wire de longitud y contador; counter de inicio es cero.
///
/// # Errors
/// Devuelve `InvalidLength` cuando el contenido supera el límite normativo.
pub fn seal_record(
    key: &[u8; 32],
    counter: u64,
    plaintext: &[u8],
) -> Result<Vec<u8>, MediaCryptoError> {
    let length = MEDIA_RECORD_OVERHEAD
        .checked_add(plaintext.len())
        .ok_or(MediaCryptoError::InvalidLength)?;
    if length > MAX_MEDIA_RECORD_BYTES || length > u32::MAX as usize {
        return Err(MediaCryptoError::InvalidLength);
    }
    let length_bytes = (length as u32).to_be_bytes();
    let counter_bytes = counter.to_be_bytes();
    let mut aad = [0_u8; 12];
    aad[..4].copy_from_slice(&length_bytes);
    aad[4..].copy_from_slice(&counter_bytes);
    let mut ciphertext = plaintext.to_vec();
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| MediaCryptoError::InvalidLength)?;
    let tag = cipher
        .encrypt_in_place_detached(
            Nonce::from_slice(&nonce_for_counter(counter)),
            &aad,
            &mut ciphertext,
        )
        .map_err(|_| MediaCryptoError::AuthenticationFailed)?;

    let mut record = Vec::with_capacity(4 + length);
    record.extend_from_slice(&length_bytes);
    record.extend_from_slice(&counter_bytes);
    record.extend_from_slice(&ciphertext);
    record.extend_from_slice(tag.as_slice());
    Ok(record)
}

/// Abre un registro completo solo si longitud y counter cumplen el contrato.
///
/// # Errors
/// Devuelve error ante truncamiento, counter inesperado o tag inválido.
pub fn open_record(
    key: &[u8; 32],
    expected_counter: u64,
    record: &[u8],
) -> Result<Vec<u8>, MediaCryptoError> {
    if record.len() < 4 + MEDIA_RECORD_OVERHEAD {
        return Err(MediaCryptoError::InvalidLength);
    }
    let declared = u32::from_be_bytes(
        record[..4]
            .try_into()
            .map_err(|_| MediaCryptoError::InvalidLength)?,
    ) as usize;
    if !(MEDIA_RECORD_OVERHEAD..=MAX_MEDIA_RECORD_BYTES).contains(&declared)
        || record.len() != declared + 4
    {
        return Err(MediaCryptoError::InvalidLength);
    }
    let counter = u64::from_be_bytes(
        record[4..12]
            .try_into()
            .map_err(|_| MediaCryptoError::InvalidLength)?,
    );
    if counter != expected_counter {
        return Err(MediaCryptoError::InvalidCounter);
    }
    let tag_start = record.len() - MEDIA_TAG_BYTES;
    let mut ciphertext = record[12..tag_start].to_vec();
    let tag = Tag::from_slice(&record[tag_start..]);
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| MediaCryptoError::InvalidLength)?;
    let mut aad = [0_u8; 12];
    aad[..4].copy_from_slice(&record[..4]);
    aad[4..].copy_from_slice(&record[4..12]);
    cipher
        .decrypt_in_place_detached(
            Nonce::from_slice(&nonce_for_counter(counter)),
            &aad,
            &mut ciphertext,
            tag,
        )
        .map_err(|_| MediaCryptoError::AuthenticationFailed)?;
    Ok(ciphertext)
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::panic)]
mod tests {
    use super::*;

    // TEST-071 — CR-014: RFC HMAC, framing, AEAD, anti-replay y tamaños.
    #[test]
    fn record_roundtrip_uses_length_counter_and_aead() {
        let key = [0x42; 32];
        let plain = b"annex-b-au";
        let record = seal_record(&key, 0, plain).unwrap();
        assert_eq!(
            u32::from_be_bytes(record[..4].try_into().unwrap()) as usize,
            record.len() - 4
        );
        assert_eq!(&record[4..12], &0_u64.to_be_bytes());
        assert_eq!(open_record(&key, 0, &record).unwrap(), plain);
    }

    #[test]
    fn record_rejects_replay_skip_tamper_and_oversize() {
        let key = [0x24; 32];
        let record = seal_record(&key, 0, b"media").unwrap();
        assert_eq!(
            open_record(&key, 1, &record),
            Err(MediaCryptoError::InvalidCounter)
        );
        let mut tampered = record.clone();
        *tampered.last_mut().unwrap() ^= 1;
        assert_eq!(
            open_record(&key, 0, &tampered),
            Err(MediaCryptoError::AuthenticationFailed)
        );
        assert_eq!(
            seal_record(&key, 0, &vec![0; MAX_MEDIA_RECORD_BYTES]),
            Err(MediaCryptoError::InvalidLength)
        );
    }

    #[test]
    fn hmac_matches_rfc4231_and_media_key_separates_nonces() {
        let key = [0x0b; 20];
        assert_eq!(
            hmac_sha256(&key, &[b"Hi There"]),
            [
                0xb0, 0x34, 0x4c, 0x61, 0xd8, 0xdb, 0x38, 0x53, 0x5c, 0xa8, 0xaf, 0xce, 0xaf, 0x0b,
                0xf1, 0x2b, 0x88, 0x1d, 0xc2, 0x00, 0xc9, 0x83, 0x3d, 0xa7, 0x26, 0xe9, 0x37, 0x6c,
                0x2e, 0x32, 0xcf, 0xf7,
            ]
        );
        let session_key: [u8; 32] = std::array::from_fn(|index| index as u8);
        let session_id: [u8; 16] = std::array::from_fn(|index| index as u8);
        let nonce_c: [u8; 32] = std::array::from_fn(|index| (index + 32) as u8);
        let nonce_s: [u8; 32] = std::array::from_fn(|index| (index + 64) as u8);
        assert_eq!(
            client_proof(&session_key, &session_id, &nonce_c),
            [
                0xf1, 0xc8, 0x8e, 0x0c, 0x68, 0xa0, 0x46, 0x42, 0x03, 0x65, 0x5f, 0x9c, 0xb7, 0xc6,
                0x17, 0xa0, 0x32, 0xfd, 0xa3, 0x19, 0xac, 0x66, 0x5a, 0x93, 0x3d, 0x0d, 0xa6, 0xc0,
                0x57, 0x7c, 0xd3, 0x48,
            ]
        );
        assert_eq!(
            server_proof(&session_key, &session_id, &nonce_c, &nonce_s),
            [
                0x42, 0xe5, 0x65, 0x59, 0x0a, 0x5a, 0xe7, 0x37, 0xa1, 0xae, 0x43, 0xb6, 0x1e, 0x10,
                0x7f, 0x85, 0xf4, 0xeb, 0x9b, 0xa6, 0x0c, 0x23, 0x73, 0x8e, 0xb8, 0x24, 0x00, 0xb3,
                0x93, 0xae, 0x52, 0x78,
            ]
        );
        assert_eq!(
            derive_media_key(&session_key, &nonce_c, &nonce_s),
            [
                0x6f, 0x95, 0xe9, 0x90, 0xfd, 0x3a, 0x7f, 0x05, 0x56, 0xc0, 0x35, 0x0a, 0x4e, 0xdc,
                0xc9, 0xea, 0x8e, 0x50, 0x10, 0xd4, 0x0e, 0xe0, 0x20, 0x78, 0xe7, 0xd2, 0x37, 0x3e,
                0x03, 0xbd, 0x36, 0xc1,
            ]
        );

        let key = [1; 32];
        assert_ne!(
            derive_media_key(&key, &[2; 32], &[3; 32]),
            derive_media_key(&key, &[2; 32], &[4; 32])
        );
        let session_id = [5; 16];
        assert_ne!(
            client_proof(&key, &session_id, &[2; 32]),
            server_proof(&key, &session_id, &[2; 32], &[3; 32])
        );
    }
}
