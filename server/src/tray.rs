//! Bandeja del sistema del Servidor PC (SPEC-010 W1/W3/W4/W5/W7, CR-061;
//! apertura en ventana web del sistema CR-063).
//!
//! Icono StatusNotifierItem vía `ksni` (decisión ADR-010). El spawn es
//! fail-soft (W7/E4): si el entorno no ofrece host de bandeja, el server
//! sigue vivo con stdout + página HTTP; aquí solo log. Las acciones del
//! menú solo tocan el flag de parada compartido con `webui` y la entrada
//! de autoarranque de `autostart.rs`. «Abrir interfaz» abre la página
//! como VENTANA de web del sistema (modo app del navegador del PC, CR-063);
//! fallback al navegador predeterminado si no hay motor de modo app.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

/// Acciones compartidas del tray con `webui` y `autostart` (DES-005 §4).
pub struct TrayActions {
    /// Puerto HTTP local de la página (W3: abrir interfaz).
    pub webui_port: u16,
    /// Flag compartido de parada (W4).
    pub shutdown: Arc<AtomicBool>,
}

struct TwinpadTray {
    actions: TrayActions,
    autostart_on: bool,
}

impl ksni::Tray for TwinpadTray {
    fn id(&self) -> String {
        "twinpad-server".to_string()
    }

    fn title(&self) -> String {
        "Twinpad Servidor PC — activo".to_string()
    }

    fn tool_tip(&self) -> ksni::ToolTip {
        ksni::ToolTip {
            title: "Twinpad Servidor PC".to_string(),
            description: "Servidor activo: acepta sesiones de Pairing en la LAN.".to_string(),
            ..Default::default()
        }
    }

    fn menu(&self) -> Vec<ksni::MenuItem<Self>> {
        use ksni::menu::*;
        vec![
            // W3 (CR-063): abrir la interfaz en ventana web del sistema.
            StandardItem {
                label: "Abrir interfaz de gestión".into(),
                icon_name: "web-browser".into(),
                activate: Box::new(|tray: &mut Self| {
                    open_app_window(tray.actions.webui_port);
                }),
                ..Default::default()
            }
            .into(),
            // Recordar dónde vive el URI por stdout (W7: útil sin GUI).
            StandardItem {
                label: "¿Dónde está el URI?".into(),
                activate: Box::new(|_tray: &mut Self| {
                    println!(
                        "Twinpad: el URI vigente se imprimió en el arranque en este mismo terminal (el QR también se regenera cada 300 s; CR-061 W3)."
                    );
                }),
                ..Default::default()
            }
            .into(),
            MenuItem::Separator,
            // W5: toggle del autoarranque.
            CheckmarkItem {
                label: "Iniciar al arrancar el equipo".into(),
                checked: self.autostart_on,
                activate: Box::new(|tray: &mut Self| {
                    let result = if crate::autostart::is_installed() {
                        crate::autostart::remove().map(|_| false)
                    } else {
                        crate::autostart::install().map(|()| true)
                    };
                    match result {
                        Ok(estado) => {
                            tray.autostart_on = estado;
                            println!(
                                "Twinpad: autoarranque {} (CR-061, SPEC-010 W5).",
                                if estado { "activado" } else { "desactivado" }
                            );
                        }
                        Err(error) => eprintln!(
                            "Twinpad: no se pudo cambiar el autoarranque: {error} (E5)."
                        ),
                    }
                }),
                ..Default::default()
            }
            .into(),
            MenuItem::Separator,
            // W4: parada con gracia (mismo flag de `webui /stop`).
            StandardItem {
                label: "Detener servidor".into(),
                icon_name: "process-stop".into(),
                activate: Box::new(|tray: &mut Self| {
                    println!("Twinpad: parada solicitada desde la bandeja (CR-061, W4).");
                    tray.actions.shutdown.store(true, Ordering::Release);
                }),
                ..Default::default()
            }
            .into(),
        ]
    }
}

