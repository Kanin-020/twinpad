//! Detección de juego con SDK Twinpad — SPEC-006 §4/§5 (ADR-008).
//!
//! Dos mensajes con codec estricto (desviación → E4):
//! - `game_announce`: juego → server por el canal IPC (TCP localhost, la
//!   conexión viva es el anuncio vivo — ADR-008).
//! - `game_frame`: juego → server por el canal IPC: un Framebuffer de su
//!   Segunda pantalla (REQ-006 AC-2) que alimenta el Stream de SPEC-003.
//! - `games_available`: server → app por la sesión de SPEC-001 (§5), cada
//!   vez que la lista de juegos vivos cambia.
//!
//! Los parámetros del Framebuffer (`w`/`h`/`fmt`, 16–3840×16–2160, rgb8/gray8)
//! y su validación son los de SPEC-003 §4 (`core::stream`), sin duplicación:
//! este módulo solo define el sobre IPC y su orden por `gfn` (cfr. `fn` de
//! SPEC-003 §4: orden exclusivo del frame dentro del stream de la app).

use crate::error::{PairError, PairResult};
use crate::stream::{FrameFormat, validate_dims};
use serde::Deserialize;
use serde::Serialize;

/// Capacidades declarables por un juego (SPEC-006 §4; subconjunto válido).
pub const CAPABILITIES: [&str; 3] = ["second_screen", "audio", "input_consumption"];

/// Límites de los campos del anuncio (SPEC-006 §4).
pub const GAME_ID_MAX: usize = 64;
pub const GAME_NAME_MAX: usize = 128;

/// Límites de los Framebuffers del stream (SPEC-003 §4), aplicados también
/// a `game_frame` (el server los re-empaqueta como `video_frame`).
pub const GAME_FRAME_MIN_DIM: u16 = crate::stream::MIN_DIM;
pub const GAME_FRAME_MAX_WIDTH: u16 = crate::stream::MAX_WIDTH;
pub const GAME_FRAME_MAX_HEIGHT: u16 = crate::stream::MAX_HEIGHT;

/// Un juego compatible vivo (SPEC-006 §4), tal como viaja en la lista.
#[derive(Clone, PartialEq, Eq, Debug, Serialize, Deserialize)]
pub struct GameInfo {
    /// 1–64 chars `[a-z0-9_.-]`, único entre juegos vivos.
    pub game_id: String,
    /// 1–128 chars legibles.
    pub game_name: String,
    /// Subconjunto de [`CAPABILITIES`]; vacío válido (§10).
    #[serde(default)]
    pub capabilities: Vec<String>,
}

/// Mensajes de detección de juego (SPEC-006 §4/§5). `type` discriminante wire.
#[derive(Clone, PartialEq, Debug, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum GameMsg {
    /// juego → server (canal IPC, ADR-008): anuncio al arrancar (§7 paso 1).
    #[serde(rename = "game_announce")]
    GameAnnounce {
        /// Identificación del juego.
        #[serde(flatten)]
        game: GameInfo,
    },
    /// server → app (sesión SPEC-001): lista de juegos compatibles vivos
    /// (§5/§7 paso 3); se reenvía completa ante cada cambio.
    #[serde(rename = "games_available")]
    GamesAvailable {
        /// Juegos vivos ordenados por `game_id`.
        games: Vec<GameInfo>,
    },
    /// Android → server (sesión autenticada): selección por sesión.
    #[serde(rename = "game_select")]
    GameSelect { game_id: String },
    /// server → Android (sesión autenticada): confirmación de selección.
    #[serde(rename = "game_selected")]
    GameSelected { game_id: String },
    /// juego → server (canal IPC, ADR-008): Framebuffer del juego para la
    /// Segunda pantalla (REQ-006 AC-2). El server lo re-empaqueta como
    /// `video_frame` de SPEC-003 hacia la app con `fn` propio.
    #[serde(rename = "game_frame")]
    GameFrame {
        /// Número de frame: monotónico creciente desde 0 por conexión (§4
        /// de SPEC-003: "empieza en 0 en cada stream; nunca se repite").
        gfn: u64,
        /// Ancho en píxeles (16–3840, SPEC-003 §4).
        w: u16,
        /// Alto en píxeles (16–2160, SPEC-003 §4).
        h: u16,
        /// Formato del Framebuffer (SPEC-003 §4: `rgb8` v1).
        fmt: FrameFormat,
        /// Timestamp monotónico desde el inicio del encoder (solo H.264, CR-015).
        #[serde(skip_serializing_if = "Option::is_none", default)]
        ts_capture_us: Option<u64>,
        /// Indica si la access unit H.264 es IDR (solo H.264, CR-015).
        #[serde(skip_serializing_if = "Option::is_none", default)]
        idr: Option<bool>,
        /// Framebuffer crudo o access unit en base64url.
        data: String,
    },
}

impl GameMsg {
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

