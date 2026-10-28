//! Mensajes del Stream de video de la Segunda pantalla — SPEC-003 §4/§12.
//!
//! Viajan por la misma conexión NDJSON de la sesión (SPEC-001 §4.2) una vez
//! establecida. El decode es estricto como el del Handshake: campo faltante,
//! `type` desconocido o parámetro fuera de rango → E4. El orden lo determina
//! exclusivamente `fn` (R2); `ts` es diagnóstico.

use crate::error::{PairError, PairResult};
use base64::Engine as _;
use serde::Deserialize;
use serde::Serialize;

/// Formatos de Framebuffer de la v1 (SPEC-003 §4 `fmt`). `rgb8` fue el
/// formato de la primera implementación (OQ-1); v1.1 (CR-001) añade `jpeg`
/// para 720p/1080p a 30/60 fps sobre WiFi LAN.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Serialize, Deserialize)]
pub enum FrameFormat {
    /// 8 bits por píxel, 1 byte/px.
    #[serde(rename = "gray8")]
    Gray8,
    /// RGB888 empaquetado, 3 bytes/px, fila mayor.
    #[serde(rename = "rgb8")]
    Rgb8,
    /// Archivo JPEG completo (JFIF con cabecera); tamaño libre (CR-001).
    #[serde(rename = "jpeg")]
    Jpeg,
    /// Modalidad del stream con keyframes JPEG y parches JPEG por tiles (CR-002).
    #[serde(rename = "jpeg_tiles")]
    JpegTiles,
    /// Access units H.264/AVC Annex B (CR-014).
    #[serde(rename = "h264")]
    H264,
}

impl FrameFormat {
    /// Bytes por píxel del formato (SPEC-003 §12); `None` para los
    /// comprimidos (el tamaño de `data` es el del archivo, no `w×h×bpp`).
    pub fn bytes_per_pixel(self) -> Option<usize> {
        match self {
            FrameFormat::Gray8 => Some(1),
            FrameFormat::Rgb8 => Some(3),
            FrameFormat::Jpeg | FrameFormat::JpegTiles | FrameFormat::H264 => None,
        }
    }
}

/// Mensajes del stream (SPEC-003 §4). `type` es el discriminante wire.
#[derive(Clone, PartialEq, Eq, Debug, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum StreamMsg {
    /// server → app: anuncia el stream y sus parámetros vigentes (§7 paso 1,
    /// §8.1 re-negociación con `fn` continuo).
    #[serde(rename = "stream_start")]
    StreamStart {
        /// Puerto TCP del canal media (8425 en SPEC-003 v1.4.0).
        media_port: u16,
        /// Ancho del Framebuffer en píxeles (16–3840).
        w: u16,
        /// Alto del Framebuffer en píxeles (16–2160).
        h: u16,
        /// Formato del Stream; v1.4.0 exige H.264.
        fmt: FrameFormat,
        /// Metadata informativa del framerate (0–255; `0` = unlimited, CR-029);
        /// ya no fija el ritmo de emisión.
        fps: u8,
    },
    /// server → app: un Framebuffer completo (§7 paso 2).
    #[serde(rename = "video_frame")]
    VideoFrame {
        /// Número de frame: monotónico creciente, ordena la presentación (R2).
        #[serde(rename = "fn")]
        fn_: u64,
        /// Milisegundos del reloj del server en el envío (diagnóstico).
        ts: u64,
        /// Ancho en píxeles (debe coincidir con el `stream_start` vigente).
        w: u16,
        /// Alto en píxeles (debe coincidir con el `stream_start` vigente).
        h: u16,
        /// Formato (debe coincidir con el `stream_start` vigente).
        fmt: FrameFormat,
        /// Framebuffer crudo en base64url: exactamente `w×h×bpp` bytes.
        data: String,
    },
    /// server → app: un lote atómico de tiles JPEG sobre la última imagen aplicada (CR-002).
    #[serde(rename = "video_patch")]
    VideoPatch {
        /// Número de imagen transmitida; mayor que `base_fn`.
        #[serde(rename = "fn")]
        fn_: u64,
        /// Última imagen base que debe tener aplicada el receptor.
        base_fn: u64,
        /// Milisegundos del reloj del server al enviar.
        ts: u64,
        /// Dimensiones de la imagen completa.
        w: u16,
        h: u16,
        /// Tamaño fijo del tile.
        tile_size: u16,
        /// Tiles completos; la última fila/columna puede ser menor.
        patches: Vec<VideoTile>,
    },
    /// server → app: notifica frames perdidos por transporte (§10, R3:
    /// no se retransmite, el render avanza al más reciente).
    #[serde(rename = "stream_gap")]
    StreamGap {
        /// Primer `fn` perdido (inclusive).
        from_fn: u64,
        /// Último `fn` perdido (inclusive); `from_fn ≤ to_fn`.
        to_fn: u64,
    },
    /// server → app: fin del stream (§7 paso 5).
    #[serde(rename = "stream_stop")]
    StreamStop {
        /// Causa del fin.
        reason: StreamStopReason,
    },
}

