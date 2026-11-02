//! Entrada de autoarranque XDG del Servidor PC (SPEC-010 W5, CR-061; REQ-001
//! AC-1). El toggle «Iniciar al arrancar el equipo» instala/elimina un
//! desktop entry en `$XDG_CONFIG_HOME/autostart` (fallback
//! `~/.config/autostart`) de forma idempotente y con error explícito (E5: se
//! devuelve a quien llama; el estado del server no cambia).

use std::fs;
use std::path::PathBuf;

/// Contenido del desktop entry. `Terminal=false`: el server arranca sin
/// ventana de consola; el QR vive en la página de gestión (CR-061) y/o en
/// el tray.
const DESKTOP_ENTRY: &str = "[Desktop Entry]\n\
Type=Application\n\
Name=Twinpad Servidor PC\n\
Comment=Servidor Twinpad: acepta sesiones de Pairing en segundo plano (CR-061, SPEC-010 W5)\n\
Exec=twinpad-server\n\
Terminal=false\n\
Categories=Utility;\n\
StartupNotify=false\n";

/// Identificador del archivo de entrada (W5: siempre el mismo nombre → una
/// sola entrada por usuario repetido idempotente).
const APP_ID: &str = "twinpad-server";

/// Error al operar la entrada de autoarranque (E5).
#[derive(Debug)]
pub enum AutostartError {
    /// No se puede determinar el directorio base del usuario.
    NoHomeDir,
    /// Fallo de E/S (crear directorio, escribir o eliminar el entry).
    Io(std::io::Error),
}

impl std::fmt::Display for AutostartError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            AutostartError::NoHomeDir => write!(f, "sin HOME/XDG para el autoarranque"),
            AutostartError::Io(e) => write!(f, "E/S de autoarranque: {e}"),
        }
    }
}

/// Ruta del desktop entry bajo una base de configuración dada (inyección
/// para tests: `<base>/autostart/twinpad-server.desktop`).
pub fn entry_path_under(config_base: &std::path::Path) -> PathBuf {
    config_base
        .join("autostart")
        .join(format!("{APP_ID}.desktop"))
}

/// Base de configuración del usuario: `$XDG_CONFIG_HOME` o `~/.config`.
fn config_base() -> Result<PathBuf, AutostartError> {
    if let Ok(xdg) = std::env::var("XDG_CONFIG_HOME") {
        if !xdg.trim().is_empty() {
            return Ok(PathBuf::from(xdg));
        }
    }
    std::env::var("HOME")
        .map_err(|_| AutostartError::NoHomeDir)
        .map(|home| PathBuf::from(home).join(".config"))
}

/// Ruta del desktop entry de autoarranque (W5, XDG).
pub fn entry_path() -> Result<PathBuf, AutostartError> {
    Ok(entry_path_under(&config_base()?))
}

/// ¿Está instalada la entrada de autoarranque (W5)?
pub fn is_installed() -> bool {
    entry_path().map(|p| p.exists()).unwrap_or(false)
}

/// Instala la entrada (W5 on), idempotente (dos instalaciones dejan una
/// sola entrada con el mismo contenido).
pub fn install_under(config_base: &std::path::Path) -> Result<(), AutostartError> {
    let path = entry_path_under(config_base);
    if let Some(dir) = path.parent() {
        fs::create_dir_all(dir).map_err(AutostartError::Io)?;
    }
    fs::write(&path, DESKTOP_ENTRY).map_err(AutostartError::Io)
}

/// Enlace con la config real del usuario (los tests usan `_under`).
pub fn install() -> Result<(), AutostartError> {
    install_under(&config_base()?)
}

/// Elimina la entrada (W5 off). Sin entrada previa es no-op (devuelve false).
pub fn remove_under(config_base: &std::path::Path) -> Result<bool, AutostartError> {
    let path = entry_path_under(config_base);
    if !path.exists() {
        return Ok(false);
    }
    fs::remove_file(path).map_err(AutostartError::Io)?;
    Ok(true)
}

/// Enlace con la config real del usuario (los tests usan `_under`).
pub fn remove() -> Result<bool, AutostartError> {
    remove_under(&config_base()?)
}

// ENG §6: unwrap/expect permitidos solo en tests.
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;

    use std::sync::atomic::Ordering as AtomicOrdering;
    fn temp_base() -> PathBuf {
        // Directorio único por TEST (los tests corren en paralelo: mismo PID
        // pero distinto nombre; evita la carrera en el remove_dir_all).
        static SEQ: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
        let dir = std::env::temp_dir().join(format!(
            "twinpad-autostart-test-{}-{}",
            std::process::id(),
            SEQ.fetch_add(1, AtomicOrdering::Relaxed),
        ));
        let _ = std::fs::remove_dir_all(&dir);
        dir
    }

    /// TEST-0137 (CR-061, SPEC-010 W5): install escribe el desktop entry y es
    /// idempotente (dos instalaciones dejan la misma entrada y contenido).
    #[test]
    fn install_writes_entry_idempotently() {
        let base = temp_base();
        let entry = entry_path_under(&base);
        install_under(&base).expect("install debe crear el entry");
        assert!(entry.exists());
        let first = std::fs::read_to_string(&entry).unwrap();
        // Idempotente: segunda instalación, misma ruta, mismo contenido.
        install_under(&base).expect("re-install debe ser no-op con el mismo contenido");
        assert_eq!(std::fs::read_to_string(&entry).unwrap(), first);
        // El entry declara cómo ha de lanzarse el proceso.
        assert!(first.contains("[Desktop Entry]"));
        assert!(first.contains("Exec=twinpad-server"));
        assert!(first.contains("Terminal=false"));
        let _ = std::fs::remove_dir_all(&base);
    }

    /// TEST-0137 (CR-061, SPEC-010 W5): remove elimina el entry y quitar sin
    /// entrada previa es no-op (false).
    #[test]
    fn remove_deletes_entry_and_absent_is_noop() {
        let base = temp_base();
        let entry = entry_path_under(&base);
        assert!(!remove_under(&base).expect("sin entry: no-op"));
        install_under(&base).unwrap();
        assert!(entry.exists());
        assert!(remove_under(&base).expect("remove debe borrarlo"));
        assert!(!entry.exists());
        let _ = std::fs::remove_dir_all(&base);
    }
}
