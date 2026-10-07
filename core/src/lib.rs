//! Twinpad Core — protocolo de sesión y tipos de dominio compartidos (ENG §2).
//!
//! Este crate es la frontera común entre el Servidor PC y la app Android.
//! Regla arquitectural (ARCH §3, ENG §5 S5/P11): `core` no realiza I/O
//! (red, HID, captura, archivos); los módulos de plataforma le inyectan
//! sus puertos.
//!
//! El comportamiento del protocolo de sesión se define en los SPECs de
//! `docs/specs/` (el primero, SPEC-001, está en Draft). No se implementa
//! ningún comportamiento hasta que su SPEC esté `Approved`.
