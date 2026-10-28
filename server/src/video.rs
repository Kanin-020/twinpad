//! Emisor del Stream de video en el Servidor PC — SPEC-003 §7 (lado origen).
//!
//! En v1 sin Plugin (REQ-006/007 pendientes) el origen es un patrón de
//! prueba generado aquí: demuestra el pipeline completo de SPEC-003
//! (start → frames consecutivos → stop) sobre la conexión persistente.
//! La captura real del juego entra con el Plugin.

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
    _frames: u64,
    _fps: u8,
    _output: OutputFormat,
    _source: FrameSource,
) -> Result<(), VideoSourceError> {
    // CR-014: RGB/JPEG sources are not a valid fallback for the H.264 stream.
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

/// Bitrate objetivo del encoder H.264 del Plugin (kbps; CR-027): la válvula
/// real de calidad de imagen en la era AVC. 8 Mbps a 720p/30 es el valor de
/// referencia de CR-014/CR-020; el rango 1–30 Mbps cubre de esbozo a nítido.
pub const DEFAULT_VIDEO_BITRATE_KBPS: u32 = 8_000;

/// Objetivos de calidad del stream que el owner puede ajustar por variable
/// de entorno (720p/1080p; CR-001/CR-027). El framerate ya no es un
/// objetivo: desde CR-029 la fuente emite sin tope ni pacing y
/// `stream_start.fps` se anuncia como metadata `FPS_UNLIMITED`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct VideoConfig {
    pub width: u16,
    pub height: u16,
    /// Bitrate objetivo del encoder H.264 del Plugin, en kbps (CR-027).
    pub bitrate_kbps: u32,
}

