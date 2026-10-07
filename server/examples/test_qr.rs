//! QR de prueba en la terminal, sin levantar el servidor (herramienta de
//! desarrollo): valida el render del QR y el escaneo con la app — al
//! escanearlo, la app intentará conectar y mostrará E1 "Servidor no
//! disponible" (SPEC-002 §9), que confirma el camino completo decodificar
//! → validar → conectar.
//!
//! Uso: cargo run -p twinpad-server --example test_qr [-- <uri>]

use twinpad_server::qr_render;

/// URI válido según SPEC-001 §4.1 con token de relleno (32 bytes cero en
/// base64url canónico, como los fixtures de `core::qr`).
const SAMPLE: &str =
    "twinpad://v1#s=192.168.1.50&p=8420&t=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

fn main() {
    let uri = std::env::args()
        .nth(1)
        .unwrap_or_else(|| SAMPLE.to_string());
    println!("QR de prueba (la app mostrará 'Servidor no disponible' al escanear):");
    println!("{uri}");
    println!();
    match qr_render::render_terminal(&uri) {
        Ok(qr_text) => println!("{qr_text}"),
        Err(_) => eprintln!("ERROR: el URI no cabe en un QR"),
    }
}
