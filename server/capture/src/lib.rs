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
//!
//! En Wayland la captura de VIDEO usa [`wayland_pipewire::PortalCapture`]:
//! portal ScreenCast + conexión PipeWire **por el fd del portal** (medido
//! 2026-10-09: el shortcut de xcap por `capture_image` tarda ~700 ms/frame
//! por el round-trip DBus, y el `video_recorder` queda `idle` al conectar
//! al core por defecto con un `node.target` del portal que solo existe
//! dentro de esa conexión remota). Si el portal no está disponible cae al
//! modo screenshot (degradación R3 de SPEC-003).

mod wayland_pipewire;

use wayland_pipewire::PortalCapture;

/// Backend de captura activo (diagnóstico/telemetría; `Debug` para el log).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GrabMode {
    /// Stream PipeWire continuo vía portal (Wayland).
    PipeWire,
    /// Screenshot puntual del portal (fallback): cadencia limitada por el
    /// round-trip del portal (~1 fps medido), solo para no perder el stream.
    PortalScreenshot,
}

/// Error de la captura de escritorio (delimitado al grabber).
#[derive(Debug)]
pub enum GrabError {
    /// No hay monitor disponible para capturar.
    NoMonitor,
    /// El backend de captura del SO falló (permisos, Wayland, etc.).
    Backend,
}

/// Orden de canal del buffer 4 B/px del frame (ENG P18: el escalador no
/// asume un orden — PipeWire entrega el formato nativo del panel, típicamente
/// `BGRx`, y asumir R,G,B en los bytes 0..3 invierte rojo y azul). El layout
/// se negocia en la sesión y viaja junto al buffer.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ChannelLayout {
    /// `R,G,B,A` (xcap y pantalla-screenshot; también lo que produce el
    /// escalador de salida).
    Rgba,
    /// `B,G,R,x` (formato nativo de KWin/wlroots en PipeWire).
    Bgrx,
    /// `x,R,G,B`.
    Xrgb,
    /// `R,G,B,x`.
    Rgbx,
}

impl ChannelLayout {
    /// Índices `[R, G, B]` dentro del píxel de 4 B.
    #[must_use]
    pub fn rgb_indices(self) -> [usize; 3] {
        match self {
            Self::Rgba | Self::Rgbx => [0, 1, 2],
            Self::Bgrx => [2, 1, 0],
            Self::Xrgb => [1, 2, 3],
        }
    }
}

/// Captura de escritorio del PC host. Implementación única y reutilizable
/// por sesión; `frame` entrega el buffer 4 B/px fila-mayor del monitor y el
/// layout de canal con el que leerlo.
pub struct DesktopGrabber {
    /// Captura continua por portal fd (Wayland; `None` en X11 o sin portal).
    continuous: Option<PortalCapture>,
    monitor: xcap::Monitor,
    mode: GrabMode,
}

impl DesktopGrabber {
    /// Abre el primer monitor disponible (v1: un solo escritorio). En
    /// Wayland abre el stream continuo del portal; si el portal rechaza o
    /// no está, cae al modo screenshot (`GrabMode::PortalScreenshot`).
    ///
    /// # Errors
    /// [`GrabError::NoMonitor`] si el SO no expone monitores (o la
    /// plataforma de captura no está disponible).
    pub fn new() -> Result<Self, GrabError> {
        let monitors = xcap::Monitor::all().map_err(|_| GrabError::Backend)?;
        let monitor = monitors.into_iter().next().ok_or(GrabError::NoMonitor)?;
        let continuous = PortalCapture::start();
        let mode = if continuous.is_ok() {
            GrabMode::PipeWire
        } else {
            GrabMode::PortalScreenshot
        };
        Ok(Self {
            continuous: continuous.ok(),
            monitor,
            mode,
        })
    }

    /// Backend activo (log de arranque del server).
    #[must_use]
    pub fn mode(&self) -> GrabMode {
        self.mode
    }

    /// Un frame del escritorio: `(width, height, layout, buffer)` fila-mayor,
    /// 4 B/px con el orden de canal declarado en `layout`.
    ///
    /// En modo continuo espera (con timeouts) el siguiente frame del
    /// compositor y entrega el más reciente. En modo screenshot hace la
    /// captura puntual (siempre RGBA).
    ///
    /// # Errors
    /// [`GrabError::Backend`] si la captura del SO falla en este frame.
    pub fn frame(&mut self) -> Result<(u32, u32, ChannelLayout, Vec<u8>), GrabError> {
        if let Some(capture) = &mut self.continuous {
            return capture.next_frame().ok_or(GrabError::Backend);
        }
        let image = self
            .monitor
            .capture_image()
            .map_err(|_| GrabError::Backend)?;
        let (width, height) = (image.width(), image.height());
        Ok((width, height, ChannelLayout::Rgba, image.into_raw()))
    }
}

