//! Errores del protocolo de sesión — códigos exactos de SPEC-001 §9.

use core::fmt;

/// Errores observables del protocolo (SPEC-001 §9). No se inventan códigos nuevos.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum PairError {
    /// E1: conexión TCP rechazada o sin respuesta en 5 s (adaptador; `core` no lo produce).
    ServerUnavailable,
    /// E2: token emitido hace más de 300 s según el reloj del servidor.
    TokenExpired,
    /// E3: token consumido, proof inválido, mensaje fuera de secuencia o timeout de 10 s.
    AuthRejected,
    /// E4: mensaje NDJSON no parseable, `type` desconocido, campo faltante o fuera de constraints.
    MalformedMessage,
    /// E5: payload del QR no conforme a SPEC-001 §4.1.
    MalformedQr,
}

impl PairError {
    /// Código textual del mensaje `pair_error` (SPEC-001 §4.2).
    pub fn code(self) -> &'static str {
        match self {
            PairError::ServerUnavailable => "E1",
            PairError::TokenExpired => "E2",
            PairError::AuthRejected => "E3",
            PairError::MalformedMessage => "E4",
            PairError::MalformedQr => "E5",
        }
    }

    /// Mapea un código textual recibido por cable a `PairError`; código
    /// desconocido → E4 (SPEC-001 §4.2: `code` ∈ E1–E5).
    pub fn from_code(code: &str) -> Self {
        match code {
            "E1" => PairError::ServerUnavailable,
            "E2" => PairError::TokenExpired,
            "E3" => PairError::AuthRejected,
            "E5" => PairError::MalformedQr,
            _ => PairError::MalformedMessage,
        }
    }
}

impl fmt::Display for PairError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.code())
    }
}

/// Alias de resultado del protocolo.
pub type PairResult<T> = Result<T, PairError>;
