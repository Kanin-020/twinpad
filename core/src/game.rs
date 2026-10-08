//! Detección de juego con SDK Twinpad — SPEC-006 §4/§5 (ADR-008).
//!
//! Dos mensajes con codec estricto (desviación → E4):
//! - `game_announce`: juego → server por el canal IPC (TCP localhost, la
//!   conexión viva es el anuncio vivo — ADR-008).
//! - `games_available`: server → app por la sesión de SPEC-001 (§5), cada
//!   vez que la lista de juegos vivos cambia.

use crate::error::{PairError, PairResult};
use serde::Deserialize;
use serde::Serialize;

/// Capacidades declarables por un juego (SPEC-006 §4; subconjunto válido).
pub const CAPABILITIES: [&str; 3] = ["second_screen", "audio", "input_consumption"];

/// Límites de los campos del anuncio (SPEC-006 §4).
pub const GAME_ID_MAX: usize = 64;
pub const GAME_NAME_MAX: usize = 128;

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
        }
    }
}

/// Valida un [`GameInfo`] según SPEC-006 §4.
fn validate_game(game: &GameInfo) -> PairResult<()> {
    let id_ok = !game.game_id.is_empty()
        && game.game_id.len() <= GAME_ID_MAX
        && game
            .game_id
            .chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, '_' | '.' | '-'));
    if !id_ok {
        return Err(PairError::MalformedMessage);
    }
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
}
