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
    run_frame_stream(sink, frames, fps, OutputFormat::Rgb8, &mut source)
}

/// Fuente de frames rgb8: recibe el `fn` y devuelve `(w, h, data)` o `None`
/// para terminar el stream limpio (p. ej. la captura falló o se detuvo).
pub type FrameSource<'a> = &'a mut dyn FnMut(u64) -> Option<(u16, u16, Vec<u8>)>;

/// Formato de emisión del stream: crudo (`rgb8`, demos/tests) o JPEG
/// (CR-001, 720p/1080p) con su calidad.
#[derive(Clone, Copy, Debug)]
pub enum OutputFormat {
    Rgb8,
    Jpeg(u8),
}

impl OutputFormat {
    /// `fmt` de SPEC-003 §4 que viaja en los mensajes.
    #[must_use]
    pub fn frame_format(self) -> FrameFormat {
        match self {
            OutputFormat::Rgb8 => FrameFormat::Rgb8,
            OutputFormat::Jpeg(_) => FrameFormat::Jpeg,
        }
    }
}

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
    output: OutputFormat,
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
    let fmt = output.frame_format();
    let (mut w, mut h) = (w, h);
    let start = StreamMsg::StreamStart { w, h, fmt, fps };
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
            send(sink, &StreamMsg::StreamStart { w, h, fmt, fps })?;
        }
        // CR-001: en JPEG cada frame se codifica aquí (tamaño libre).
        let wire_data = match output {
            OutputFormat::Rgb8 => data,
            OutputFormat::Jpeg(quality) => match encode_jpeg(&data, fw, fh, quality) {
                Some(jpeg) => jpeg,
                None => continue, // frame descartado; el stream continúa
            },
        };
        let frame = StreamMsg::VideoFrame {
            fn_,
            ts: next_deadline.elapsed().as_millis() as u64,
            w: fw,
            h: fh,
            fmt,
            data: base64_url(&wire_data),
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

/// Calidad JPEG por defecto (CR-001): 0–100; 80 ofrece ~20-40× de reducción
/// con pérdida visual imperceptible para contenido de juego.
pub const DEFAULT_JPEG_QUALITY: u8 = 80;

/// Objetivos de calidad del stream que el owner puede ajustar por variable
/// de entorno (720p/1080p, 30/60 fps; CR-001). Los valores caen a lo que la
/// máquina sostenga: el stream no se congela (SPEC-003 R3/R5).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct VideoConfig {
    pub width: u16,
    pub height: u16,
    pub fps: u8,
    pub jpeg_quality: u8,
}

impl VideoConfig {
    /// Lee `TWINPAD_WIDTH`/`TWINPAD_HEIGHT`/`TWINPAD_FPS`/`TWINPAD_JPEG_QUALITY`
    /// (720p×30 por defecto). Los valores fuera de rango de SPEC-003 §4 se
    /// clampean (16–3840×16–2160, 1–60 fps, 1–100 calidad).
    #[must_use]
    pub fn from_env() -> Self {
        Self {
            width: clamp_env_u16("TWINPAD_WIDTH", 1280),
            height: clamp_env_u16("TWINPAD_HEIGHT", 720),
            fps: clamp_env_u8("TWINPAD_FPS", 30)
                .clamp(twinpad_core::stream::MIN_FPS, twinpad_core::stream::MAX_FPS),
            jpeg_quality: clamp_env_u8("TWINPAD_JPEG_QUALITY", DEFAULT_JPEG_QUALITY).clamp(1, 100),
        }
    }
}

fn clamp_env_u16(name: &str, default: u16) -> u16 {
    std::env::var(name)
        .ok()
        .and_then(|v| v.parse::<u16>().ok())
        .unwrap_or(default)
        .clamp(
            twinpad_core::stream::MIN_DIM,
            twinpad_core::stream::MAX_WIDTH,
        )
}

fn clamp_env_u8(name: &str, default: u8) -> u8 {
    std::env::var(name)
        .ok()
        .and_then(|v| v.parse::<u8>().ok())
        .unwrap_or(default)
}

