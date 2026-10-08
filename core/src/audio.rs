//! Mensajes del Stream de audio — SPEC-005 §4/§12.
//!
//! Viajan por la misma conexión NDJSON de la sesión (SPEC-001 §4.2) una vez
//! establecida, junto al stream de video de SPEC-003. El decode es estricto:
//! campo faltante, `type` desconocido o parámetro fuera de rango → E4.
//! El orden lo determina exclusivamente `seq` (R2); `ts` es diagnóstico.
//! v1 sin compresión: PCM `s16le` crudo (OQ §17 resuelta por el owner).

use crate::error::{PairError, PairResult};
use serde::Deserialize;
use serde::Serialize;

/// Formato de muestra v1 (SPEC-005 §4): PCM little-endian con signo, 16 bits.
pub const SAMPLE_FMT_S16LE: &str = "s16le";

/// Límites de los parámetros del audio (SPEC-005 §4).
pub const MIN_RATE: u32 = 8_000;
pub const MAX_RATE: u32 = 192_000;
pub const MAX_CHANNELS: u8 = 2;
/// Tamaño máximo decodificado de un `audio_chunk` (16 KiB, §4).
pub const MAX_CHUNK_BYTES: usize = 16 * 1024;

/// Causas de `audio_stop` (SPEC-005 §4).
#[derive(Clone, Copy, PartialEq, Eq, Debug, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AudioStopReason {
    /// El Plugin dejó de emitir audio.
    #[serde(rename = "plugin_stopped")]
    PluginStopped,
    /// El Server se apaga.
    #[serde(rename = "server_shutdown")]
    ServerShutdown,
}

/// Mensajes del stream de audio (SPEC-005 §4). `type` discriminante wire.
#[derive(Clone, PartialEq, Eq, Debug, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum AudioMsg {
    /// server → app: anuncia el stream y configura la salida (§7 paso 1).
    #[serde(rename = "audio_start")]
    AudioStart {
        /// Muestras por segundo (8 000–192 000).
        rate: u32,
        /// 1 (mono) o 2 (estéreo).
        channels: u8,
        /// Formato de muestra; v1: solo `s16le`.
        sample_fmt: String,
    },
    /// server → app: un fragmento de PCM (§7 paso 2).
    #[serde(rename = "audio_chunk")]
    AudioChunk {
        /// Secuencia creciente por stream; ordena la reproducción (R2).
        seq: u64,
        /// Milisegundos del reloj del server en el envío (diagnóstico).
        ts: u64,
        /// PCM s16le en base64url; ≤ 16 KiB decodificados.
        data: String,
    },
    /// server → app: fin del stream de audio (§7 paso 4).
    #[serde(rename = "audio_stop")]
    AudioStop {
        /// Causa del fin.
        reason: AudioStopReason,
    },
}

impl AudioMsg {
    /// Serializa a su línea NDJSON canónica (sin `\n` final).
    pub fn encode(&self) -> PairResult<String> {
        serde_json::to_string(self).map_err(|_| PairError::MalformedMessage)
    }

    /// Decodifica y valida una línea NDJSON; desviación de §4 → E4.
    pub fn decode(line: &str) -> PairResult<Self> {
        let msg: Self = serde_json::from_str(line).map_err(|_| PairError::MalformedMessage)?;
        msg.validate()?;
        Ok(msg)
    }

    /// Valida rangos y coherencia del mensaje (§4/§9).
    fn validate(&self) -> PairResult<()> {
        match self {
            AudioMsg::AudioStart {
                rate,
                channels,
                sample_fmt,
            } => {
                if !(MIN_RATE..=MAX_RATE).contains(rate) {
                    return Err(PairError::MalformedMessage);
                }
                if *channels == 0 || *channels > MAX_CHANNELS {
                    return Err(PairError::MalformedMessage);
                }
                if sample_fmt != SAMPLE_FMT_S16LE {
                    return Err(PairError::MalformedMessage);
                }
                Ok(())
            }
            AudioMsg::AudioChunk { data, .. } => {
                use base64::Engine as _;
                let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
                    .decode(data)
                    .map_err(|_| PairError::MalformedMessage)?;
                if bytes.len() > MAX_CHUNK_BYTES {
                    return Err(PairError::MalformedMessage);
                }
                // s16le: el PCM decodificado debe ser un número entero de
                // muestras (2 bytes por muestra, §4).
                if bytes.len() % 2 != 0 {
                    return Err(PairError::MalformedMessage);
                }
                Ok(())
            }
            AudioMsg::AudioStop { .. } => Ok(()),
        }
    }
}