    /// Valida rangos y formato de los campos (§4/§9).
    fn validate(&self) -> PairResult<()> {
        match self {
            GameMsg::GameAnnounce { game } => validate_game(game),
            GameMsg::GamesAvailable { games } => {
                for game in games {
                    validate_game(game)?;
                }
                Ok(())
            }
            GameMsg::GameSelect { game_id } | GameMsg::GameSelected { game_id } => {
                validate_game_id(game_id)
            }
            GameMsg::GameFrame {
                w,
                h,
                fmt,
                ts_capture_us,
                idr,
                data,
                ..
            } => {
                if *fmt == FrameFormat::H264 {
                    validate_dims(*w, *h)?;
                    if data.is_empty() || ts_capture_us.is_none() || idr.is_none() {
                        return Err(PairError::MalformedMessage);
                    }
                    Ok(())
                } else if ts_capture_us.is_some() || idr.is_some() {
                    Err(PairError::MalformedMessage)
                } else {
                    crate::stream::validate_frame_params(*w, *h, *fmt, data)
                }
            }
        }
    }
}

/// Valida un [`GameInfo`] según SPEC-006 §4.
fn validate_game_id(game_id: &str) -> PairResult<()> {
    let id_ok = !game_id.is_empty()
        && game_id.len() <= GAME_ID_MAX
        && game_id
            .chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, '_' | '.' | '-'));
    if !id_ok {
        return Err(PairError::MalformedMessage);
    }
    Ok(())
}

/// Valida un [`GameInfo`] según SPEC-006 §4.
fn validate_game(game: &GameInfo) -> PairResult<()> {
    validate_game_id(&game.game_id)?;
    if game.game_name.is_empty() || game.game_name.len() > GAME_NAME_MAX {
        return Err(PairError::MalformedMessage);
    }
    for cap in &game.capabilities {
        if !CAPABILITIES.contains(&cap.as_str()) {
            return Err(PairError::MalformedMessage);
        }
    }
    Ok(())
}

// ENG §6: unwrap/panic permitidos solo en tests (ámbito: este módulo).
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::panic)]
mod tests {
    use super::*;

    // TEST-021 — verifies SPEC-006 (§4: anuncio válido; §9: inválido → E4).
    fn announce(game_id: &str) -> GameMsg {
        GameMsg::GameAnnounce {
            game: GameInfo {
                game_id: game_id.into(),
                game_name: "Pac Demo".into(),
                capabilities: vec!["second_screen".into(), "audio".into()],
            },
        }
    }

    #[test]
    fn announce_roundtrip() {
        let line = announce("pacdemo").encode().unwrap();
        assert!(!line.contains('\n'));
        assert_eq!(GameMsg::decode(&line).unwrap(), announce("pacdemo"));
    }