/// Navegadores con modo app (flag `--app`), en orden de prelación. Es el
/// motor del flag `--app` (Chrome, Edge, Brave, Chromium): no se espera de
/// Firefox (sin modo app) y le toca el fallback `xdg-open`.
const APP_MODE_BROWSERS: &[&str] = &[
    "google-chrome-stable",
    "google-chrome",
    "chromium",
    "chromium-browser",
    "msedge",
    "brave",
];

/// argv de la ventana web del sistema (CR-063, W3): `--app=URL` abre una
/// ventana propia sin barra de direcciones ni pestañas sobre el mismo
/// HTML de loopback; sin flags extra para no perturbar el perfil del
/// usuario.
fn build_app_window_command(program: &str, url: &str) -> std::process::Command {
    let mut command = std::process::Command::new(program);
    command.arg(format!("--app={url}"));
    command
}

/// Acción «Abrir interfaz de gestión» (SPEC-010 W3, CR-063): la página se
/// abre como ventana propia del escritorio. Desapegada (`spawn()` sin
/// `wait()`): el callback de los ash tray no queda esperando al cierre de
/// la ventana. Si ningún navegador de modo app está disponible (E4),
/// fallback a `xdg-open` (comportamiento CR-061).
fn open_app_window(port: u16) {
    let url = format!("http://127.0.0.1:{port}/");
    for program in APP_MODE_BROWSERS {
        let spawn = build_app_window_command(program, &url).spawn();
        match spawn {
            Ok(_) => {
                println!("Twinpad: ventana de gestión abierta en {url} ({program}; CR-063 W3).");
                return;
            }
            Err(err) if err.kind() == std::io::ErrorKind::NotFound => {
                continue;
            }
            Err(_) => break,
        }
    }
    match std::process::Command::new("xdg-open").arg(&url).status() {
        Ok(_) => {
            println!("Twinpad: interfaz local abierta en {url} (fallback navegador; CR-063 W3).")
        }
        Err(err) => {
            eprintln!("Twinpad: no se pudo abrir la ventana web ({err}); usa {url} (E4).")
        }
    }
}

/// Spawn fail-soft del tray (W7). En entornos sin bandeja devuelve el error
/// para que quien llama loguee y siga vivo; flag y HTTP quedan intactos.
pub fn spawn_tray(actions: TrayActions) -> Result<(), String> {
    let autostart_on = crate::autostart::is_installed();
    let tray = TwinpadTray {
        actions,
        autostart_on,
    };
    use ksni::blocking::TrayMethods;
    match tray.spawn() {
        Ok(_handle) => {
            println!("Twinpad: bandeja activa (CR-061, SPEC-010 W1).");
            Ok(())
        }
        Err(error) => Err(format!("{error}")),
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;

    // TEST-0141 — verifies CR-063 (SPEC-010 W3): la ventana app es el
    // programa candidato con el único argumento `--app=URL` (sin flags
    // extra de perfil).
    #[test]
    fn app_window_command_is_exactly_program_and_app_flag() {
        let url = "http://127.0.0.1:8430/";
        let command = build_app_window_command("chromium", url);
        assert_eq!(command.get_program(), "chromium");
        let args: Vec<String> = command
            .get_args()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect();
        assert_eq!(args, vec![format!("--app={url}")]);
    }

    // TEST-0141 — verifies CR-063 (SPEC-010 W3): la canasta de candidatos es
    // la conocida de motores Blink; ningún fallback prematuro de Firefox.
    #[test]
    fn app_window_candidates_are_blink_app_mode_engines() {
        assert!(APP_MODE_BROWSERS.contains(&"google-chrome"));
        assert!(APP_MODE_BROWSERS.contains(&"msedge"));
        assert!(APP_MODE_BROWSERS.contains(&"chromium"));
        assert!(
            !APP_MODE_BROWSERS
                .iter()
                .any(|name| name.contains("firefox"))
        );
    }
}
