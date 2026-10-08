//! Servidor Twinpad (PC) — componentes `Servidor PC` y `Twinpad Core (PC)` (ARCH §2).
//!
//! Responsabilidad (ARCH §2): gestionar sesiones, exponer el QR de
//! vinculación y coordinar los streams (REQ-001, REQ-002).
//!
//! Módulos (DES-001): `qr` (payload canónico §4.1), `tokens` (almacén
//! R2/R3/R6/R7, owner único ARCH §5), `session` (bucle TCP que alimenta
//! `ServerHandshake`). El binario ejecutable (`main.rs`) es el adaptador
//! con listener real, entropía del SO y QR en stdout (REQ-001 AC-1/2).

pub mod audio;
pub mod games;
pub mod qr;
pub mod qr_render;
pub mod registry;
pub mod session;
pub mod tokens;
pub mod video;
