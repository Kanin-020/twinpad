//! Render del QR de vinculación como texto semi-gráfico para el terminal
//! del Servidor PC (REQ-001 AC-1/2: el operador debe poder escanear el QR
//! directamente desde la pantalla del PC).

use qrcodegen::{QrCode, QrCodeEcc};

use crate::qr_render::QrEncodeError::UriTooLong;

/// Borde en módulos alrededor del QR (zona silenciosa para lectores).
const QUIET_ZONE: i32 = 2;

/// Error al renderizar el QR de vinculación.
#[derive(Debug)]
pub enum QrEncodeError {
    /// El URI no cabe en un QR (no debería ocurrir: §4.1 acota el payload).
    UriTooLong,
}

/// Renderiza el URI como QR en texto Unicode (bloques de medio renglón:
/// dos filas de módulos por línea) para escanear desde la pantalla.
///
/// # Errors
/// `UriTooLong` si el payload excede la capacidad del QR (ecc medio).
pub fn render_terminal(uri: &str) -> Result<String, QrEncodeError> {
    let code = QrCode::encode_text(uri, QrCodeEcc::Medium).map_err(|_| UriTooLong)?;
    let size = code.size();
    let border = QUIET_ZONE;
    let dim = size + 2 * border;

    let mut out = String::new();
    // Cada línea cubre dos filas de módulos (arriba/abajo) mediante los
    // caracteres ▀ (mitad superior), ▄ (mitad inferior) y espacio.
    for y in (0..dim).step_by(2) {
        for x in 0..dim {
            let top = module_is_dark(&code, x, y - border);
            let bottom = module_is_dark(&code, x, y + 1 - border);
            out.push_str(match (top, bottom) {
                (true, true) => " ",
                (true, false) => "▄",
                (false, true) => "▀",
                (false, false) => "█",
            });
        }
        out.push('\n');
    }
    Ok(out)
}

/// Módulo oscuro (true) o claro (false), con borde tratado como claro.
fn module_is_dark(code: &QrCode, x: i32, y: i32) -> bool {
    x >= 0 && x < code.size() && y >= 0 && y < code.size() && code.get_module(x, y)
}

// ENG §6: unwrap/panic permitidos solo en tests (ámbito: este módulo).
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::panic)]
mod tests {
    use super::*;

    // QR mínimo con datos alfanuméricos; el URI de SPEC-001 usa bytes.
    const SAMPLE_URI: &str =
        "twinpad://v1#s=192.168.1.50&p=8420&t=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    #[test]
    fn render_has_square_dimensions_and_empty_quiet_zone() {
        let rendered = render_terminal(SAMPLE_URI).unwrap();
        let lines: Vec<&str> = rendered.lines().collect();
        assert!(lines.len() >= 21); // QR mínimo v1 = 21 módulos + borde
        // Todas las líneas del cuerpo tienen el mismo ancho (es cuadrado).
        let widths: std::collections::HashSet<usize> =
            lines.iter().map(|l| l.chars().count()).collect();
        assert_eq!(widths.len(), 1);
        // La primera y última línea son solo borde claro (zona silenciosa).
        let border_line: String = std::iter::repeat_n('█', lines[0].chars().count()).collect();
        assert_eq!(lines.first().unwrap(), &border_line);
        assert_eq!(lines.last().unwrap(), &border_line);
    }

    #[test]
    fn render_is_deterministic_for_same_uri() {
        assert_eq!(
            render_terminal(SAMPLE_URI).unwrap(),
            render_terminal(SAMPLE_URI).unwrap()
        );
    }

    #[test]
    fn oversized_uri_is_error_not_panic() {
        assert!(render_terminal(&"x".repeat(3000)).is_err());
    }
}