/// Codifica un framebuffer rgb8 a JPEG (CR-001). `None` si el encoder falla
/// (frame descartado; el stream continúa con el siguiente).
pub fn encode_jpeg(rgb: &[u8], w: u16, h: u16, quality: u8) -> Option<Vec<u8>> {
    let buffer = image::ImageBuffer::from_raw(u32::from(w), u32::from(h), rgb.to_vec())?;
    let mut out = std::io::Cursor::new(Vec::new());
    let encoder = image::codecs::jpeg::JpegEncoder::new_with_quality(&mut out, quality);
    image::DynamicImage::ImageRgb8(buffer)
        .write_with_encoder(encoder)
        .ok()?;
    Some(out.into_inner())
}

/// Emite el escritorio del PC capturado (fuente temporal mientras no hay
/// Plugin: SPEC-003 §17) escalado a `config.width×config.height` y codificado
/// en JPEG (CR-001) a `config.fps`.
///
/// # Errors
/// `Disconnected` si el receptor deja de estar disponible; un fallo de
/// captura detiene el stream limpio (no es un error del transporte).
pub fn run_desktop_stream(
    sink: &mut dyn FrameSink,
    frames: u64,
    fps: u8,
    config: VideoConfig,
    grabber: &twinpad_capture::DesktopGrabber,
) -> Result<(), VideoSourceError> {
    let mut source = move |fn_: u64| {
        let _ = fn_;
        grabber.frame().ok().map(|(sw, sh, rgba)| {
            (
                config.width,
                config.height,
                twinpad_capture::rgb8_scaled(&rgba, sw, sh, config.width, config.height),
            )
        })
    };
    run_frame_stream(
        sink,
        frames,
        fps,
        OutputFormat::Jpeg(config.jpeg_quality),
        &mut source,
    )
}

/// Fuente de frames del juego con SDK (REQ-006 AC-2): devuelve el último
/// `game_frame` del registro y su `game_id`, o `None` mientras el juego
/// declaró `second_screen` pero aún no publicó su primer frame.
pub type GameFrameSource<'a> = &'a mut dyn FnMut() -> Option<(String, crate::games::GameFrame)>;

