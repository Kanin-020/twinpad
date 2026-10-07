//! Codec del payload del QR de vinculación — contrato canónico SPEC-001 §4.1.
//!
//! Formato: `twinpad://v1#s=<server_addr>&p=<server_port>&t=<token>`.
//! Gramática estricta: claves `s`, `p`, `t` exactas, sin duplicados ni
//! extras; token base64url sin relleno de 32 bytes. Violación → E5.

use crate::error::{PairError, PairResult};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;

/// Esquema URI fijo de la versión 1 (SPEC-001 R1).
pub const SCHEME: &str = "twinpad://v1#";

/// Duración de validez del QR en milisegundos: 300 s (SPEC-001 R3).
pub const QR_VALIDITY_MS: u64 = 300_000;

/// Payload decodificado del QR de vinculación (SPEC-001 §4.1).
#[derive(Clone, PartialEq, Eq, Debug)]
pub struct QrPayload {
    /// Dirección IPv4/IPv6 del Servidor PC en la LAN (1–253 chars).
    pub server_addr: String,
    /// Puerto TCP del Servidor PC (1024–65535).
    pub server_port: u16,
    /// Token de sesión en base64url canónico (decodifica 32 bytes).
    pub token: String,
}

/// Codifica el payload al URI canónico (orden fijo `s`, `p`, `t`).
///
/// # Panics
/// Nunca: `format!` sobre strings ya validadas en `parse`/construcción.
pub fn format(payload: &QrPayload) -> String {
    format!(
        "{}s={}&p={}&t={}",
        SCHEME, payload.server_addr, payload.server_port, payload.token
    )
}

/// Decodifica y valida estrictamente el URI del QR (SPEC-001 §4.1).
/// Cualquier desviación de la gramática → E5.
pub fn parse(raw: &str) -> PairResult<QrPayload> {
    let rest = raw.strip_prefix(SCHEME).ok_or(PairError::MalformedQr)?;
    if rest.is_empty() {
        return Err(PairError::MalformedQr);
    }
    let mut server_addr: Option<String> = None;
    let mut server_port: Option<String> = None;
    let mut token: Option<String> = None;

    for part in rest.split('&') {
        let (key, value) = part.split_once('=').ok_or(PairError::MalformedQr)?;
        if value.is_empty() || value.contains('#') {
            return Err(PairError::MalformedQr); // valor vacío o con fragmento
        }
        match key {
            "s" => {
                if server_addr.is_some() {
                    return Err(PairError::MalformedQr); // duplicada
                }
                server_addr = Some(value.to_string());
            }
            "p" => {
                if server_port.is_some() {
                    return Err(PairError::MalformedQr);
                }
                server_port = Some(value.to_string());
            }
            "t" => {
                if token.is_some() {
                    return Err(PairError::MalformedQr);
                }
                token = Some(value.to_string());
            }
            _ => return Err(PairError::MalformedQr), // clave extra
        }
    }

    let server_addr = server_addr.ok_or(PairError::MalformedQr)?;
    let token = token.ok_or(PairError::MalformedQr)?;
    let server_port_raw = server_port.ok_or(PairError::MalformedQr)?;

    validate_addr(&server_addr)?;
    let server_port: u16 = server_port_raw
        .parse()
        .map_err(|_| PairError::MalformedQr)?;
    if !(1024..=65535).contains(&server_port) {
        return Err(PairError::MalformedQr);
    }
    validate_token(&token)?;

    Ok(QrPayload {
        server_addr,
        server_port,
        token,
    })
}

/// Decodifica el token (base64url sin relleno) a bytes crudos.
pub fn decode_token(token: &str) -> PairResult<Vec<u8>> {
    URL_SAFE_NO_PAD
        .decode(token)
        .map_err(|_| PairError::MalformedQr)
}

/// Valida el token: base64url sin relleno, decodifica exactamente 32 bytes.
pub fn validate_token(token: &str) -> PairResult<()> {
    if decode_token(token)?.len() != 32 {
        return Err(PairError::MalformedQr);
    }
    Ok(())
}

