//! SDK Twinpad — binding para juegos huésped (REQ-006, ADR-006).
//!
//! Frontera ARCH §3: el SDK habla con el `Servidor PC` solo vía la API de
//! anuncio/registro del juego; nunca escribe directo en la red/transporte.
//!
//! v1 (SPEC-006 `Approved` + ADR-008): anuncio de presencia por el canal IPC
//! TCP localhost. La conexión viva **es** el anuncio vivo: al soltar
//! [`TwinpadGame`] (o al crashear el proceso) el juego deja de estar
//! disponible automáticamente. AC-3 (inputs) entra en una iteración
//! posterior sobre este mismo canal.
//!
//! AC-2 (REQ-006): el juego publica los Framebuffers de su Segunda pantalla
//! con [`TwinpadGame::push_frame`]; el server los re-empaqueta como
//! `video_frame` de SPEC-003 hacia la app.

use std::io::{Read, Write};
use std::net::TcpStream;
use std::time::Duration;

use twinpad_core::game::{GAME_ID_MAX, GAME_NAME_MAX, GameInfo, GameMsg};

/// Puerto IPC del Servidor por defecto (SPEC-006/ADR-008).
pub const DEFAULT_IPC_PORT: u16 = 8421;

/// Timeout para el anuncio (el server debe aceptarlo al momento).
const ANNOUNCE_TIMEOUT: Duration = Duration::from_secs(10);

/// Sondeo de rechazo: el server no responde en el flujo feliz (§7) — solo
/// cierra el socket si el anuncio es inválido o duplicado (§9). Esperar este
/// lapso sin datos ni cierre confirma el registro.
const REJECT_PROBE: Duration = Duration::from_millis(500);

/// Error del SDK (§9: errores locales, sin códigos de sesión).
#[derive(Debug)]
pub enum SdkError {
    /// El server no está corriendo o no acepta conexiones en el puerto IPC.
    ServerUnreachable(std::io::Error),
    /// El server cerró la conexión: anuncio inválido o `game_id` duplicado.
    AnnouncementRejected,
    /// Los campos no cumplen SPEC-006 §4 (se valida antes de conectar).
    InvalidConfig,
}

/// Identificación del juego (SPEC-006 §4). Valídada al construir.
#[derive(Clone, Debug)]
pub struct GameConfig {
    /// 1–64 chars `[a-z0-9_.-]`, único entre juegos vivos.
    pub game_id: String,
    /// 1–128 chars legibles.
    pub game_name: String,
    /// Subconjunto de capacidades (`second_screen`, `audio`,
    /// `input_consumption`); vacío válido (§10).
    pub capabilities: Vec<String>,
}

impl GameConfig {
    /// Crea la config validando los constraints de §4.
    ///
    /// # Errors
    /// [`SdkError::InvalidConfig`] si algún campo está fuera de rango.
    pub fn new(
        game_id: impl Into<String>,
        game_name: impl Into<String>,
        capabilities: Vec<String>,
    ) -> Result<Self, SdkError> {
        let game_id = game_id.into();
        let game_name = game_name.into();
        let valid_id = !game_id.is_empty()
            && game_id.len() <= GAME_ID_MAX
            && game_id.chars().all(|c| {
                c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, '_' | '.' | '-')
            });
        if !valid_id || game_name.is_empty() || game_name.len() > GAME_NAME_MAX {
            return Err(SdkError::InvalidConfig);
        }
        for cap in &capabilities {
            if !twinpad_core::game::CAPABILITIES.contains(&cap.as_str()) {
                return Err(SdkError::InvalidConfig);
            }
        }
        Ok(Self {
            game_id,
            game_name,
            capabilities,
        })
    }

    fn info(&self) -> GameInfo {
        GameInfo {
            game_id: self.game_id.clone(),
            game_name: self.game_name.clone(),
            capabilities: self.capabilities.clone(),
        }
    }
}

/// Handle del juego anunciado: manténgalo vivo mientras el juego quiera
/// estar disponible. Soltarlo (o crashear) lo retira del server (R2).
pub struct TwinpadGame {
    stream: TcpStream,
    game_id: String,
    /// Número de frame publicado (AC-2): monotónico desde 0 por conexión.
    gfn: u64,
}

impl TwinpadGame {
    /// Anuncia el juego al Servidor PC (SPEC-006 §7 pasos 1–2).
    ///
    /// # Errors
    /// [`SdkError::ServerUnreachable`] si no hay server IPC;
    /// [`SdkError::AnnouncementRejected`] si el server cerró (anuncio
    /// inválido o `game_id` duplicado, §9); [`SdkError::InvalidConfig`].
    pub fn announce(config: GameConfig) -> Result<Self, SdkError> {
        Self::announce_on(("127.0.0.1", DEFAULT_IPC_PORT), config)
    }