    // TEST-051 — verifies SPEC-006: session source selection is strict NDJSON.
    #[test]
    fn game_selection_messages_roundtrip_and_validate_id() {
        let select = GameMsg::GameSelect {
            game_id: "cemu".into(),
        };
        assert_eq!(GameMsg::decode(&select.encode().unwrap()).unwrap(), select);
        let selected = GameMsg::GameSelected {
            game_id: "cemu".into(),
        };
        assert_eq!(
            GameMsg::decode(&selected.encode().unwrap()).unwrap(),
            selected
        );
        assert_eq!(
            GameMsg::decode(r#"{"type":"game_select","game_id":"Bad ID"}"#),
            Err(PairError::MalformedMessage)
        );
        assert_eq!(
            GameMsg::decode(r#"{"type":"game_selected","game_id":"cemu","extra":true}"#),
            Err(PairError::MalformedMessage)
        );
    }

    #[test]
    fn wire_type_names_match_spec() {
        assert!(
            announce("pacdemo")
                .encode()
                .unwrap()
                .starts_with("{\"type\":\"game_announce\"")
        );
        let list = GameMsg::GamesAvailable { games: vec![] }.encode().unwrap();
        assert!(list.starts_with("{\"type\":\"games_available\""));
    }

    #[test]
    fn games_available_roundtrip() {
        let msg = GameMsg::GamesAvailable {
            games: vec![GameInfo {
                game_id: "pacdemo".into(),
                game_name: "Pac Demo".into(),
                capabilities: vec!["second_screen".into()],
            }],
        };
        assert_eq!(GameMsg::decode(&msg.encode().unwrap()).unwrap(), msg);
    }

    #[test]
    fn empty_capabilities_are_valid() {
        let msg = GameMsg::GameAnnounce {
            game: GameInfo {
                game_id: "solo".into(),
                game_name: "Solo".into(),
                capabilities: vec![],
            },
        };
        assert_eq!(msg.validate(), Ok(()));
    }

    #[test]
    fn bad_game_id_is_e4() {
        for bad in ["", "UPPER", "con espacio", "ñandu", &"a".repeat(65)] {
            assert_eq!(
                announce(bad).validate(),
                Err(PairError::MalformedMessage),
                "game_id {bad:?} debió rechazarse"
            );
        }
    }

    #[test]
    fn bad_game_name_is_e4() {
        let msg = GameMsg::GameAnnounce {
            game: GameInfo {
                game_id: "ok".into(),
                game_name: String::new(),
                capabilities: vec![],
            },
        };
        assert_eq!(msg.validate(), Err(PairError::MalformedMessage));
        let long = GameMsg::GameAnnounce {
            game: GameInfo {
                game_id: "ok".into(),
                game_name: "x".repeat(GAME_NAME_MAX + 1),
                capabilities: vec![],
            },
        };
        assert_eq!(long.validate(), Err(PairError::MalformedMessage));
    }

    #[test]
    fn unknown_capability_is_e4() {
        let msg = GameMsg::GameAnnounce {
            game: GameInfo {
                game_id: "ok".into(),
                game_name: "Ok".into(),
                capabilities: vec!["telepatia".into()],
            },
        };
        assert_eq!(msg.validate(), Err(PairError::MalformedMessage));
    }

    #[test]
    fn unknown_type_or_missing_field_is_e4() {
        assert_eq!(
            GameMsg::decode("{\"type\":\"game_quit\",\"game_id\":\"x\"}"),
            Err(PairError::MalformedMessage)
        );
        assert_eq!(
            GameMsg::decode("{\"type\":\"game_announce\",\"game_id\":\"x\"}"),
            Err(PairError::MalformedMessage)
        );
    }

    // AC-2 (REQ-006): `game_frame` valida los parámetros de SPEC-003 §4.
    fn frame(gfn: u64, w: u16, h: u16) -> GameMsg {
        GameMsg::GameFrame {
            gfn,
            w,
            h,
            fmt: FrameFormat::Rgb8,
            ts_capture_us: None,
            idr: None,
            data: rgb_data(w, h),
        }
    }

    fn rgb_data(w: u16, h: u16) -> String {
        use base64::Engine as _;
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(vec![
            3u8;
            usize::from(w)
                * usize::from(h)
                * 3
        ])
    }

    #[test]
    fn game_frame_roundtrip() {
        let msg = frame(0, 32, 16);
        let line = msg.encode().unwrap();
        assert!(!line.contains('\n'));
        assert!(line.starts_with("{\"type\":\"game_frame\""));
        assert_eq!(GameMsg::decode(&line).unwrap(), msg);
    }

    #[test]
    fn game_frame_dims_out_of_range_is_e4() {
        assert_eq!(
            frame(0, 15, 16).validate(),
            Err(PairError::MalformedMessage)
        );
        assert_eq!(
            frame(0, 16, 15).validate(),
            Err(PairError::MalformedMessage)
        );
        assert_eq!(
            frame(0, GAME_FRAME_MAX_WIDTH + 1, 16).validate(),
            Err(PairError::MalformedMessage)
        );
        assert_eq!(
            frame(0, 16, GAME_FRAME_MAX_HEIGHT + 1).validate(),
            Err(PairError::MalformedMessage)
        );
    }

    #[test]
    fn game_frame_with_wrong_data_size_is_e4() {
        use base64::Engine as _;
        let short =
            base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(vec![1u8; 32 * 16 * 3 - 1]);
        let msg = GameMsg::GameFrame {
            gfn: 0,
            w: 32,
            h: 16,
            fmt: FrameFormat::Rgb8,
            ts_capture_us: None,
            idr: None,
            data: short,
        };
        assert_eq!(msg.validate(), Err(PairError::MalformedMessage));
    }

    #[test]
    fn game_frame_with_non_base64_data_is_e4() {
        let msg = GameMsg::GameFrame {
            gfn: 0,
            w: 16,
            h: 16,
            fmt: FrameFormat::Rgb8,
            ts_capture_us: None,
            idr: None,
            data: "¡no es base64!".into(),
        };
        assert_eq!(msg.validate(), Err(PairError::MalformedMessage));
    }

    #[test]
    fn game_frame_zero_is_valid() {
        assert_eq!(frame(0, 16, 16).validate(), Ok(()));
    }

    // TEST-073 — CR-015: H.264 IPC requires encoder timestamp and IDR flag.
    #[test]
    fn h264_game_frame_requires_capture_timestamp_and_idr_metadata() {
        let message = GameMsg::GameFrame {
            gfn: 0,
            w: 1280,
            h: 720,
            fmt: FrameFormat::H264,
            ts_capture_us: Some(10),
            idr: Some(true),
            data: "AAAA".into(),
        };
        let line = message.encode().unwrap();
        assert_eq!(GameMsg::decode(&line).unwrap(), message);

        let no_timestamp = line.replace(",\"ts_capture_us\":10", "");
        assert_eq!(
            GameMsg::decode(&no_timestamp),
            Err(PairError::MalformedMessage)
        );
        let no_idr = line.replace(",\"idr\":true", "");
        assert_eq!(GameMsg::decode(&no_idr), Err(PairError::MalformedMessage));
    }
}