/// Valida `server_addr`: 1–253 chars, sin espacios ni separadores de la
/// gramática (SPEC-001 §4.1).
fn validate_addr(addr: &str) -> PairResult<()> {
    if addr.is_empty() || addr.len() > 253 || addr.chars().any(|c| c.is_whitespace() || c == '#') {
        return Err(PairError::MalformedQr);
    }
    Ok(())
}

// ENG §6: unwrap/expect permitidos solo en tests (ámbito: este módulo).
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::*;

    // TEST-006 — verifies SPEC-001 (E5: payload del QR malformado / válido).
    const TOKEN: &str = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // 32 bytes cero
    const ADDR: &str = "192.168.1.50";

    fn sample() -> QrPayload {
        QrPayload {
            server_addr: ADDR.to_string(),
            server_port: 8420,
            token: TOKEN.to_string(),
        }
    }

    #[test]
    fn roundtrip_format_parse_is_identity() {
        let uri = format(&sample());
        assert_eq!(uri, format!("{SCHEME}s={ADDR}&p=8420&t={TOKEN}"));
        assert_eq!(parse(&uri).unwrap(), sample());
    }

    #[test]
    fn order_of_pairs_is_free() {
        let uri = format!("{SCHEME}t={TOKEN}&p=8420&s={ADDR}");
        assert_eq!(parse(&uri).unwrap(), sample());
    }

    #[test]
    fn port_boundaries_1024_and_65535_are_valid() {
        for port in [1024u16, 65535] {
            let payload = QrPayload {
                server_port: port,
                ..sample()
            };
            assert!(parse(&format(&payload)).is_ok());
        }
    }

    #[test]
    fn port_outside_range_is_e5() {
        for raw in [1023u32, 65536] {
            let uri = format!("{SCHEME}s={ADDR}&p={raw}&t={TOKEN}");
            assert_eq!(parse(&uri), Err(PairError::MalformedQr));
        }
    }

    #[test]
    fn bad_scheme_is_e5() {
        let uri = format!("twinpad://v2#s={ADDR}&p=8420&t={TOKEN}");
        assert_eq!(parse(&uri), Err(PairError::MalformedQr));
    }

    #[test]
    fn missing_key_is_e5() {
        for uri in [
            format!("{SCHEME}s={ADDR}&p=8420"),
            format!("{SCHEME}s={ADDR}&t={TOKEN}"),
            format!("{SCHEME}p=8420&t={TOKEN}"),
        ] {
            assert_eq!(parse(&uri), Err(PairError::MalformedQr));
        }
    }

    #[test]
    fn duplicate_or_extra_key_is_e5() {
        let dup = format!("{SCHEME}s={ADDR}&s={ADDR}&p=8420&t={TOKEN}");
        assert_eq!(parse(&dup), Err(PairError::MalformedQr));
        let extra = format!("{SCHEME}s={ADDR}&p=8420&t={TOKEN}&x=1");
        assert_eq!(parse(&extra), Err(PairError::MalformedQr));
    }

    #[test]
    fn token_wrong_length_is_e5() {
        // 31 bytes → 42 chars (SPEC-001 §4.1: decodifica exactamente 32)
        let short = "A".repeat(42);
        let uri = format!("{SCHEME}s={ADDR}&p=8420&t={short}");
        assert_eq!(parse(&uri), Err(PairError::MalformedQr));
    }

    #[test]
    fn token_non_base64url_is_e5() {
        let uri = format!("{SCHEME}s={ADDR}&p=8420&t=not@base64!!");
        assert_eq!(parse(&uri), Err(PairError::MalformedQr));
    }

    #[test]
    fn empty_or_oversized_addr_is_e5() {
        let empty = format!("{SCHEME}s=&p=8420&t={TOKEN}");
        assert_eq!(parse(&empty), Err(PairError::MalformedQr));
        let big = format!("{SCHEME}s={}&p=8420&t={TOKEN}", "a".repeat(254));
        assert_eq!(parse(&big), Err(PairError::MalformedQr));
    }
}