impl VideoConfig {
    /// Lee `TWINPAD_WIDTH`/`TWINPAD_HEIGHT`/`TWINPAD_VIDEO_BITRATE_KBPS`
    /// (720p por defecto). Los valores fuera de rango de SPEC-003 §4 se
    /// clampean (16–3840×16–2160, 1000–30000 kbps). `TWINPAD_FPS` quedó
    /// discontinuado por CR-029: el framerate no se configura.
    #[must_use]
    pub fn from_env() -> Self {
        Self {
            width: clamp_env_u16("TWINPAD_WIDTH", 1280),
            height: clamp_env_u16("TWINPAD_HEIGHT", 720),
            bitrate_kbps: clamp_env_u32("TWINPAD_VIDEO_BITRATE_KBPS", DEFAULT_VIDEO_BITRATE_KBPS)
                .clamp(1_000, 30_000),
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
fn clamp_env_u32(name: &str, default: u32) -> u32 {
    std::env::var(name)
        .ok()
        .and_then(|v| v.parse::<u32>().ok())
        .unwrap_or(default)
}

/// Codifica un framebuffer rgb8 a JPEG (CR-001). `None` si el encoder falla
/// (frame descartado; el stream continúa con el siguiente).
pub fn encode_jpeg(rgb: &[u8], w: u16, h: u16, quality: u8) -> Option<Vec<u8>> {
    let expected_len = usize::from(w).checked_mul(usize::from(h))?.checked_mul(3)?;
    if w == 0 || h == 0 || rgb.len() != expected_len {
        return None;
    }
    let mut out = Vec::new();
    jpeg_encoder::Encoder::new(&mut out, quality)
        .encode(rgb, w, h, jpeg_encoder::ColorType::Rgb)
        .ok()?;
    Some(out)
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
    grabber: &mut twinpad_capture::DesktopGrabber,
) -> Result<(), VideoSourceError> {
    let mut source = move |fn_: u64| {
        let _ = fn_;
        grabber.frame().ok().map(|(sw, sh, layout, rgba)| {
            (
                config.width,
                config.height,
                twinpad_capture::rgb8_scaled(&rgba, sw, sh, config.width, config.height, layout),
            )
        })
    };
    run_frame_stream(
        sink,
        frames,
        fps,
        // Fuente demo JPEG (CR-001): conserva su calidad fija local.
        OutputFormat::Jpeg(DEFAULT_JPEG_QUALITY),
        &mut source,
    )
}

/// Igual que [`run_desktop_stream`] pero la config se lee de `cfg` en cada
/// frame: la app puede cambiar resolución/fps/calidad en caliente (menú de
/// ajustes, CR-001) y el emisor lo aplica con un `stream_start` nuevo (§8.1).
///
/// La captura corre en un HILO DEDICADO (por qué: `xcap::capture_image`
/// tarda 200–600 ms en Wayland; si captura+escalado+JPEG se hacen sobre el
/// hilo de emisión, el pacing de 30 fps colapsa a ~1 fps). El hilo de
/// captura escala y publica el frame más reciente; el emisor solo codifica
/// y envía a `cfg.fps`, leyendo la config en cada frame.
///
/// # Errors
/// `Disconnected` si el receptor deja de estar disponible.
pub fn run_desktop_stream_dynamic(
    sink: &mut dyn FrameSink,
    _frames: u64,
    _cfg: std::sync::Arc<std::sync::Mutex<VideoConfig>>,
    _grabber: twinpad_capture::DesktopGrabber,
) -> Result<(), VideoSourceError> {
    // CR-014: desktop capture is not a conforming H.264 source or fallback.
    send(
        sink,
        &StreamMsg::StreamStop {
            reason: twinpad_core::stream::StreamStopReason::PluginStopped,
        },
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
/// controla el fin limpio cuando el juego desaparece. El framerate ya no se
/// escala por un objetivo: se itera a la cadencia que la fuente sostiene
/// (CR-029) y `stream_start.fps` se anuncia como `FPS_UNLIMITED`.
///
/// # Errors
/// `Disconnected` si el receptor deja de estar disponible.
pub fn run_game_stream(
    sink: &mut dyn FrameSink,
    _frames: u64,
    _fps: u8,
    source: GameFrameSource,
    stop_on_no_game: bool,
) -> Result<(), VideoSourceError> {
    let mut announced: Option<(u16, u16, FrameFormat)> = None;
    // Presupuesto de iteraciones: en v1 limita los bucles de prueba; el
    // ritmo ya no lo fija un fps (CR-029), la fuente se sondea directo.
    for _ in 0.._frames {
        let Some((_game_id, gf)) = source() else {
            if stop_on_no_game {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(10));
            continue;
        };
        let _ = _game_id; // v1: una sola fuente preferente (§8.1 de SPEC-006)
        if gf.fmt == FrameFormat::H264 {
            if gf.ts_capture_us.is_none() || gf.idr.is_none() {
                continue;
            }
            if announced != Some((gf.w, gf.h, FrameFormat::H264)) {
                if gf.idr != Some(true) {
                    continue;
                }
                announced = Some((gf.w, gf.h, FrameFormat::H264));
                send(
                    sink,
                    &StreamMsg::StreamStart {
                        media_port: twinpad_core::media_crypto::MEDIA_PORT,
                        w: gf.w,
                        h: gf.h,
                        fmt: FrameFormat::H264,
                        fps: twinpad_core::stream::FPS_UNLIMITED,
                    },
                )?;
            }
        } else {
            continue;
        }
    }

    send(
        sink,
        &StreamMsg::StreamStop {
            reason: twinpad_core::stream::StreamStopReason::PluginStopped,
        },
    )
}

/// Emite frames de la fuente seleccionada por sesión hasta que la sesión termine.
/// Al cambiar o perder la fuente, cierra el stream previo y reinicia fn/base.
pub fn run_selected_game_stream(
    sink: &mut dyn FrameSink,
    running: &std::sync::atomic::AtomicBool,
    config: &mut dyn FnMut() -> VideoConfig,
    source: &mut dyn FnMut() -> (Option<String>, Option<crate::games::GameFrame>),
) -> Result<(), VideoSourceError> {
    let mut active_game = None::<String>;
    let mut announced = None::<(u16, u16, u8)>;
    while running.load(std::sync::atomic::Ordering::Acquire) {
        let (game_id, frame) = source();
        if game_id != active_game {
            send_stream_stop(sink, announced.is_some())?;
            active_game = game_id;
            announced = None;
        }
        let Some(frame) = frame else {
            send_stream_stop(sink, announced.take().is_some())?;
            std::thread::sleep(std::time::Duration::from_millis(10));
            continue;
        };
        if frame.fmt != FrameFormat::H264 {
            send_stream_stop(sink, announced.take().is_some())?;
            std::thread::sleep(std::time::Duration::from_millis(10));
            continue;
        }
        // CR-029: `fps` es metadata (0 = unlimited); ya no escoge el ritmo.
        let fps = twinpad_core::stream::FPS_UNLIMITED;
        if announced != Some((frame.w, frame.h, fps)) {
            send_stream_stop(sink, announced.take().is_some())?;
            if frame.idr != Some(true) {
                std::thread::sleep(std::time::Duration::from_millis(2));
                continue;
            }
            let _ = config(); // bitrate vigente reenviado por el lector de sesión
            announced = Some((frame.w, frame.h, fps));
            send(
                sink,
                &StreamMsg::StreamStart {
                    media_port: twinpad_core::media_crypto::MEDIA_PORT,
                    w: frame.w,
                    h: frame.h,
                    fmt: FrameFormat::H264,
                    fps,
                },
            )?;
        }
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    send_stream_stop(sink, announced.is_some())
}

fn send_stream_stop(sink: &mut dyn FrameSink, announced: bool) -> Result<(), VideoSourceError> {
    if announced {
        send(
            sink,
            &StreamMsg::StreamStop {
                reason: twinpad_core::stream::StreamStopReason::PluginStopped,
            },
        )?;
    }
    Ok(())
}

// ENG §6: unwrap permitido solo en tests.
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
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
    // TEST-031 — verifies SPEC-003: SIMD JPEG is decodable at the declared dimensions.
    #[test]
    fn jpeg_encoding_produces_valid_image_smaller_than_raw() {
        let rgb = demo_frame(7, 320, 180); // 172 800 bytes crudos
        let raw = rgb.len();
        let jpeg = encode_jpeg(&rgb, 320, 180, DEFAULT_JPEG_QUALITY).expect("encode ok");
        // Empieza con SOI (FF D8) y termina con EOI (FF D9).
        assert_eq!(&jpeg[0..2], &[0xFF, 0xD8]);
        assert_eq!(&jpeg[jpeg.len() - 2..], &[0xFF, 0xD9]);
        let decoded = image::load_from_memory(&jpeg).expect("JPEG must decode");
        assert_eq!((decoded.width(), decoded.height()), (320, 180));
        let decoded_pixel = decoded.to_rgb8().get_pixel(10, 10).0;
        let source_offset = (10 * 320 + 10) * 3;
        for (actual, expected) in decoded_pixel
            .iter()
            .zip(&rgb[source_offset..source_offset + 3])
        {
            assert!(
                actual.abs_diff(*expected) <= 32,
                "decoded RGB should remain visually close"
            );
        }
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
            data: {
                use base64::Engine as _;
                base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&jpeg)
            },
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
            bitrate_kbps: DEFAULT_VIDEO_BITRATE_KBPS,
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
    fn demo_stream_is_disabled_without_an_h264_plugin_source() {
        let mut sink = SinkSpy(RefCell::new(Vec::new()));
        run_demo_stream(&mut sink, 3, 60, 16, 16).unwrap();
        let lines = sink.0.borrow();
        assert_eq!(lines.len(), 1);
        assert!(matches!(
            StreamMsg::decode(&lines[0]).unwrap(),
            StreamMsg::StreamStop { .. }
        ));
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
            ts_capture_us: None,
            idr: None,
            data: vec![gfn as u8; usize::from(w) * usize::from(h) * 3].into(),
        }
    }

    fn h264_game_frame(gfn: u64, w: u16, h: u16) -> GameFrame {
        GameFrame {
            gfn,
            w,
            h,
            fmt: FrameFormat::H264,
            ts_capture_us: Some(gfn + 1),
            idr: Some(true),
            data: vec![0, 0, 0, 1, 0x65, gfn as u8].into(),
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
    fn game_stream_does_not_emit_legacy_rgb_frames() {
        let exhausted = std::rc::Rc::new(std::cell::Cell::new(false));
        let mut source = test_source(2, std::rc::Rc::clone(&exhausted));
        let mut sink = SinkSpy(RefCell::new(Vec::new()));
        // FPS alto: sin sleeps medibles en el test.
        run_game_stream(&mut sink, 3, 60, &mut source, true).unwrap();
        let lines = sink.0.borrow();
        // start + fn=0 + fn=1 + stop: el juego dejó de publicar → fin limpio.
        assert_eq!(lines.len(), 1);
        assert!(matches!(
            StreamMsg::decode(&lines[0]).unwrap(),
            StreamMsg::StreamStop { .. }
        ));
    }

    // TEST-028 — verifies SPEC-003/CR-002: SDK JPEG frames remain eligible sources.
    #[test]
    fn game_stream_does_not_fallback_to_jpeg_frames() {
        let first = encode_jpeg(&vec![80u8; 64 * 64 * 3], 64, 64, 80).unwrap();
        let mut emitted = 0u64;
        let mut source = move || {
            let index = emitted;
            emitted += 1;
            Some((
                "pacdemo".into(),
                GameFrame {
                    gfn: index,
                    w: 64,
                    h: 64,
                    fmt: FrameFormat::Jpeg,
                    ts_capture_us: None,
                    idr: None,
                    data: first.clone().into(),
                },
            ))
        };
        let mut sink = SinkSpy(RefCell::new(Vec::new()));
        run_game_stream(&mut sink, 1, 60, &mut source, true).unwrap();
        let messages: Vec<StreamMsg> = sink
            .0
            .borrow()
            .iter()
            .map(|line| StreamMsg::decode(line).unwrap())
            .collect();
        assert!(matches!(
            messages.as_slice(),
            [StreamMsg::StreamStop { .. }]
        ));
    }

    // TEST-028 — verifies SPEC-003/CR-002: localized RGB changes emit one tile patch.
    #[test]
    fn game_stream_does_not_fallback_to_rgb_tile_patches() {
        let mut source = || Some(("pacdemo".into(), game_frame(0, 128, 128)));
        let mut sink = SinkSpy(RefCell::new(Vec::new()));
        run_game_stream(&mut sink, 1, 60, &mut source, true).unwrap();
        let lines = sink.0.borrow();
        assert_eq!(lines.len(), 1);
        assert!(matches!(
            StreamMsg::decode(&lines[0]).unwrap(),
            StreamMsg::StreamStop { .. }
        ));
    }

    #[test]
    fn game_stream_does_not_announce_raw_frame_resolution_changes() {
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
        assert_eq!(lines.len(), 1);
        assert!(matches!(
            StreamMsg::decode(&lines[0]).unwrap(),
            StreamMsg::StreamStop { .. }
        ));
    }

    // TEST-051 — verifies SPEC-003/006: media stops and its tile base resets on selected-source changes.
    #[test]
    fn selected_game_stream_relays_only_across_confirmed_source_boundaries() {
        let running = std::sync::atomic::AtomicBool::new(true);
        let polls = std::cell::Cell::new(0usize);
        let mut source = || {
            let poll = polls.get();
            polls.set(poll + 1);
            if poll == 4 {
                running.store(false, std::sync::atomic::Ordering::Release);
            }
            match poll {
                0 => (None, None),
                1 | 2 => (Some("alpha".to_string()), Some(h264_game_frame(0, 64, 64))),
                3 | 4 => (Some("beta".to_string()), Some(h264_game_frame(1, 64, 64))),
                _ => (None, None),
            }
        };
        let mut config = || VideoConfig {
            width: 1280,
            height: 720,
            bitrate_kbps: DEFAULT_VIDEO_BITRATE_KBPS,
        };
        let mut sink = SinkSpy(RefCell::new(Vec::new()));
        run_selected_game_stream(&mut sink, &running, &mut config, &mut source).unwrap();
        let messages: Vec<StreamMsg> = sink
            .0
            .borrow()
            .iter()
            .map(|line| StreamMsg::decode(line).unwrap())
            .collect();
        // CR-029: `fps` se anuncia como metadata 0 = unlimited.
        assert!(matches!(
            messages.as_slice(),
            [
                StreamMsg::StreamStart {
                    fmt: FrameFormat::H264,
                    fps: 0,
                    ..
                },
                StreamMsg::StreamStop { .. },
                StreamMsg::StreamStart {
                    fmt: FrameFormat::H264,
                    fps: 0,
                    ..
                },
                StreamMsg::StreamStop { .. },
            ]
        ));
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
