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
use base64::Engine as _;
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
    /// juego → server → Android (CR-033): patrón de vibración de
    /// `VPADControlMotor`. `length` = bytes del patrón (0 = parar); `data`
    /// es el patrón en base64url de exactamente `length` bytes.
    #[serde(rename = "rumble")]
    Rumble { length: u8, data: String },
    /// Android → server → Cemu (CR-034): access unit H.264 de la cámara
    /// frontal del teléfono, 320x240. `data` es base64url no vacío.
    #[serde(rename = "camera_frame")]
    CameraFrame {
        w: u16,
        h: u16,
        ts_capture_us: u64,
        idr: bool,
        data: String,
    },
    /// juego → server → Android (CR-038): demanda vigente del video de la
    /// cámara del teléfono. `on` true cuando el juego abrió instancias de
    /// cámara HLE y false al cerrarlas; el envío de `camera_frame` pasa a
    /// estar gobernado por este mensaje.
    #[serde(rename = "camera_request")]
    CameraRequest {
        /// true: abrir la cámara del teléfono; false: detenerla.
        on: bool,
    },
    /// Android → server → Cemu (CR-035): PCM s16le mono 32 kHz del
    /// micrófono del teléfono hacia la API de micrófono HLE `mic` del
    /// GamePad. `data` es base64url de exactamente 2·`samples` bytes,
    /// no vacío.
    #[serde(rename = "mic_chunk")]
    MicChunk {
        /// Muestras s16 de este chunk (2 bytes por muestra).
        samples: u16,
        /// PCM s16le little-endian mono en base64url.
        data: String,
    },
    /// juego → server → Android (CR-035): demanda vigente del micrófono
    /// del teléfono. `on` true cuando el juego abrió el micrófono HLE
    /// `mic` y false al cerrarlo; la captura del teléfono pasa a estar
    /// gobernada por este mensaje.
    #[serde(rename = "mic_request")]
    MicRequest {
        /// true: abrir el micrófono del teléfono; false: detenerlo.
        on: bool,
    },
}

/// Geometría fija de la cámara del teléfono enviada a Cemu (CR-034).
pub const CAMERA_WIDTH: u16 = 320;
pub const CAMERA_HEIGHT: u16 = 240;

/// Máximo de bytes de un patrón de vibración (`VPADControlMotor`, CR-033).
pub const RUMBLE_MAX_BYTES: u8 = 120;

/// Muestras s16 máximo por chunk de micrófono: 1 s a 32 kHz (CR-035).
pub const MIC_MAX_SAMPLES: u16 = 32000;

