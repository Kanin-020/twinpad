//! Carpeta de datos del Servidor PC (SPEC-010).
//!
//! La carpeta es fija: `$XDG_DATA_HOME/twinpad` (fallback
//! `~/.local/share/twinpad`). No hay selección ni asistente de instalación.

use std::path::PathBuf;

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