/// Un tile JPEG que reemplaza el rectángulo indicado dentro del Framebuffer (CR-002).
#[derive(Clone, PartialEq, Eq, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct VideoTile {
    pub x: u16,
    pub y: u16,
    pub w: u16,
    pub h: u16,
    pub fmt: FrameFormat,
    pub data: String,
}

/// Tamaño normativo de tile (CR-002).
pub const TILE_SIZE: u16 = 64;

/// Causas de `stream_stop` (SPEC-003 §4).
#[derive(Clone, Copy, PartialEq, Eq, Debug, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum StreamStopReason {
    /// El Plugin dejó de emitir contenido (REQ-004 AC-3).
    #[serde(rename = "plugin_stopped")]
    PluginStopped,
    /// El Server se apaga.
    #[serde(rename = "server_shutdown")]
    ServerShutdown,
}

/// Límites de los parámetros del stream (SPEC-003 §4).
pub const MIN_DIM: u16 = 16;
pub const MAX_WIDTH: u16 = 3840;
pub const MAX_HEIGHT: u16 = 2160;
/// Metadata informativa de `stream_start.fps` (CR-029): `0` = sin límite
/// declarado (`unlimited`). Ya no es parámetro de pacing: la fuente publica
/// tan rápido como permitan captura+encoder.
pub const FPS_UNLIMITED: u8 = 0;

impl StreamMsg {
    /// Serializa a su línea NDJSON canónica (sin `\n` final).
    pub fn encode(&self) -> PairResult<String> {
        serde_json::to_string(self).map_err(|_| PairError::MalformedMessage)
    }

    /// Decodifica y valida una línea NDJSON del stream; cualquier desviación
    /// de §4 → E4 (SPEC-003 §9).
    pub fn decode(line: &str) -> PairResult<Self> {
        let msg: Self = serde_json::from_str(line).map_err(|_| PairError::MalformedMessage)?;
        msg.validate()?;
        Ok(msg)
    }

