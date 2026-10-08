//! Emisor del Stream de video en el Servidor PC — SPEC-003 §7 (lado origen).
//!
//! En v1 sin Plugin (REQ-006/007 pendientes) el origen es un patrón de
//! prueba generado aquí: demuestra el pipeline completo de SPEC-003
//! (start → frames consecutivos → stop) sobre la conexión persistente.
//! La captura real del juego entra con el Plugin.

use std::time::Instant;

use twinpad_core::stream::{FrameFormat, StreamMsg};

/// Un canal de emisión por sesión activa. `send` serializa y entrega la
/// línea por el stream retenido (SessionRegistry).
pub trait FrameSink {
    /// Envía una línea NDJSON; `false` = la sesión ya no está conectada.
    fn send_line(&mut self, line: &str) -> bool;
}

/// Errores del emisor (delimitados a la vida del stream; SPEC-003 §9 no
/// define códigos para el origen: el origen detiene el stream y notifica).
#[derive(Debug)]
pub enum VideoSourceError {
    /// La sesión dejó de recibir (conexión caída).
    Disconnected,
}

/// Parámetros del stream de demostración (v1 sin compresión: OQ-1).
pub const DEMO_WIDTH: u16 = 320;
pub const DEMO_HEIGHT: u16 = 180;
pub const DEMO_FPS: u8 = 30;

/// Genera un Framebuffer RGB8 con un patrón animado determinista:
/// gradiente + barra vertical que recorre la imagen según el frame.
/// Determinismo (ENG P10): mismo `fn` → mismos bytes.
pub fn demo_frame(fn_: u64, w: u16, h: u16) -> Vec<u8> {
    let mut buf = Vec::with_capacity(usize::from(w) * usize::from(h) * 3);
    let bar_x = (fn_ % u64::from(w)) as u16;
    for y in 0..h {
        for x in 0..w {
            let r = ((u32::from(x) * 255) / u32::from(w)) as u8;
            let g = ((u32::from(y) * 255) / u32::from(h)) as u8;
            let b = if x == bar_x { 255 } else { 64 };
            buf.extend_from_slice(&[r, g, b]);
        }
    }
    buf
}

/// Emite un stream completo de `frames` cuadros a `fps` sobre `sink`
/// (SPEC-003 §7 pasos 1–5). Bloquea el hilo caller; pensado para correr
/// en su propio hilo por sesión.
///
/// # Errors
/// `Disconnected` si el receptor deja de estar disponible a mitad del stream.
pub fn run_demo_stream(
    sink: &mut dyn FrameSink,
    frames: u64,
    fps: u8,
    w: u16,
    h: u16,
) -> Result<(), VideoSourceError> {
    let mut source = |fn_: u64| Some((w, h, demo_frame(fn_, w, h)));
    run_frame_stream(sink, frames, fps, &mut source)
}

/// Fuente de frames rgb8: recibe el `fn` y devuelve `(w, h, data)` o `None`
/// para terminar el stream limpio (p. ej. la captura falló o se detuvo).
pub type FrameSource<'a> = &'a mut dyn FnMut(u64) -> Option<(u16, u16, Vec<u8>)>;

/// Emite un stream de `frames` cuadros a `fps`, pidiendo cada framebuffer a
/// `source` (SPEC-003 §7 pasos 1–5). `None` de la fuente → `stream_stop`.
/// Bloquea el hilo caller; pensado para correr en su propio hilo por sesión.
///
/// # Errors
/// `Disconnected` si el receptor deja de estar disponible a mitad del stream.
pub fn run_frame_stream(
    sink: &mut dyn FrameSink,
    frames: u64,
    fps: u8,
    source: FrameSource,
) -> Result<(), VideoSourceError> {
    let Some((w, h, _)) = source(0) else {
        // Sin primer frame no hay stream que anunciar: fin limpio.
        return send(
            sink,
            &StreamMsg::StreamStop {
                reason: twinpad_core::stream::StreamStopReason::PluginStopped,
            },
        );
    };
    let (mut w, mut h) = (w, h);
    let start = StreamMsg::StreamStart {
        w,
        h,
        fmt: FrameFormat::Rgb8,
        fps,
    };
    send(sink, &start)?;

    let frame_period = std::time::Duration::from_millis(1_000 / u64::from(fps));
    let mut next_deadline = Instant::now();
    for fn_ in 0..frames {
        let Some((fw, fh, data)) = source(fn_) else {
            break; // la fuente se detuvo: §7 paso 5
        };
        // §8.1: si la resolución cambió a mitad de sesión, se anuncia un
        // `stream_start` nuevo con los parámetros vigentes; `fn` continúa.
        if fw != w || fh != h {
            w = fw;
            h = fh;
            send(
                sink,
                &StreamMsg::StreamStart {
                    w,
                    h,
                    fmt: FrameFormat::Rgb8,
                    fps,
                },
            )?;
        }
        let frame = StreamMsg::VideoFrame {
            fn_,
            ts: next_deadline.elapsed().as_millis() as u64,
            w: fw,
            h: fh,
            fmt: FrameFormat::Rgb8,
            data: base64_url(&data),
        };
        send(sink, &frame)?;
        next_deadline += frame_period;
        let now = Instant::now();
        if next_deadline > now {
            std::thread::sleep(next_deadline - now);
        }
    }

    send(
        sink,
        &StreamMsg::StreamStop {
            reason: twinpad_core::stream::StreamStopReason::PluginStopped,
        },
    )
}

