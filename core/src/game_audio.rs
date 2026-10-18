//! IPC PCM de juegos — SPEC-006 §4 (CR-003), separado del stream de sesión.

use crate::audio::{MAX_CHANNELS, MAX_CHUNK_BYTES, MAX_RATE, MIN_RATE, SAMPLE_FMT_S16LE};
use crate::error::{PairError, PairResult};
use serde::{Deserialize, Serialize};

/// Mensajes del canal IPC game_audio (127.0.0.1:8422); NDJSON estricto.
#[derive(Clone, PartialEq, Eq, Debug, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum GameAudioMsg {
    #[serde(rename = "game_audio_start")]
    Start {
        game_id: String,
        rate: u32,
        channels: u8,
        sample_fmt: String,
    },
    #[serde(rename = "game_audio_chunk")]
    Chunk {
        game_id: String,
        seq: u64,
        ts: u64,
        data: String,
    },
    #[serde(rename = "game_audio_stop")]
    Stop { game_id: String },
}

impl GameAudioMsg {
    /// Codifica a una línea NDJSON sin newline.
    pub fn encode(&self) -> PairResult<String> {
        self.validate()?;
        serde_json::to_string(self).map_err(|_| PairError::MalformedMessage)
    }

    /// Decodifica y valida estrictamente una línea NDJSON.
    pub fn decode(line: &str) -> PairResult<Self> {
        let message: Self = serde_json::from_str(line).map_err(|_| PairError::MalformedMessage)?;
        message.validate()?;
        Ok(message)
    }

    fn validate(&self) -> PairResult<()> {
        let game_id = match self {
            Self::Start {
                game_id,
                rate,
                channels,
                sample_fmt,
            } => {
                if !(MIN_RATE..=MAX_RATE).contains(rate)
                    || *channels == 0
                    || *channels > MAX_CHANNELS
                    || sample_fmt != SAMPLE_FMT_S16LE
                {
                    return Err(PairError::MalformedMessage);
                }
                game_id
            }
            Self::Chunk { game_id, data, .. } => {
                use base64::Engine as _;
                let pcm = base64::engine::general_purpose::URL_SAFE_NO_PAD
                    .decode(data)
                    .map_err(|_| PairError::MalformedMessage)?;
                if pcm.is_empty() || pcm.len() > MAX_CHUNK_BYTES || pcm.len() % 2 != 0 {
                    return Err(PairError::MalformedMessage);
                }
                game_id
            }
            Self::Stop { game_id } => game_id,
        };
        if game_id.is_empty()
            || game_id.len() > crate::game::GAME_ID_MAX
            || !game_id.chars().all(|c| {
                c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, '_' | '.' | '-')
            })
        {
            return Err(PairError::MalformedMessage);
        }
        Ok(())
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    // TEST-036 — validates CR-003 game_audio IPC schema and PCM constraints.
    #[test]
    fn game_audio_messages_roundtrip_and_validate() {
        let start = GameAudioMsg::Start {
            game_id: "cemu".into(),
            rate: 48_000,
            channels: 2,
            sample_fmt: "s16le".into(),
        };
        assert_eq!(
            GameAudioMsg::decode(&start.encode().unwrap()).unwrap(),
            start
        );
        use base64::Engine as _;
        let chunk = GameAudioMsg::Chunk {
            game_id: "cemu".into(),
            seq: 2,
            ts: 31,
            data: base64::engine::general_purpose::URL_SAFE_NO_PAD.encode([0u8; 16]),
        };
        assert_eq!(
            GameAudioMsg::decode(&chunk.encode().unwrap()).unwrap(),
            chunk
        );
        let stop = GameAudioMsg::Stop {
            game_id: "cemu".into(),
        };
        assert_eq!(GameAudioMsg::decode(&stop.encode().unwrap()).unwrap(), stop);
    }

    #[test]
    fn rejects_bad_rate_format_channel_chunk_and_unknown_fields() {
        for line in [
            r#"{"type":"game_audio_start","game_id":"cemu","rate":7999,"channels":2,"sample_fmt":"s16le"}"#,
            r#"{"type":"game_audio_start","game_id":"cemu","rate":48000,"channels":3,"sample_fmt":"s16le"}"#,
            r#"{"type":"game_audio_start","game_id":"cemu","rate":48000,"channels":2,"sample_fmt":"f32le"}"#,
            r#"{"type":"game_audio_chunk","game_id":"cemu","seq":0,"ts":0,"data":"%%%"}"#,
            r#"{"type":"game_audio_stop","game_id":"cemu","extra":1}"#,
        ] {
            assert_eq!(GameAudioMsg::decode(line), Err(PairError::MalformedMessage));
        }
    }
}