    /// Valida rangos y coherencia del mensaje (§4/§9/§10).
    fn validate(&self) -> PairResult<()> {
        match self {
            StreamMsg::StreamStart {
                media_port,
                fmt,
                w,
                h,
                fps: _,
            } => {
                validate_dims(*w, *h)?;
                if *fmt != FrameFormat::H264 || *media_port != crate::media_crypto::MEDIA_PORT {
                    return Err(PairError::MalformedMessage);
                }
                // CR-029: `fps` es metadata informativa (0–255, dominio del
                // u8); sin validación de rango 1–60 ni pacing.
                Ok(())
            }
            StreamMsg::VideoFrame {
                fn_,
                w,
                h,
                fmt,
                data,
                ..
            } => {
                validate_dims(*w, *h)?;
                if *fmt == FrameFormat::JpegTiles {
                    return Err(PairError::MalformedMessage);
                }
                validate_frame_size(*w, *h, *fmt, data)
                    // `fn_` monotonicity requiere el estado del stream.
                    .map(|_| {
                        let _ = fn_;
                    })
            }
            StreamMsg::VideoPatch {
                fn_,
                base_fn,
                w,
                h,
                tile_size,
                patches,
                ..
            } => {
                validate_dims(*w, *h)?;
                if *fn_ <= *base_fn || *tile_size != TILE_SIZE || patches.is_empty() {
                    return Err(PairError::MalformedMessage);
                }
                let columns = w.div_ceil(TILE_SIZE);
                let rows = h.div_ceil(TILE_SIZE);
                let mut occupied = std::collections::HashSet::with_capacity(patches.len());
                for tile in patches {
                    if tile.fmt != FrameFormat::Jpeg
                        || tile.x % TILE_SIZE != 0
                        || tile.y % TILE_SIZE != 0
                        || tile.w != TILE_SIZE.min(w.saturating_sub(tile.x))
                        || tile.h != TILE_SIZE.min(h.saturating_sub(tile.y))
                        || tile.x >= *w
                        || tile.y >= *h
                    {
                        return Err(PairError::MalformedMessage);
                    }
                    let tile_index = (tile.y / TILE_SIZE) * columns + tile.x / TILE_SIZE;
                    if tile_index >= columns * rows || !occupied.insert(tile_index) {
                        return Err(PairError::MalformedMessage);
                    }
                    let jpeg = base64::engine::general_purpose::URL_SAFE_NO_PAD
                        .decode(&tile.data)
                        .map_err(|_| PairError::MalformedMessage)?;
                    if jpeg.is_empty() {
                        return Err(PairError::MalformedMessage);
                    }
                }
                Ok(())
            }
            StreamMsg::StreamGap { from_fn, to_fn } => {
                if from_fn > to_fn {
                    return Err(PairError::MalformedMessage); // §4: from ≤ to
                }
                Ok(())
            }
            StreamMsg::StreamStop { .. } => Ok(()),
        }
    }
}

pub(crate) fn validate_dims(w: u16, h: u16) -> PairResult<()> {
    if w < MIN_DIM || h < MIN_DIM || w > MAX_WIDTH || h > MAX_HEIGHT {
        return Err(PairError::MalformedMessage);
    }
    Ok(())
}

/// El `data` decodifica a `w×h×bpp` bytes para los formatos crudos; para
/// los comprimidos (`jpeg`, CR-001) basta con que decodifique (el tamaño
/// es el del archivo, §4). §5: el frame inconsistente se descarta en el
/// receptor; aquí, en la frontera, es E4.
fn validate_frame_size(w: u16, h: u16, fmt: FrameFormat, data: &str) -> PairResult<()> {
    use base64::Engine as _;
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(data)
        .map_err(|_| PairError::MalformedMessage)?;
    if matches!(fmt, FrameFormat::JpegTiles | FrameFormat::H264) {
        return Err(PairError::MalformedMessage);
    }
    match fmt.bytes_per_pixel() {
        Some(bpp) => {
            let expected = usize::from(w) * usize::from(h) * bpp;
            if bytes.len() != expected {
                return Err(PairError::MalformedMessage);
            }
            Ok(())
        }
        None => Ok(()),
    }
}

/// Valida parámetros de Framebuffer según SPEC-003 §4 (dimensiones, tamaño
/// de `data` en base64url); compartido por los sobres de otros canales (p.
/// ej. `game_frame` de `core::game`, REQ-006 AC-2). Es `pub(crate)`: el
/// contrato canónico sigue siendo el de este módulo (ENG: SSOT).
pub(crate) fn validate_frame_params(
    w: u16,
    h: u16,
    fmt: FrameFormat,
    data: &str,
) -> PairResult<()> {
    validate_dims(w, h)?;
    validate_frame_size(w, h, fmt, data)
}

