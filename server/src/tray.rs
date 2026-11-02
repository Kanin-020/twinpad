//! Bandeja del sistema del Servidor PC (SPEC-010 W1/W3/W4/W5/W7, CR-061).
//!
//! Icono StatusNotifierItem vía `ksni` (decisión ADR-010). El spawn es
//! fail-soft (W7/E4): si el entorno no ofrece host de bandeja, el server
//! sigue vivo con stdout + página HTTP; aquí solo log. Las acciones del
//! menú solo tocan el flag de parada compartido con `webui` y la entrada
//! de autoarranque de `autostart.rs`.

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
            // W3: abrir la interfaz local en el navegador del propio PC.
            StandardItem {
                label: "Abrir interfaz de gestión".into(),
                icon_name: "web-browser".into(),
                activate: Box::new(|tray: &mut Self| {
                    open_in_browser(tray.actions.webui_port);
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

fn open_in_browser(port: u16) {
    let url = format!("http://127.0.0.1:{port}/");
    match std::process::Command::new("xdg-open").arg(&url).status() {
        Ok(_) => println!("Twinpad: interfaz local abierta en {url} (CR-061 W3)."),
        Err(err) => {
            eprintln!("Twinpad: no se pudo abrir el navegador ({err}); usa {url} (E4).")
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