    /// Igual que [`Self::announce`] pero contra un endpoint explícito
    /// (útil para pruebas y despliegues con puerto distinto).
    ///
    /// # Errors
    /// Los mismos que [`Self::announce`].
    pub fn announce_on(
        endpoint: impl std::net::ToSocketAddrs,
        config: GameConfig,
    ) -> Result<Self, SdkError> {
        let stream = TcpStream::connect(endpoint).map_err(SdkError::ServerUnreachable)?;
        stream
            .set_write_timeout(Some(ANNOUNCE_TIMEOUT))
            .map_err(SdkError::ServerUnreachable)?;
        stream
            .set_read_timeout(Some(REJECT_PROBE))
            .map_err(SdkError::ServerUnreachable)?;
        let mut stream = stream;

        let line = GameMsg::GameAnnounce {
            game: config.info(),
        }
        .encode()
        .map_err(|_| SdkError::InvalidConfig)?;
        stream
            .write_all(line.as_bytes())
            .and_then(|_| stream.write_all(b"\n"))
            .and_then(|_| stream.flush())
            .map_err(SdkError::ServerUnreachable)?;

        // §9: el server cierra el socket si el anuncio es inválido o el
        // game_id está duplicado; el flujo feliz no tiene respuesta, así que
        // un sondeo breve sin datos ni cierre confirma el registro.
        let mut probe = [0u8; 1];
        let mut reader = stream.try_clone().map_err(SdkError::ServerUnreachable)?;
        match reader.read(&mut probe) {
            Ok(0) | Ok(_) => Err(SdkError::AnnouncementRejected),
            Err(e)
                if e.kind() == std::io::ErrorKind::WouldBlock
                    || e.kind() == std::io::ErrorKind::TimedOut =>
            {
                stream
                    .set_read_timeout(None)
                    .map_err(SdkError::ServerUnreachable)?;
                Ok(Self {
                    stream,
                    game_id: config.game_id,
                    gfn: 0,
                })
            }
            Err(e) => Err(SdkError::ServerUnreachable(e)),
        }
    }

    /// Identificación del juego anunciado (vía `game_id`).
    #[must_use]
    pub fn game_id(&self) -> &str {
        &self.game_id
    }

    /// Publica un Framebuffer de la Segunda pantalla del juego (REQ-006
    /// AC-2). `data` son `w×h×bpp` bytes crudos de `fmt`, fila mayor.
    ///
    /// El `gfn` lo asigna el SDK (monotónico desde 0 por conexión); el
    /// server re-empacará el frame con el `fn` de su stream hacia la app.
    ///
    /// # Errors
    /// [`SdkError::InvalidConfig`] si los parámetros no cumplen SPEC-003 §4
    /// (dimensiones o tamaño de `data`); [`SdkError::AnnouncementRejected`]
    /// si la conexión cayó (el juego dejó de estar disponible, §9).
    pub fn push_frame(
        &mut self,
        w: u16,
        h: u16,
        fmt: twinpad_core::stream::FrameFormat,
        data: &[u8],
    ) -> Result<(), SdkError> {
        // `jpeg` (CR-001) tiene tamaño libre; los formatos crudos deben
        // medir exactamente w×h×bpp.
        let size_ok = match fmt.bytes_per_pixel() {
            Some(bpp) => data.len() == usize::from(w) * usize::from(h) * bpp,
            None => !data.is_empty(),
        };
        if !size_ok {
            return Err(SdkError::InvalidConfig);
        }
        use base64::Engine as _;
        let encoded = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(data);
        self.gfn = self.gfn.wrapping_add(1);
        let line = GameMsg::GameFrame {
            gfn: self.gfn,
            w,
            h,
            fmt,
            data: encoded,
        }
        .encode()
        .map_err(|_| SdkError::InvalidConfig)?;
        self.stream
            .write_all(line.as_bytes())
            .and_then(|_| self.stream.write_all(b"\n"))
            .and_then(|_| self.stream.flush())
            .map_err(|_| SdkError::AnnouncementRejected)
    }
}

// ENG §6: unwrap permitido solo en tests.
#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    // TEST-021 — verifies SPEC-006 (§4: validación local del anuncio).
    #[test]
    fn config_validates_constraints() {
        assert!(GameConfig::new("pacdemo", "Pac Demo", vec!["second_screen".into()]).is_ok());
        assert!(matches!(
            GameConfig::new("", "X", vec![]),
            Err(SdkError::InvalidConfig)
        ));
        assert!(matches!(
            GameConfig::new("MAYUSCULAS", "X", vec![]),
            Err(SdkError::InvalidConfig)
        ));
        assert!(matches!(
            GameConfig::new("ok", "", vec![]),
            Err(SdkError::InvalidConfig)
        ));
        assert!(matches!(
            GameConfig::new("ok", "X", vec!["no_existe".into()]),
            Err(SdkError::InvalidConfig)
        ));
    }

    #[test]
    fn announce_rejected_when_no_server() {
        // Puerto sin listener: nadie escucha.
        let cfg = GameConfig::new("pacdemo", "Pac Demo", vec![]).unwrap();
        let result = TwinpadGame::announce_on(("127.0.0.1", 1), cfg);
        assert!(matches!(result, Err(SdkError::ServerUnreachable(_))));
    }
}