// ENG §6: unwrap/panic permitidos solo en tests (ámbito: este módulo).
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::panic)]
mod tests {
    use super::*;

    // TEST-015 — verifies SPEC-003 (§7: flujo principal del stream).
    /// rgb8 de 16×16 = 768 bytes → base64url sin relleno.
    fn rgb_data(w: u16, h: u16) -> String {
        use base64::Engine as _;
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(vec![
            7u8;
            usize::from(w)
                * usize::from(h)
                * 3
        ])
    }

    #[test]
    fn stream_messages_roundtrip() {
        let msgs = [
            StreamMsg::StreamStart {
                media_port: crate::media_crypto::MEDIA_PORT,
                w: 1280,
                h: 720,
                fmt: FrameFormat::H264,
                fps: 30,
            },
            StreamMsg::VideoFrame {
                fn_: 0,
                ts: 1_000,
                w: 16,
                h: 16,
                fmt: FrameFormat::Rgb8,
                data: rgb_data(16, 16),
            },
            StreamMsg::StreamGap {
                from_fn: 5,
                to_fn: 9,
            },
            StreamMsg::StreamStop {
                reason: StreamStopReason::PluginStopped,
            },
        ];
        for msg in msgs {
            let line = msg.encode().unwrap();
            assert!(!line.contains('\n'));
            assert_eq!(StreamMsg::decode(&line).unwrap(), msg);
        }
    }

    #[test]
    fn wire_type_names_match_spec() {
        let line = StreamMsg::StreamStart {
            media_port: crate::media_crypto::MEDIA_PORT,
            w: 640,
            h: 480,
            fmt: FrameFormat::H264,
            fps: 15,
        }
        .encode()
        .unwrap();
        assert!(line.starts_with("{\"type\":\"stream_start\""));
        assert!(line.contains("\"fmt\":\"h264\""));
    }

    #[test]
    fn unknown_type_is_e4() {
        assert_eq!(
            StreamMsg::decode("{\"type\":\"audio_frame\",\"x\":1}"),
            Err(PairError::MalformedMessage)
        );
    }

    #[test]
    fn missing_field_is_e4() {
        assert_eq!(
            StreamMsg::decode("{\"type\":\"stream_start\",\"w\":640,\"h\":480,\"fps\":30}"),
            Err(PairError::MalformedMessage)
        );
    }

    #[test]
    fn extra_field_is_e4() {
        let line = format!(
            "{{\"type\":\"stream_gap\",\"from_fn\":1,\"to_fn\":2,\"extra\":0}}{}",
            ""
        );
        assert_eq!(StreamMsg::decode(&line), Err(PairError::MalformedMessage));
    }

    #[test]
    fn out_of_range_params_are_e4() {
        // Dimensiones por debajo del mínimo (16).
        let tiny = StreamMsg::StreamStart {
            media_port: crate::media_crypto::MEDIA_PORT,
            w: 15,
            h: 480,
            fmt: FrameFormat::Rgb8,
            fps: 0,
        };
        assert_eq!(tiny.validate(), Err(PairError::MalformedMessage));
    }

    // TEST-089 — verifies SPEC-003 §4.1/CR-029: `fps` es metadata del wire
    // (0 = unlimited, cualquier u8 válido) y ya no se rechaza por rango.
    #[test]
    fn stream_start_accepts_unlimited_and_metadata_fps() {
        for fps in [0_u8, 30, 60, u8::MAX] {
            let start = StreamMsg::StreamStart {
                media_port: crate::media_crypto::MEDIA_PORT,
                w: 640,
                h: 480,
                fmt: FrameFormat::H264,
                fps,
            };
            assert_eq!(
                start.validate(),
                Ok(()),
                "fps={fps} debe ser metadata válida"
            );
        }
    }

