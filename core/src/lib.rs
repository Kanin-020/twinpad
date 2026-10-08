//! Twinpad Core — protocolo de sesión y tipos de dominio compartidos (ENG §2).
//!
//! Núcleo puro del protocolo de Pairing/Handshake (SPEC-001 `Approved`,
//! realizado por DES-001): codec del payload QR, mensajes NDJSON,
//! criptografía HKDF/HMAC-SHA256 y máquinas de estado del Handshake.
//!
//! Regla arquitectural (ARCH §3, ENG §5 P11/S5): `core` no realiza I/O
//! (red, HID, captura, archivos, RNG); la entropía y el reloj entran por
//! parámetro y los adaptadores (`server/`, app Android) inyectan los
//! efectos.
//!
//! El comportamiento está definido por SPEC-001; nada de lo que no esté
//! allí es requerido (SPEC-001 §15).

pub mod crypto;
pub mod error;
pub mod game;
pub mod handshake;
pub mod input;
pub mod message;
pub mod qr;
pub mod stream;

/// Token de sesión: 32 bytes de entropía, un solo uso (SPEC-001 R2, ADR-004).
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub struct Token(pub [u8; 32]);

/// Nonce criptográfico de 32 bytes (SPEC-001 §2).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Nonce(pub [u8; 32]);

/// Identificador opaco de sesión: 16 bytes aleatorios (SPEC-001 §2).
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub struct SessionId(pub [u8; 16]);

/// Clave de sesión derivada: 32 bytes, nunca viaja por la red (SPEC-001 R5/R6).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct SessionKey(pub [u8; 32]);
