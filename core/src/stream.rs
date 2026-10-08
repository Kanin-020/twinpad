//! Mensajes del Stream de video de la Segunda pantalla — SPEC-003 §4/§12.
//!
//! Viajan por la misma conexión NDJSON de la sesión (SPEC-001 §4.2) una vez
//! establecida. El decode es estricto como el del Handshake: campo faltante,
//! `type` desconocido o parámetro fuera de rango → E4. El orden lo determina
//! exclusivamente `fn` (R2); `ts` es diagnóstico.

use crate::error::{PairError, PairResult};
use serde::Deserialize;
use serde::Serialize;

/// Formatos de Framebuffer de la v1 (SPEC-003 §4 `fmt`). `rgb8` es el único
/// requerido por la primera implementación (OQ-1 aprobada: sin compresión).
#[derive(Clone, Copy, PartialEq, Eq, Debug, Serialize, Deserialize)]
pub enum FrameFormat {
    /// 8 bits por píxel, 1 byte/px.
    #[serde(rename = "gray8")]
    Gray8,
    /// RGB888 empaquetado, 3 bytes/px, fila mayor.
    #[serde(rename = "rgb8")]
    Rgb8,
}

impl FrameFormat {
    /// Bytes por píxel del formato (SPEC-003 §12).
    pub fn bytes_per_pixel(self) -> usize {
        match self {
            FrameFormat::Gray8 => 1,
            FrameFormat::Rgb8 => 3,
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
        /// Ancho del Framebuffer en píxeles (16–3840).
        w: u16,
        /// Alto del Framebuffer en píxeles (16–2160).
        h: u16,
        /// Formato del Framebuffer.
        fmt: FrameFormat,
        /// Frames por segundo objetivo (1–60).
        fps: u8,
    },
    /// server → app: un Framebuffer completo (§7 paso 2).
    #[serde(rename = "video_frame")]
    VideoFrame {
        /// Número de frame: monotónico creciente, ordena la presentación (R2).
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
pub const MIN_FPS: u8 = 1;
pub const MAX_FPS: u8 = 60;

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
            StreamMsg::StreamStart { w, h, fps, .. } => {
                validate_dims(*w, *h)?;
                if !(MIN_FPS..=MAX_FPS).contains(fps) {
                    return Err(PairError::MalformedMessage);
                }
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
                validate_frame_size(*w, *h, *fmt, data)
                    // `fn_` no se valida aquí: `fn=0` es válido y "monotónico"
                    // solo lo puede juzgar quien lleva el estado del stream.
                    .map(|_| {
                        let _ = fn_;
                    })
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

fn validate_dims(w: u16, h: u16) -> PairResult<()> {
    if w < MIN_DIM || h < MIN_DIM || w > MAX_WIDTH || h > MAX_HEIGHT {
        return Err(PairError::MalformedMessage);
    }
    Ok(())
}

/// El `data` decodifica exactamente `w×h×bpp` bytes (§5: el frame
/// inconsistente se descarta en el receptor; aquí, en la frontera, es E4).
fn validate_frame_size(w: u16, h: u16, fmt: FrameFormat, data: &str) -> PairResult<()> {
    use base64::Engine as _;
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(data)
        .map_err(|_| PairError::MalformedMessage)?;
    let expected = usize::from(w) * usize::from(h) * fmt.bytes_per_pixel();
    if bytes.len() != expected {
        return Err(PairError::MalformedMessage);
    }
    Ok(())
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
                w: 1280,
                h: 720,
                fmt: FrameFormat::Rgb8,
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
            w: 640,
            h: 480,
            fmt: FrameFormat::Gray8,
            fps: 15,
        }
        .encode()
        .unwrap();
        assert!(line.starts_with("{\"type\":\"stream_start\""));
        assert!(line.contains("\"fmt\":\"gray8\""));
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
            w: 15,
            h: 480,
            fmt: FrameFormat::Rgb8,
            fps: 30,
        };
        assert_eq!(tiny.validate(), Err(PairError::MalformedMessage));
        // fps fuera de 1..=60.
        let fast = StreamMsg::StreamStart {
            w: 640,
            h: 480,
            fmt: FrameFormat::Rgb8,
            fps: 61,
        };
        assert_eq!(fast.validate(), Err(PairError::MalformedMessage));
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