    // TEST-016 — verifies SPEC-003 (§5/§9: frame inconsistente → rechazo).
    #[test]
    fn frame_with_wrong_data_size_is_e4() {
        // Declara 16×16 rgb8 (768 B) pero viajan 767.
        use base64::Engine as _;
        let short = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(vec![1u8; 767]);
        let frame = StreamMsg::VideoFrame {
            fn_: 1,
            ts: 1,
            w: 16,
            h: 16,
            fmt: FrameFormat::Rgb8,
            data: short,
        };
        assert_eq!(frame.validate(), Err(PairError::MalformedMessage));
    }

    #[test]
    fn non_base64_data_is_e4() {
        let frame = StreamMsg::VideoFrame {
            fn_: 1,
            ts: 1,
            w: 16,
            h: 16,
            fmt: FrameFormat::Rgb8,
            data: "¡no es base64!".into(),
        };
        assert_eq!(frame.validate(), Err(PairError::MalformedMessage));
    }

    #[test]
    fn inverted_gap_is_e4() {
        let gap = StreamMsg::StreamGap {
            from_fn: 9,
            to_fn: 5,
        };
        assert_eq!(gap.validate(), Err(PairError::MalformedMessage));
    }

    // TEST-027 — verifies SPEC-003/CR-002: patch wire shape and validation.
    #[test]
    fn video_patch_roundtrips_with_fn_wire_name_and_valid_tiles() {
        let patch = StreamMsg::VideoPatch {
            fn_: 2,
            base_fn: 1,
            ts: 50,
            w: 80,
            h: 70,
            tile_size: TILE_SIZE,
            patches: vec![VideoTile {
                x: 64,
                y: 64,
                w: 16,
                h: 6,
                fmt: FrameFormat::Jpeg,
                data: base64_url(&[0xff, 0xd8, 0xff, 0xd9]),
            }],
        };
        let encoded = patch.encode().unwrap();
        assert!(encoded.contains("\"fn\":2"));
        assert!(!encoded.contains("fn_"));
        assert_eq!(StreamMsg::decode(&encoded).unwrap(), patch);
    }

    #[test]
    fn video_patch_rejects_wrong_base_order_empty_overlap_and_bad_edge_size() {
        let tile = VideoTile {
            x: 0,
            y: 0,
            w: 64,
            h: 64,
            fmt: FrameFormat::Jpeg,
            data: base64_url(&[0xff, 0xd8, 0xff, 0xd9]),
        };
        let valid = |fn_, base_fn, patches| StreamMsg::VideoPatch {
            fn_,
            base_fn,
            ts: 0,
            w: 128,
            h: 64,
            tile_size: TILE_SIZE,
            patches,
        };
        assert_eq!(
            valid(1, 1, vec![tile.clone()]).validate(),
            Err(PairError::MalformedMessage)
        );
        assert_eq!(
            valid(2, 1, vec![]).validate(),
            Err(PairError::MalformedMessage)
        );
        assert_eq!(
            valid(2, 1, vec![tile.clone(), tile.clone()]).validate(),
            Err(PairError::MalformedMessage)
        );
        let edge = VideoTile {
            x: 64,
            y: 0,
            w: 63,
            h: 64,
            ..tile
        };
        assert_eq!(
            valid(2, 1, vec![edge]).validate(),
            Err(PairError::MalformedMessage)
        );
    }

    fn base64_url(bytes: &[u8]) -> String {
        use base64::Engine as _;
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
    }

    #[test]
    fn frame_zero_is_valid() {
        let frame = StreamMsg::VideoFrame {
            fn_: 0,
            ts: 0,
            w: 16,
            h: 16,
            fmt: FrameFormat::Rgb8,
            data: rgb_data(16, 16),
        };
        assert_eq!(frame.validate(), Ok(()));
    }
}