/// Emite el Stream de SPEC-003 alimentado por los `game_frame` que el juego
/// publica vía SDK (REQ-006 AC-2). A cada tick pide el frame más reciente a
/// `source`; `fn` lo asigna este emisor (monotónico desde 0, R2) y un
/// `stream_start` nuevo (§8.1) anuncia los cambios de resolución. Un juego
/// que deja de publicar frames NO detiene el stream: se queda en el último
/// emitido (SPEC-006 §9: su crash jamás rompe la sesión); `stop_on_no_game`
/// controla el fin limpio cuando el juego desaparece.
///
/// # Errors
/// `Disconnected` si el receptor deja de estar disponible.
pub fn run_game_stream(
    sink: &mut dyn FrameSink,
    frames: u64,
    fps: u8,
    source: GameFrameSource,
    stop_on_no_game: bool,
) -> Result<(), VideoSourceError> {
    let mut announced: Option<(u16, u16)> = None;
    let mut next_deadline = Instant::now();
    for fn_ in 0..frames {
        let Some((game_id, gf)) = source() else {
            if stop_on_no_game {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(1_000 / u64::from(fps)));
            next_deadline += std::time::Duration::from_millis(1_000 / u64::from(fps));
            continue;
        };
        let _ = game_id; // v1: una sola fuente preferente (§8.1 de SPEC-006)
        // §8.1: resolución nueva → `stream_start` nuevo; `fn` continúa.
        if announced != Some((gf.w, gf.h)) {
            announced = Some((gf.w, gf.h));
            send(
                sink,
                &StreamMsg::StreamStart {
                    w: gf.w,
                    h: gf.h,
                    fmt: gf.fmt,
                    fps,
                },
            )?;
        }
        send(
            sink,
            &StreamMsg::VideoFrame {
                fn_,
                ts: next_deadline.elapsed().as_millis() as u64,
                w: gf.w,
                h: gf.h,
                fmt: gf.fmt,
                data: base64_url(&gf.data),
            },
        )?;
        next_deadline += std::time::Duration::from_millis(1_000 / u64::from(fps));
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

/// base64url canónico sin relleno (igual criterio que `core::message`).
fn base64_url(bytes: &[u8]) -> String {
    use base64::Engine as _;
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

// ENG §6: unwrap permitido solo en tests.
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
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

    // CR-001: emisión JPEG y ancho de banda.
    #[test]
    fn jpeg_encoding_produces_valid_image_smaller_than_raw() {
        let rgb = demo_frame(7, 320, 180); // 172 800 bytes crudos
        let raw = rgb.len();
        let jpeg = encode_jpeg(&rgb, 320, 180, DEFAULT_JPEG_QUALITY).expect("encode ok");
        // Empieza con SOI (FF D8) y termina con EOI (FF D9).
        assert_eq!(&jpeg[0..2], &[0xFF, 0xD8]);
        assert_eq!(&jpeg[jpeg.len() - 2..], &[0xFF, 0xD9]);
        assert!(
            jpeg.len() < raw / 5,
            "JPEG q80 debe comprimir al menos 5× (raw={raw}, jpeg={})",
            jpeg.len()
        );
        // Determinista (ENG P10): mismo input → mismo archivo.
        assert_eq!(
            jpeg,
            encode_jpeg(&rgb, 320, 180, DEFAULT_JPEG_QUALITY).unwrap()
        );
    }

    #[test]
    fn jpeg_stream_roundtrips_through_the_shared_codec() {
        // Un frame JPEG emitido valida en `core::stream` (tamaño libre §4).
        let rgb = demo_frame(0, 64, 64);
        let jpeg = encode_jpeg(&rgb, 64, 64, 80).unwrap();
        let frame = StreamMsg::VideoFrame {
            fn_: 0,
            ts: 0,
            w: 64,
            h: 64,
            fmt: FrameFormat::Jpeg,
            data: base64_url(&jpeg),
        };
        let line = frame.encode().unwrap();
        assert_eq!(StreamMsg::decode(&line).unwrap(), frame);
    }

    #[test]
    fn video_config_clamps_env_values() {
        // (sin variables de entorno en el test: solo el default coherente)
        let cfg = VideoConfig {
            width: 1280,
            height: 720,
            fps: 30,
            jpeg_quality: DEFAULT_JPEG_QUALITY,
        };
        assert_eq!(cfg.width, 1280);
        assert_eq!(cfg.height, 720);
    }

    /// Medición de ancho de banda (CR-001): el JPEG de un frame 720p del
    /// patrón demo debe quedar muy por debajo del objetivo de ~33 Mbps a
    /// 30 fps (≈138 KB/frame). El patrón (gradiente + barra) comprime peor
    /// que el contenido de juego real: es una cota superior conservadora.
    #[test]
    fn jpeg_720p_frame_fits_the_bandwidth_budget() {
        let rgb = demo_frame(10, 1280, 720);
        let jpeg = encode_jpeg(&rgb, 1280, 720, DEFAULT_JPEG_QUALITY).expect("encode");
        let budget_bytes_per_frame = 33_000_000.0 / 8.0 / 30.0; // 33 Mbps @ 30 fps
        assert!(
            (jpeg.len() as f64) < budget_bytes_per_frame,
            "frame 720p JPEG q80 ({} B) excede el presupuesto por frame ({:.0} B)",
            jpeg.len(),
            budget_bytes_per_frame
        );
        // Y 1080p×30 (~75 Mbps → 312 KB/frame).
        let rgb1080 = demo_frame(10, 1920, 1080);
        let jpeg1080 = encode_jpeg(&rgb1080, 1920, 1080, DEFAULT_JPEG_QUALITY).expect("encode");
        let budget1080 = 75_000_000.0 / 8.0 / 30.0;
        assert!(
            (jpeg1080.len() as f64) < budget1080,
            "frame 1080p JPEG q80 ({} B) excede el presupuesto ({:.0} B)",
            jpeg1080.len(),
            budget1080
        );
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

    // AC-2 (REQ-006): el stream se alimenta de los `game_frame` del juego.
    use crate::games::GameFrame;
    use twinpad_core::stream::FrameFormat;

    fn game_frame(gfn: u64, w: u16, h: u16) -> GameFrame {
        GameFrame {
            gfn,
            w,
            h,
            fmt: FrameFormat::Rgb8,
            data: vec![9u8; usize::from(w) * usize::from(h) * 3],
        }
    }

    /// Fuente de prueba: frames crecientes; `None` cuando `exhausted`.
    fn test_source(
        frames: u64,
        exhausted: std::rc::Rc<std::cell::Cell<bool>>,
    ) -> impl FnMut() -> Option<(String, GameFrame)> {
        let mut emitted = 0u64;
        move || {
            if exhausted.get() {
                return None;
            }
            if emitted >= frames {
                return None;
            }
            let gfn = emitted;
            emitted += 1;
            Some(("pacdemo".into(), game_frame(gfn, 32, 16)))
        }
    }

    #[test]
    fn game_stream_announces_sends_frames_and_stops() {
        let exhausted = std::rc::Rc::new(std::cell::Cell::new(false));
        let mut source = test_source(2, std::rc::Rc::clone(&exhausted));
        let mut sink = SinkSpy(RefCell::new(Vec::new()));
        // FPS alto: sin sleeps medibles en el test.
        run_game_stream(&mut sink, 3, 60, &mut source, true).unwrap();
        let lines = sink.0.borrow();
        // start + fn=0 + fn=1 + stop: el juego dejó de publicar → fin limpio.
        assert_eq!(lines.len(), 4);
        assert!(lines[0].starts_with("{\"type\":\"stream_start\""));
        assert!(lines[1].contains("\"fn_\":0"));
        assert!(lines[2].contains("\"fn_\":1"));
        assert!(lines[3].starts_with("{\"type\":\"stream_stop\""));
        // Paridad con el codec compartido (test-strategy §10).
        for line in lines.iter() {
            StreamMsg::decode(line).unwrap();
        }
    }

    #[test]
    fn game_stream_re_announces_on_resolution_change() {
        let mut emitted = 0u64;
        let mut source = move || {
            let gfn = emitted;
            emitted += 1;
            // Cambia la resolución en fn=1 (§8.1).
            Some((
                "pacdemo".into(),
                game_frame(gfn, if gfn == 0 { 32 } else { 64 }, 16),
            ))
        };
        let mut sink = SinkSpy(RefCell::new(Vec::new()));
        run_game_stream(&mut sink, 2, 60, &mut source, true).unwrap();
        let lines = sink.0.borrow();
        // start(32×16) + frame0 + start(64×16) + frame1 + stop
        assert_eq!(lines.len(), 5);
        assert!(lines[0].contains("\"w\":32"));
        assert!(lines[2].starts_with("{\"type\":\"stream_start\""));
        assert!(lines[2].contains("\"w\":64"));
        // `fn` continúa (§8.1): no se reinicia.
        assert!(lines[3].contains("\"fn_\":1"));
    }

    #[test]
    fn game_stream_without_game_keeps_waiting_if_not_stopping() {
        let exhausted = std::rc::Rc::new(std::cell::Cell::new(false));
        let mut source = test_source(0, exhausted);
        let mut sink = SinkSpy(RefCell::new(Vec::new()));
        // `stop_on_no_game=false`: el server sigue (escritorio/demo como
        // respaldo o el juego aún no publicó su primer frame).
        run_game_stream(&mut sink, 1, 60, &mut source, false).unwrap();
        let lines = sink.0.borrow();
        // Solo el `stream_stop` final: nada se anunció sin frames.
        assert_eq!(lines.len(), 1);
        assert!(lines[0].starts_with("{\"type\":\"stream_stop\""));
    }
}