// ENG §6: unwrap/panic permitidos solo en tests (ámbito: este módulo).
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::panic)]
mod tests {
    use super::*;

    // TEST-020 — verifies SPEC-005 (§4: codec estricto de los mensajes).
    fn pcm_data(n_samples: usize) -> String {
        use base64::Engine as _;
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(vec![0u8; n_samples * 2])
    }

    #[test]
    fn audio_messages_roundtrip() {
        let msgs = [
            AudioMsg::AudioStart {
                rate: 44_100,
                channels: 2,
                sample_fmt: "s16le".into(),
            },
            AudioMsg::AudioChunk {
                seq: 0,
                ts: 1_000,
                data: pcm_data(64),
            },
            AudioMsg::AudioStop {
                reason: AudioStopReason::PluginStopped,
            },
        ];
        for msg in msgs {
            let line = msg.encode().unwrap();
            assert!(!line.contains('\n'));
            assert_eq!(AudioMsg::decode(&line).unwrap(), msg);
        }
    }

    #[test]
    fn wire_type_names_match_spec() {
        let line = AudioMsg::AudioStart {
            rate: 44_100,
            channels: 1,
            sample_fmt: "s16le".into(),
        }
        .encode()
        .unwrap();
        assert!(line.starts_with("{\"type\":\"audio_start\""));
        assert!(line.contains("\"sample_fmt\":\"s16le\""));
    }

    #[test]
    fn unknown_type_or_missing_field_is_e4() {
        assert_eq!(
            AudioMsg::decode("{\"type\":\"audio_pause\"}"),
            Err(PairError::MalformedMessage)
        );
        assert_eq!(
            AudioMsg::decode("{\"type\":\"audio_start\",\"rate\":44100}"),
            Err(PairError::MalformedMessage)
        );
        assert_eq!(
            AudioMsg::decode(
                "{\"type\":\"audio_start\",\"rate\":44100,\"channels\":2,\"sample_fmt\":\"s16le\",\"extra\":1}"
            ),
            Err(PairError::MalformedMessage)
        );
    }

    #[test]
    fn rate_out_of_range_is_e4() {
        for bad in [MIN_RATE - 1, MAX_RATE + 1] {
            let msg = AudioMsg::AudioStart {
                rate: bad,
                channels: 2,
                sample_fmt: "s16le".into(),
            };
            assert_eq!(msg.validate(), Err(PairError::MalformedMessage));
        }
    }

    #[test]
    fn bad_channels_or_fmt_is_e4() {
        let zero = AudioMsg::AudioStart {
            rate: 44_100,
            channels: 0,
            sample_fmt: "s16le".into(),
        };
        assert_eq!(zero.validate(), Err(PairError::MalformedMessage));
        let three = AudioMsg::AudioStart {
            rate: 44_100,
            channels: 3,
            sample_fmt: "s16le".into(),
        };
        assert_eq!(three.validate(), Err(PairError::MalformedMessage));
        let fmt = AudioMsg::AudioStart {
            rate: 44_100,
            channels: 2,
            sample_fmt: "f32le".into(),
        };
        assert_eq!(fmt.validate(), Err(PairError::MalformedMessage));
    }

    // TEST-020 — verifies SPEC-005 (§5/§9: fragmento inconsistente → descarte).
    #[test]
    fn chunk_over_16kib_is_e4() {
        let big = AudioMsg::AudioChunk {
            seq: 0,
            ts: 0,
            data: pcm_data(MAX_CHUNK_BYTES / 2 + 1), // +1 muestra → > 16 KiB
        };
        assert_eq!(big.validate(), Err(PairError::MalformedMessage));
    }

    #[test]
    fn chunk_with_odd_byte_count_is_e4() {
        use base64::Engine as _;
        let odd = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(vec![0u8; 3]);
        let msg = AudioMsg::AudioChunk {
            seq: 0,
            ts: 0,
            data: odd,
        };
        assert_eq!(msg.validate(), Err(PairError::MalformedMessage));
    }

    #[test]
    fn non_base64_chunk_is_e4() {
        let msg = AudioMsg::AudioChunk {
            seq: 0,
            ts: 0,
            data: "¡no es base64!".into(),
        };
        assert_eq!(msg.validate(), Err(PairError::MalformedMessage));
    }

    #[test]
    fn chunk_at_16kib_limit_is_valid() {
        let exact = AudioMsg::AudioChunk {
            seq: 0,
            ts: 0,
            data: pcm_data(MAX_CHUNK_BYTES / 2),
        };
        assert_eq!(exact.validate(), Ok(()));
    }
}
