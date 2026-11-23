//! Carpeta de datos del Servidor PC (SPEC-010).
//!
//! La carpeta es fija: `$XDG_DATA_HOME/twinpad` (fallback
//! `~/.local/share/twinpad`). No hay selección ni asistente de instalación.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

/// Fichero donde el servidor publica la ruta de su propio ejecutable.
pub const SERVER_PATH_FILE: &str = "server-path";

#[derive(Debug)]
pub enum DataDirError {
    NoHomeDir,
}

impl std::fmt::Display for DataDirError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DataDirError::NoHomeDir => write!(f, "sin HOME/XDG para la carpeta de datos"),
        }
    }
}

/// Carpeta de datos: `$XDG_DATA_HOME/twinpad` o `$HOME/.local/share/twinpad`.
pub fn current_dir() -> Result<PathBuf, DataDirError> {
    if let Ok(value) = std::env::var("XDG_DATA_HOME") {
        if !value.trim().is_empty() {
            return Ok(PathBuf::from(value).join("twinpad"));
        }
    }
    std::env::var("HOME")
        .map(|home| PathBuf::from(home).join(".local/share/twinpad"))
        .map_err(|_| DataDirError::NoHomeDir)
}

/// Ejecutable en curso: el AppImage si el servidor se lanzó desde uno
/// (`$APPIMAGE`), y si no, el binario actual.
pub fn running_executable() -> Option<PathBuf> {
    std::env::var("APPIMAGE")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .map(PathBuf::from)
        .or_else(|| std::env::current_exe().ok())
}

/// Publica `exe` en `dir/server-path` (escritura atómica: temporal +
/// renombrado), para que otros componentes —el plugin de Decky— puedan
/// arrancar el servidor aunque su ruta no esté en `PATH` ni en `$APPIMAGE`.
pub fn publish_server_path(dir: &Path, exe: &Path) -> io::Result<()> {
    fs::create_dir_all(dir)?;
    let tmp = dir.join(format!("{SERVER_PATH_FILE}.tmp"));
    fs::write(&tmp, exe.display().to_string())?;
    fs::rename(&tmp, dir.join(SERVER_PATH_FILE))
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;

    /// TEST-0192: la ruta publicada queda legible y sin temporal huérfano.
    #[test]
    fn published_server_path_is_stored_as_plain_text() {
        let dir = std::env::temp_dir().join(format!(
            "twinpad-data-dir-{}-{}",
            std::process::id(),
            line!()
        ));
        let _ = fs::remove_dir_all(&dir);
        let exe = Path::new("/opt/twinpad/twinpad-server");
        publish_server_path(&dir, exe).expect("publicar la ruta del binario");
        let published = fs::read_to_string(dir.join(SERVER_PATH_FILE)).expect("leer la ruta");
        assert_eq!(published, exe.display().to_string());
        assert!(!dir.join(format!("{SERVER_PATH_FILE}.tmp")).exists());
        let _ = fs::remove_dir_all(&dir);
    }
}