/// Muestras s16 mínimo por chunk de micrófono: 1 ms a 32 kHz (CR-035).
pub const MIC_MIN_SAMPLES: u16 = 32;

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
            GameMsg::CameraFrame { w, h, data, .. } => {
                if *w != CAMERA_WIDTH || *h != CAMERA_HEIGHT || data.is_empty() {
                    return Err(PairError::MalformedMessage);
                }
                base64::engine::general_purpose::URL_SAFE_NO_PAD
                    .decode(data)
                    .map_err(|_| PairError::MalformedMessage)?;
                Ok(())
            }
            GameMsg::Rumble { length, data } => {
                if *length > RUMBLE_MAX_BYTES {
                    return Err(PairError::MalformedMessage);
                }
                let decoded = base64::engine::general_purpose::URL_SAFE_NO_PAD
                    .decode(data)
                    .map_err(|_| PairError::MalformedMessage)?;
                if decoded.len() != usize::from(*length) {
                    return Err(PairError::MalformedMessage);
                }
                Ok(())
            }
            // CR-038: el flag es un booleano wire estricto; nada más que validar.
            GameMsg::CameraRequest { .. } | GameMsg::MicRequest { .. } => Ok(()),
            GameMsg::MicChunk { samples, data } => {
                if *samples < MIC_MIN_SAMPLES || *samples > MIC_MAX_SAMPLES || data.is_empty() {
                    return Err(PairError::MalformedMessage);
                }
                // 2 bytes por muestra s16.
                let decoded = base64::engine::general_purpose::URL_SAFE_NO_PAD
                    .decode(data)
                    .map_err(|_| PairError::MalformedMessage)?;
                if decoded.len() != usize::from(*samples) * 2 {
                    return Err(PairError::MalformedMessage);
                }
                Ok(())
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

    // TEST-092 — verifies SPEC-006 (CR-033): `rumble` round-trip and strict validation.
    #[test]
    fn rumble_round_trips_and_rejects_bad_length_or_data() {
        use base64::Engine as _;
        let engine = base64::engine::general_purpose::URL_SAFE_NO_PAD;
        let pattern = vec![0xAAu8, 0x55, 0x0F];
        let msg = GameMsg::Rumble {
            length: 3,
            data: engine.encode(&pattern),
        };
        assert_eq!(GameMsg::decode(&msg.encode().unwrap()).unwrap(), msg);

        // Parar: longitud 0 sin datos.
        let stop = GameMsg::Rumble {
            length: 0,
            data: String::new(),
        };
        assert_eq!(GameMsg::decode(&stop.encode().unwrap()).unwrap(), stop);

        // Longitud declarada distinta de los bytes decodificados.
        assert_eq!(
            GameMsg::decode(&format!(
                r#"{{"type":"rumble","length":2,"data":"{}"}}"#,
                engine.encode(&pattern)
            )),
            Err(PairError::MalformedMessage)
        );
        // Longitud por encima del máximo de 120 bytes.
        assert_eq!(
            GameMsg::decode(&format!(
                r#"{{"type":"rumble","length":121,"data":"{}"}}"#,
                engine.encode(vec![0u8; 121])
            )),
            Err(PairError::MalformedMessage)
        );
        // Base64 inválido.
        assert_eq!(
            GameMsg::decode(r#"{"type":"rumble","length":1,"data":"!!"}"#),
            Err(PairError::MalformedMessage)
        );
    }

    // TEST-0101 — verifies SPEC-006 (CR-035): `mic_chunk` round-trip, strict sample
    // count/byte parity and `mic_request` boolean flag.
    #[test]
    fn mic_chunk_and_request_round_trip_and_reject_bad_shapes() {
        use base64::Engine as _;
        let engine = base64::engine::general_purpose::URL_SAFE_NO_PAD;
        // Chunk mínimo válido: 32 muestras (1 ms a 32 kHz) = 64 bytes s16le.
        let pcm = vec![0x01u8, 0x00, 0xFF, 0xFF, 0x02, 0x00];
        let mut full = vec![0u8; 64];
        full.extend_from_slice(&pcm);
        let msg = GameMsg::MicChunk {
            samples: 35,
            data: engine.encode(&full),
        };
        assert_eq!(GameMsg::decode(&msg.encode().unwrap()).unwrap(), msg);

        // `samples` incoherente con los bytes decodificados.
        assert_eq!(
            GameMsg::decode(&format!(
                r#"{{"type":"mic_chunk","samples":36,"data":"{}"}}"#,
                engine.encode(&full)
            )),
            Err(PairError::MalformedMessage)
        );
        // Bytes no pares (medias muestras).
        assert_eq!(
            GameMsg::decode(&format!(
                r#"{{"type":"mic_chunk","samples":32,"data":"{}"}}"#,
                engine.encode(&full[..65])
            )),
            Err(PairError::MalformedMessage)
        );
        // Vacío y por debajo del mínimo (32 muestras = 1 ms).
        assert_eq!(
            GameMsg::decode(r#"{"type":"mic_chunk","samples":0,"data":""}"#),
            Err(PairError::MalformedMessage)
        );
        assert_eq!(
            GameMsg::decode(&format!(
                r#"{{"type":"mic_chunk","samples":31,"data":"{}"}}"#,
                engine.encode(vec![0u8; 62])
            )),
            Err(PairError::MalformedMessage)
        );
        // Por encima del máximo (32000 muestras = 1 s).
        assert_eq!(
            GameMsg::decode(&format!(
                r#"{{"type":"mic_chunk","samples":32001,"data":"{}"}}"#,
                engine.encode(vec![0u8; 32001 * 2])
            )),
            Err(PairError::MalformedMessage)
        );
        // Base64 inválido.
        assert_eq!(
            GameMsg::decode(r#"{"type":"mic_chunk","samples":32,"data":"!!"}"#),
            Err(PairError::MalformedMessage)
        );

        // `mic_request`: ida y vuelta y flag estricto.
        let on = GameMsg::MicRequest { on: true };
        assert_eq!(GameMsg::decode(&on.encode().unwrap()).unwrap(), on);
        let off = GameMsg::MicRequest { on: false };
        assert_eq!(GameMsg::decode(&off.encode().unwrap()).unwrap(), off);
        // Flag faltante.
        assert_eq!(
            GameMsg::decode(r#"{"type":"mic_request"}"#),
            Err(PairError::MalformedMessage)
        );
        // Flag no booleano.
        assert_eq!(
            GameMsg::decode(r#"{"type":"mic_request","on":"no"}"#),
            Err(PairError::MalformedMessage)
        );
        // Campo desconocido.
        assert_eq!(
            GameMsg::decode(r#"{"type":"mic_request","on":true,"x":1}"#),
            Err(PairError::MalformedMessage)
        );
    }

    // TEST-097 — verifies SPEC-006 (CR-038): `camera_request` round-trip with a strict boolean flag.    #[test]
    fn camera_request_round_trips_and_rejects_bad_flag() {
        let msg = GameMsg::CameraRequest { on: true };
        assert_eq!(GameMsg::decode(&msg.encode().unwrap()).unwrap(), msg);
        let off = GameMsg::CameraRequest { on: false };
        assert_eq!(GameMsg::decode(&off.encode().unwrap()).unwrap(), off);

        // Flag faltante.
        assert_eq!(
            GameMsg::decode(r#"{"type":"camera_request"}"#),
            Err(PairError::MalformedMessage)
        );
        // Flag no booleano.
        assert_eq!(
            GameMsg::decode(r#"{"type":"camera_request","on":"yes"}"#),
            Err(PairError::MalformedMessage)
        );
        // Campo desconocido (contrato NDJSON estricto).
        assert_eq!(
            GameMsg::decode(r#"{"type":"camera_request","on":true,"x":1}"#),
            Err(PairError::MalformedMessage)
        );
    }

    // TEST-093 — verifies SPEC-006 (CR-034): `camera_frame` round-trip and strict geometry.
    #[test]
    fn camera_frame_round_trips_and_rejects_bad_geometry() {
        use base64::Engine as _;
        let engine = base64::engine::general_purpose::URL_SAFE_NO_PAD;
        let au = vec![0x00u8, 0x00, 0x00, 0x01, 0x67];
        let msg = GameMsg::CameraFrame {
            w: CAMERA_WIDTH,
            h: CAMERA_HEIGHT,
            ts_capture_us: 33_000,
            idr: true,
            data: engine.encode(&au),
        };
        assert_eq!(GameMsg::decode(&msg.encode().unwrap()).unwrap(), msg);

        // Geometría distinta de 320x240.
        assert_eq!(
            GameMsg::decode(&format!(
                r#"{{"type":"camera_frame","w":640,"h":480,"ts_capture_us":0,"idr":false,"data":"{}"}}"#,
                engine.encode(&au)
            )),
            Err(PairError::MalformedMessage)
        );
        // Datos vacíos.
        assert_eq!(
            GameMsg::decode(
                r#"{"type":"camera_frame","w":320,"h":240,"ts_capture_us":0,"idr":false,"data":""}"#
            ),
            Err(PairError::MalformedMessage)
        );
        // Campo obligatorio ausente.
        assert_eq!(
            GameMsg::decode(&format!(
                r#"{{"type":"camera_frame","w":320,"h":240,"ts_capture_us":0,"data":"{}"}}"#,
                engine.encode(&au)
            )),
            Err(PairError::MalformedMessage)
        );
        // Base64 inválido.
        assert_eq!(
            GameMsg::decode(
                r#"{"type":"camera_frame","w":320,"h":240,"ts_capture_us":0,"idr":true,"data":"!!"}"#
            ),
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