fn send(sink: &mut dyn FrameSink, msg: &StreamMsg) -> Result<(), VideoSourceError> {
    let line = msg.encode().map_err(|_| VideoSourceError::Disconnected)?;
    if sink.send_line(&line) {
        Ok(())
    } else {
        Err(VideoSourceError::Disconnected)
    }
}

/// Resolución de salida de la captura de escritorio (v1 demo; 16:9).
pub const CAPTURE_WIDTH: u16 = 640;
pub const CAPTURE_HEIGHT: u16 = 360;

/// Emite el escritorio del PC capturado a `fps` (fuente temporal mientras
/// no hay Plugin: SPEC-003 §17, decisión del owner). Escala a 640×360.
///
/// # Errors
/// `Disconnected` si el receptor deja de estar disponible; un fallo de
/// captura detiene el stream limpio (no es un error del transporte).
pub fn run_desktop_stream(
    sink: &mut dyn FrameSink,
    frames: u64,
    fps: u8,
    grabber: &twinpad_capture::DesktopGrabber,
) -> Result<(), VideoSourceError> {
    let mut source = |fn_: u64| {
        let _ = fn_;
        grabber.frame().ok().map(|(sw, sh, rgba)| {
            (
                CAPTURE_WIDTH,
                CAPTURE_HEIGHT,
                twinpad_capture::rgb8_scaled(&rgba, sw, sh, CAPTURE_WIDTH, CAPTURE_HEIGHT),
            )
        })
    };
    run_frame_stream(sink, frames, fps, &mut source)
}

/// base64url canónico sin relleno (igual criterio que `core::message`).
fn base64_url(bytes: &[u8]) -> String {
    use base64::Engine as _;
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

// ENG §6: unwrap permitido solo en tests.
#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    struct SinkSpy(RefCell<Vec<String>>);
    impl FrameSink for SinkSpy {
        fn send_line(&mut self, line: &str) -> bool {
            self.0.borrow_mut().push(line.to_string());
            true
        }
    }

    #[test]
    fn demo_frame_is_deterministic_and_sized() {
        let a = demo_frame(5, 32, 16);
        let b = demo_frame(5, 32, 16);
        assert_eq!(a, b);
        assert_eq!(a.len(), 32 * 16 * 3);
        // El patrón cambia con el frame (barra móvil).
        assert_ne!(demo_frame(0, 32, 16), demo_frame(1, 32, 16));
    }

    #[test]
    fn demo_stream_sends_start_frames_and_stop_in_order() {
        let mut sink = SinkSpy(RefCell::new(Vec::new()));
        run_demo_stream(&mut sink, 3, 60, 16, 16).unwrap();
        let lines = sink.0.borrow();
        assert_eq!(lines.len(), 5); // start + 3 frames + stop
        assert!(lines[0].starts_with("{\"type\":\"stream_start\""));
        for (expected_fn, line) in lines[1..4].iter().enumerate() {
            assert!(line.starts_with("{\"type\":\"video_frame\""));
            assert!(line.contains(&format!("\"fn_\":{expected_fn}")));
        }
        assert!(lines[4].starts_with("{\"type\":\"stream_stop\""));
        // Cada línea es decodificable por el codec compartido (paridad).
        for line in lines.iter() {
            StreamMsg::decode(line).unwrap();
        }
    }

    #[test]
    fn disconnected_sink_stops_the_source() {
        struct DeadSink;
        impl FrameSink for DeadSink {
            fn send_line(&mut self, _line: &str) -> bool {
                false
            }
        }
        let mut sink = DeadSink;
        assert!(matches!(
            run_demo_stream(&mut sink, 3, 60, 16, 16),
            Err(VideoSourceError::Disconnected)
        ));
    }
}