/// Convierte un frame 4 B/px fila-mayor a `rgb8` (3 B/px, SPEC-003 §12)
/// escalado a `dw×dh` por vecino más próximo (R3: tomar el frame más
/// reciente permite reescalar sin resample caro). Alpha descartado.
///
/// POR QUÉ el `layout`: el buffer de PipeWire conserva el orden de canal
/// del panel (`BGRx` medido en KWin); leer R en el byte 0 con ese layout
/// presentaba rojo y azul intercambiados. Solo se leen los 3 índices que
/// `layout::rgb_indices` declara.
pub fn rgb8_scaled(
    frame: &[u8],
    sw: u32,
    sh: u32,
    dw: u16,
    dh: u16,
    layout: ChannelLayout,
) -> Vec<u8> {
    let [ir, ig, ib] = layout.rgb_indices();
    let output_len = usize::from(dw) * usize::from(dh) * 3;
    let mut out = vec![0; output_len];
    if sw == 0 || sh == 0 || dw == 0 || dh == 0 {
        return out;
    }
    let source_width = usize::try_from(sw).unwrap_or(0);
    let mut output_index = 0;
    for y in 0..u32::from(dh) {
        let sy = (y * sh / u32::from(dh)) as usize;
        let source_row = sy * source_width * 4;
        for x in 0..u32::from(dw) {
            let sx = (x * sw / u32::from(dw)) as usize;
            let i = source_row + sx * 4;
            if i + ib < frame.len() {
                out[output_index] = frame[i + ir];
                out[output_index + 1] = frame[i + ig];
                out[output_index + 2] = frame[i + ib];
            }
            output_index += 3;
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

    // TEST-032 — verifies SPEC-003: pre-sized RGB scaling preserves channel/layout contract.
    #[test]
    fn same_size_conversion_drops_alpha() {
        let out = rgb8_scaled(&sample(), 2, 2, 2, 2, ChannelLayout::Rgba);
        assert_eq!(out.len(), 2 * 2 * 3);
        assert_eq!(&out[0..3], &[0, 0, 0]);
        assert_eq!(&out[3..6], &[255, 0, 0]);
        assert_eq!(&out[6..9], &[0, 255, 0]);
        assert_eq!(&out[9..12], &[255, 255, 255]);
    }

    /// Regresión del color invertido 2026-10-10: el pipeline entregaba
    /// buffers `BGRx` y los leía como R,G,B fijos — rojo y azul cambiados.
    #[test]
    fn bgrx_layout_does_not_swap_red_and_blue() {
        // Píxel BGRx puro: azul=255 en byte 0, rojo=255 en byte 2.
        let bgrx_red: Vec<u8> = vec![0, 0, 255, 0]; // rojo visto en BGRx
        let bgrx_blue: Vec<u8> = vec![255, 0, 0, 0]; // azul visto en BGRx
        let red = rgb8_scaled(&bgrx_red, 1, 1, 1, 1, ChannelLayout::Bgrx);
        let blue = rgb8_scaled(&bgrx_blue, 1, 1, 1, 1, ChannelLayout::Bgrx);
        assert_eq!(red, vec![255, 0, 0]);
        assert_eq!(blue, vec![0, 0, 255]);
    }

    #[test]
    fn xrgb_layout_reads_the_second_byte_first() {
        let xrgb = vec![0, 255, 0, 0]; // R=255 en byte 1
        let out = rgb8_scaled(&xrgb, 1, 1, 1, 1, ChannelLayout::Xrgb);
        assert_eq!(out, vec![255, 0, 0]);
    }

    #[test]
    fn downscale_keeps_proportions_by_nearest_neighbor() {
        // 2×2 → 1×1: toma el píxel (0,0).
        let out = rgb8_scaled(&sample(), 2, 2, 1, 1, ChannelLayout::Rgba);
        assert_eq!(out, vec![0, 0, 0]);
        // 2×2 → 2×1: toma (0,0) y (1,0) de la primera fila.
        let row = rgb8_scaled(&sample(), 2, 2, 2, 1, ChannelLayout::Rgba);
        assert_eq!(row, vec![0, 0, 0, 255, 0, 0]);
    }

    #[test]
    fn empty_source_yields_black_frame_of_expected_size() {
        let out = rgb8_scaled(&[], 0, 0, 4, 4, ChannelLayout::Rgba);
        assert_eq!(out, vec![0u8; 4 * 4 * 3]);
    }

    #[test]
    fn short_source_does_not_panic_and_fills_black() {
        let out = rgb8_scaled(&[7, 8, 9, 255], 1, 1, 2, 2, ChannelLayout::Rgba);
        assert_eq!(out.len(), 2 * 2 * 3);
        assert_eq!(&out[0..3], &[7, 8, 9]);
    }
}
