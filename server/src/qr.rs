//! Generación del payload del QR de vinculación en el Servidor PC —
//! contrato SPEC-001 §4.1 (gramática canónica; el codec estricto vive en
//! `twinpad-core::qr` y es compartido).

use twinpad_core::Token;
use twinpad_core::error::PairResult;
use twinpad_core::qr::{self, QrPayload};

/// Orden canónico de pares: `s`, `p`, `t` (DES-001 §4: determinista, P10).
pub fn format_uri(addr: &str, port: u16, token: &Token) -> String {
    qr::format(&QrPayload {
        server_addr: addr.to_string(),
        server_port: port,
        token: twinpad_core::message::b64_32(&token.0),
    })
}

/// Valida que una dirección sea utilizable en el QR (misma regla §4.1).
pub fn validate_addr(addr: &str) -> PairResult<()> {
    if addr.is_empty() || addr.len() > 253 || addr.chars().any(|c| c.is_whitespace() || c == '#') {
        return Err(twinpad_core::error::PairError::MalformedQr);
    }
    Ok(())
}

// ENG §6: unwrap permitido solo en tests.
#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn uri_is_canonical_and_parseable_by_core() {
        let token = Token([9; 32]);
        let uri = format_uri("192.168.1.50", 8420, &token);
        let payload = qr::parse(&uri).unwrap();
        assert_eq!(payload.server_addr, "192.168.1.50");
        assert_eq!(payload.server_port, 8420);
        assert_eq!(payload.token, twinpad_core::message::b64_32(&token.0));
    }

    #[test]
    fn addr_validation_matches_qr_grammar() {
        assert!(validate_addr("twinpad.local.").is_ok());
        assert!(validate_addr("").is_err());
        assert!(validate_addr("192.168.1.50 extra").is_err());
    }
}
