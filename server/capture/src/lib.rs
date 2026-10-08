//! Captura (PC) — captura de Framebuffers y audio del juego integrado (ARCH §2).
//!
//! Respeta la frontera ARCH §3: `Captura (PC)` → `Twinpad Core (PC)` vía
//! stream de frames/audio; sin renderizado ni lógica de UI.
//!
//! Fuente temporal de la demo (SPEC-003 §17, decisión del owner): mientras
//! no exista el Plugin (SPEC-006), el stream emite el **escritorio** del PC
//! capturado con `xcap`. La conversión a `rgb8` y el escalado son puros y
//! testeables (ENG P10); el efecto real de captura vive solo en
//! [`DesktopGrabber`], el adaptador inyectable.

/// Error de la captura de escritorio (delimitado al grabber).
#[derive(Debug)]
pub enum GrabError {
    /// No hay monitor disponible para capturar.
    NoMonitor,
    /// El backend de captura del SO falló (permisos, Wayland, etc.).
    Backend,
}

/// Captura de escritorio del PC host. Implementación única y reutilizable
/// por sesión; `frame` entrega el buffer RGBA fila-mayor del monitor.
pub struct DesktopGrabber {
    monitor: xcap::Monitor,
}

impl DesktopGrabber {
    /// Abre el primer monitor disponible (v1: un solo escritorio).
    ///
    /// # Errors
    /// [`GrabError::NoMonitor`] si el SO no expone monitores (o la
    /// plataforma de captura no está disponible, p. ej. Wayland v1).
    pub fn new() -> Result<Self, GrabError> {
        let monitors = xcap::Monitor::all().map_err(|_| GrabError::Backend)?;
        let monitor = monitors.into_iter().next().ok_or(GrabError::NoMonitor)?;
        Ok(Self { monitor })
    }

    /// Un frame del escritorio: `(width, height, rgba)` fila-mayor, 4 B/px.
    ///
    /// # Errors
    /// [`GrabError::Backend`] si la captura del SO falla en este frame.
    pub fn frame(&self) -> Result<(u32, u32, Vec<u8>), GrabError> {
        let image = self
            .monitor
            .capture_image()
            .map_err(|_| GrabError::Backend)?;
        let (width, height) = (image.width(), image.height());
        Ok((width, height, image.into_raw()))
    }
}

/// Convierte RGBA fila-mayor a `rgb8` (3 B/px, SPEC-003 §12) escalado a
/// `dw×dh` por vecino más próximo (R3: avanzar al frame más reciente
/// permite el escalado sin resample caro). Alpha descartado.
pub fn rgb8_scaled(rgba: &[u8], sw: u32, sh: u32, dw: u16, dh: u16) -> Vec<u8> {
    let mut out = Vec::with_capacity(usize::from(dw) * usize::from(dh) * 3);
    if sw == 0 || sh == 0 {
        out.resize(out.capacity(), 0);
        return out;
    }
    for y in 0..u32::from(dh) {
        let sy = (y * sh / u32::from(dh)) as usize;
        for x in 0..u32::from(dw) {
            let sx = (x * sw / u32::from(dw)) as usize;
            let i = (sy * usize::try_from(sw).unwrap_or(0) + sx) * 4;
            if i + 2 < rgba.len() {
                out.extend_from_slice(&[rgba[i], rgba[i + 1], rgba[i + 2]]);
            } else {
                out.extend_from_slice(&[0, 0, 0]);
            }
        }
    }
    out
}

// ENG §6: unwrap permitido solo en tests.
#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    /// RGBA 2×2: negro, rojo, verde, blanco (fila-mayor).
    fn sample() -> Vec<u8> {
        vec![
            0, 0, 0, 255, // (0,0) negro
            255, 0, 0, 255, // (1,0) rojo
            0, 255, 0, 255, // (0,1) verde
            255, 255, 255, 255, // (1,1) blanco
        ]
    }

    #[test]
    fn same_size_conversion_drops_alpha() {
        let out = rgb8_scaled(&sample(), 2, 2, 2, 2);
        assert_eq!(out.len(), 2 * 2 * 3);
        assert_eq!(&out[0..3], &[0, 0, 0]);
        assert_eq!(&out[3..6], &[255, 0, 0]);
        assert_eq!(&out[6..9], &[0, 255, 0]);
        assert_eq!(&out[9..12], &[255, 255, 255]);
    }

    #[test]
    fn downscale_keeps_proportions_by_nearest_neighbor() {
        // 2×2 → 1×1: toma el píxel (0,0).
        let out = rgb8_scaled(&sample(), 2, 2, 1, 1);
        assert_eq!(out, vec![0, 0, 0]);
        // 2×2 → 2×1: toma (0,0) y (1,0) de la primera fila.
        let row = rgb8_scaled(&sample(), 2, 2, 2, 1);
        assert_eq!(row, vec![0, 0, 0, 255, 0, 0]);
    }

    #[test]
    fn empty_source_yields_black_frame_of_expected_size() {
        let out = rgb8_scaled(&[], 0, 0, 4, 4);
        assert_eq!(out, vec![0u8; 4 * 4 * 3]);
    }

    #[test]
    fn short_source_does_not_panic_and_fills_black() {
        let out = rgb8_scaled(&[7, 8, 9, 255], 1, 1, 2, 2);
        assert_eq!(out.len(), 2 * 2 * 3);
        assert_eq!(&out[0..3], &[7, 8, 9]);
    }
}
