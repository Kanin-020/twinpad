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
}

impl ksni::Tray for TwinpadTray {
    fn id(&self) -> String {
        "twinpad-server".to_string()
    }

    fn title(&self) -> String {
        "Twinpad Servidor PC — activo".to_string()
    }

    fn icon_pixmap(&self) -> Vec<ksni::Icon> {
        vec![brand_icon()]
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
                // `menu()` se reconstruye en cada GetLayout: el estado se lee
                // del desktop entry real, no de una copia que pueda quedar
                // obsoleta si se conmuta desde la página o el QAM.
                checked: crate::autostart::is_installed(),
                activate: Box::new(|_tray: &mut Self| {
                    let result = if crate::autostart::is_installed() {
                        crate::autostart::remove().map(|_| false)
                    } else {
                        crate::autostart::install().map(|()| true)
                    };
                    match result {
                        Ok(estado) => {
                            println!(
                                "Twinpad: autoarranque {} (CR-061, SPEC-010 W5).",
                                if estado { "activado" } else { "desactivado" }
                            );
                        }
                        Err(error) => {
                            eprintln!("Twinpad: no se pudo cambiar el autoarranque: {error} (E5).")
                        }
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

/// Lienzo (unidades del vector) del icono de la app Android.
const GLYPH_VIEWPORT: f32 = 1254.0;
/// Pivote y escala del glifo en `ic_launcher_foreground.xml`.
const GLYPH_CENTER: (f32, f32) = (627.0, 599.0);
const GLYPH_SCALE: f32 = 0.6;
/// Desplazamiento vertical del grupo del glifo en el vector Android.
const GLYPH_OFFSET_Y: f32 = 28.0;
/// Recorte al área visible del icono adaptativo (72dp de 108dp), que es la
/// que ve el usuario una vez aplicada la máscara del launcher.
const GLYPH_ZOOM: f32 = 1.5;
/// Contorno aplanado del trapecio izquierdo del glifo: los ocho tramos del
/// `pathData` de `ic_launcher_foreground.xml` (curvas Bézier cúbicas y
/// rectas) muestreados en una polilínea; el derecho es su espejo respecto a
/// `GLYPH_CENTER.0`.
fn glyph_outline() -> Vec<(f32, f32)> {
    const STEPS: u32 = 8;
    // Cada tramo es [x0, y0, c1x, c1y, c2x, c2y, x3, y3]: inicio, controles
    // de la curva y fin. En las rectas los controles coinciden con los
    // extremos, así que una misma fórmula cúbica sirve para curvas y rectas.
    const SEGMENTS: [[f32; 8]; 8] = [
        [530.0, 340.0, 565.0, 328.0, 601.0, 352.0, 601.0, 392.0],
        [601.0, 392.0, 601.0, 392.0, 601.0, 815.0, 601.0, 815.0],
        [601.0, 815.0, 601.0, 850.0, 575.0, 870.0, 540.0, 864.0],
        [540.0, 864.0, 540.0, 864.0, 312.0, 778.0, 312.0, 778.0],
        [312.0, 778.0, 285.0, 768.0, 271.0, 745.0, 271.0, 720.0],
        [271.0, 720.0, 271.0, 720.0, 271.0, 478.0, 271.0, 478.0],
        [271.0, 478.0, 271.0, 450.0, 288.0, 428.0, 312.0, 420.0],
        [312.0, 420.0, 312.0, 420.0, 530.0, 340.0, 530.0, 340.0],
    ];
    let mut outline = Vec::with_capacity((STEPS * SEGMENTS.len() as u32) as usize);
    for [x0, y0, c1x, c1y, c2x, c2y, x3, y3] in SEGMENTS {
        for step in 1..=STEPS {
            let t = step as f32 / STEPS as f32;
            let u = 1.0 - t;
            let cubic = |a: f32, b: f32, c: f32, d: f32| {
                u * u * u * a + 3.0 * u * u * t * b + 3.0 * u * t * t * c + t * t * t * d
            };
            outline.push((cubic(x0, c1x, c2x, x3), cubic(y0, c1y, c2y, y3)));
        }
    }
    outline
}

/// ¿El punto normalizado (0..1) cae dentro del lienzo redondeado del icono?
fn inside_icon_canvas(x: f32, y: f32) -> bool {
    const RADIUS: f32 = 0.22;
    let dx = x - x.clamp(RADIUS, 1.0 - RADIUS);
    let dy = y - y.clamp(RADIUS, 1.0 - RADIUS);
    dx * dx + dy * dy <= RADIUS * RADIUS
}

/// Prueba de interior por cruce de rayos (par-impar).
fn point_in_polygon(points: &[(f32, f32)], x: f32, y: f32) -> bool {
    let mut inside = false;
    let mut j = points.len() - 1;
    for i in 0..points.len() {
        let (xi, yi) = points[i];
        let (xj, yj) = points[j];
        if (yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi {
            inside = !inside;
        }
        j = i;
    }
    inside
}

/// ¿El punto normalizado (0..1) cae dentro de alguno de los dos trapecios?
fn inside_icon_glyph(outline: &[(f32, f32)], x: f32, y: f32) -> bool {
    let vx = ((x - 0.5) / GLYPH_ZOOM + 0.5) * GLYPH_VIEWPORT;
    let vy = ((y - 0.5) / GLYPH_ZOOM + 0.5) * GLYPH_VIEWPORT;
    let gx = (vx - GLYPH_CENTER.0) / GLYPH_SCALE + GLYPH_CENTER.0;
    let gy = (vy - GLYPH_OFFSET_Y - GLYPH_CENTER.1) / GLYPH_SCALE + GLYPH_CENTER.1;
    point_in_polygon(outline, gx, gy) || point_in_polygon(outline, GLYPH_VIEWPORT - gx, gy)
}

/// Icono de la bandeja: el icono de la app Android (dos trapecios enfrentados
/// en Signal Violet sobre el lienzo blanco redondeado del icono adaptativo),
/// rasterizado en código con supermuestreo 4x4 para bordes suaves. ARGB32 en
/// orden de bytes de red (SNI).
fn brand_icon() -> ksni::Icon {
    const SIZE: i32 = 64;
    const SAMPLES: i32 = 4;
    const VIOLET: (f32, f32, f32) = (0x70 as f32, 0x64 as f32, 0xF2 as f32);
    let samples = (SAMPLES * SAMPLES) as f32;
    let outline = glyph_outline();
    let mut data = Vec::with_capacity((SIZE * SIZE * 4) as usize);
    for y in 0..SIZE {
        for x in 0..SIZE {
            let (mut canvas, mut glyph) = (0.0f32, 0.0f32);
            for sy in 0..SAMPLES {
                for sx in 0..SAMPLES {
                    let px = (x as f32 + (sx as f32 + 0.5) / SAMPLES as f32) / SIZE as f32;
                    let py = (y as f32 + (sy as f32 + 0.5) / SAMPLES as f32) / SIZE as f32;
                    if inside_icon_canvas(px, py) {
                        canvas += 1.0;
                    }
                    if inside_icon_glyph(&outline, px, py) {
                        glyph += 1.0;
                    }
                }
            }
            let pixel: [u8; 4] = if canvas == 0.0 {
                [0, 0, 0, 0]
            } else {
                // Glifo compuesto sobre el lienzo blanco; el alfa sale de la
                // cobertura del lienzo redondeado.
                let blend = |channel: f32| -> u8 {
                    ((channel * glyph + 255.0 * (canvas - glyph)) / canvas).round() as u8
                };
                [
                    (canvas / samples * 255.0).round() as u8,
                    blend(VIOLET.0),
                    blend(VIOLET.1),
                    blend(VIOLET.2),
                ]
            };
            data.extend_from_slice(&pixel);
        }
    }
    ksni::Icon {
        width: SIZE,
        height: SIZE,
        data,
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
///
/// Seguridad: no hay shell (`Command` pasa cada argumento como argv). `program`
/// sale solo de las listas constantes de navegadores, y `url` siempre es
/// `http://127.0.0.1:<puerto>/` construida internamente; ningún dato externo
/// llega a este comando.
fn build_app_window_command(program: &str, url: &str) -> std::process::Command {
    let mut command = std::process::Command::new(program);
    command.arg(format!("--app={url}"));
    command
}

/// Aperturas fallback (CR-063 0.1.2): `xdg-open` (host normal) y, en
/// Toolbx/Flatpak —donde el navegador del escritorio vive FUERA del
/// sandbox— `flatpak-spawn --host` con `xdg-open` o `gio open`, que lanza
/// el comando en la sesión real del host. `.status()`: los helpers de
/// apertura devuelven rápido, así el canal puede decidir por resultado.
const FALLBACK_OPENERS: &[&[&str]] = &[
    &["xdg-open"],
    &["flatpak-spawn", "--host", "xdg-open"],
    &["flatpak-spawn", "--host", "gio", "open"],
];

/// webview del sistema (CR-063 0.2.0): Gtk.Window + WebKit2.WebView por
/// PyGObject — WebKitGTK 4.1 real, SIN chrome de navegador ni pestañas.
/// El script se pasa con `-c` y el URL llega por argv[1] (sin
/// interpolación en el código). reqs del HOST Fedora Workstation:
/// python3-gobject + webkit2gtk4.1 (preinstalados; sin dependencias).
const HOST_WEBVIEW_SCRIPT: &str = r#"import sys
import gi
gi.require_version("WebKit2", "4.1")
gi.require_version("Gtk", "3.0")
from gi.repository import WebKit2, Gtk

window = Gtk.Window(title="Twinpad Servidor PC")
window.set_default_size(880, 980)
window.maximize()
view = WebKit2.WebView()
view.load_uri(sys.argv[1])
window.add(view)
window.connect("destroy", Gtk.main_quit)
window.show_all()
Gtk.main()
"#;

/// argv de la fase webview: `flatpak-spawn --host python3 -c <script> URL`.
fn build_host_webview_command(url: &str) -> std::process::Command {
    let mut command = std::process::Command::new("flatpak-spawn");
    command.args(["--host", "python3", "-c", HOST_WEBVIEW_SCRIPT, url]);
    command
}

/// Lanza la ventana webview del sistema en el HOST (Toolbx/Flatpak: el
/// binario corre en el sandbox sin GTK/WebKit; el escritorio del host sí
/// los tiene). `.status()`: puede bloquear hasta cerrar la ventana — se
/// llama desde el hilo dedicado de la cascada.
fn launch_host_webview(url: &str) -> Result<std::process::ExitStatus, std::io::Error> {
    build_host_webview_command(url).status()
}

/// Acción «Abrir interfaz de gestión» (SPEC-010 W3, CR-063): la página se
/// abre como ventana del sistema, en cascada:
///
/// 0. WEBVIEW del sistema en el HOST: WebKitGTK real (`flatpak-spawn --host
///    python3` + PyGObject) — la ventana nativa que pide el owner (CR-063
///    0.2.0), sin navegador de por medio.
/// 1. LOCAL modo app: `--app=URL` del primer Blink en [`APP_MODE_BROWSERS`]
///    (spawn desapegado: el caller no espera al cierre).
/// 2. HOST modo app (Toolbx/Flatpak): `--app=URL` del Blink del host con
///    `flatpak-spawn --host`.
/// 3. HOST/LOCAL genérico: [`FALLBACK_OPENERS`] (`xdg-open`/`gio open`;
///    pestaña del navegador por defecto).
///
/// Fases 0/2/3 usan `.status()` (bloquean mientras da vida a la ventana),
/// así que toda la cascada corre en un hilo dedicado: el caller nunca
/// queda esperando.
pub fn open_app_window(port: u16) {
    let url = format!("http://127.0.0.1:{port}/");
    std::thread::spawn(move || open_app_window_cascade(&url));
}

fn open_app_window_cascade(url: &str) {
    // Fase 0: webview del sistema (WebKitGTK del host).
    if try_fallback(launch_host_webview(url)) {
        println!("Twinpad: ventana webview del sistema abierta en {url} (CR-063 0.2.0).");
        return;
    }
    // Fase 1: Blink local en modo app (spawn desapegado, no bloquea).
    for program in APP_MODE_BROWSERS {
        if build_app_window_command(program, url).spawn().is_ok() {
            println!(
                "Twinpad: ventana de gestión abierta en {url} ({program}; fallback navegador; CR-063 W3)."
            );
            return;
        }
    }
    // Fase 2: Blink del host en modo app.
    for program in APP_MODE_BROWSERS {
        let status = std::process::Command::new("flatpak-spawn")
            .args(["--host", program, &format!("--app={url}")])
            .status();
        if try_fallback(status) {
            println!(
                "Twinpad: ventana de gestión abierta en {url} (host {program}; fallback navegador; CR-063 W3)."
            );
            return;
        }
    }
    // Fase 3: aperturas genéricas (pestaña en el navegador por defecto).
    for opener in FALLBACK_OPENERS {
        let [bin, extra_args @ ..] = opener else {
            continue;
        };
        let status = std::process::Command::new(bin)
            .args(extra_args)
            .arg(url)
            .status();
        if try_fallback(status) {
            println!(
                "Twinpad: interfaz local abierta en {url} (fallback {extra}; CR-063 W3).",
                extra = extra_args.join(" ")
            );
            return;
        }
    }
    eprintln!("Twinpad: sin opener disponible; abre {url} manualmente (E4).");
}

/// Un intento de la cascada es exitoso con exit code 0; con otro exit code
/// o runtime falla, loguea E4 y la cascada prueba lo siguiente. NotFound
/// se descarta en silencio (SIN opener en ese sandbox).
fn try_fallback(status: Result<std::process::ExitStatus, std::io::Error>) -> bool {
    match status {
        Ok(code) if code.success() => true,
        Ok(code) => {
            eprintln!("Twinpad: opener salió con {code} (E4); prueba el siguiente de la cascada.");
            false
        }
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => false,
        Err(err) => {
            eprintln!("Twinpad: opener no se pudo ejecutar ({err}) (E4).");
            false
        }
    }
}

/// Spawn fail-soft del tray (W7). En entornos sin bandeja devuelve el error
/// para que quien llama loguee y siga vivo; flag y HTTP quedan intactos.
pub fn spawn_tray(actions: TrayActions) -> Result<(), String> {
    let tray = TwinpadTray { actions };
    use ksni::blocking::TrayMethods;
    match tray.spawn() {
        Ok(handle) => {
            // El Handle se conserva vivo lo que dura el proceso: es lo que
            // mantiene en pie el servicio de bandeja de `ksni`, y el icono
            // debe seguir ahí mientras el servidor corre (SPEC-010 W1).
            let _handle: &'static mut _ = Box::leak(Box::new(handle));
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

    // TEST-0142 — verifies CR-063 0.2.0 (SPEC-010 W3): el webview del
    // sistema es `flatpak-spawn --host python3 -c <script> URL` (el URL va
    // por argv[1] del script, sin interpolación; el script crea
    // Gtk.Window + WebKit2.WebView del host).
    #[test]
    fn host_webview_launch_is_flatpak_spawn_python_webkit() {
        let url = "http://127.0.0.1:8430/";
        let command = build_host_webview_command(url);
        assert_eq!(command.get_program(), "flatpak-spawn");
        let args: Vec<String> = command
            .get_args()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect();
        assert_eq!(args.len(), 5);
        assert_eq!(args[0], "--host");
        assert_eq!(args[1], "python3");
        assert_eq!(args[2], "-c");
        assert!(args[3].contains("WebKit2.WebView"));
        assert!(args[3].contains("load_uri(sys.argv[1])"));
        assert_eq!(args[4], url);
        // El script no interpola el URL: argv es su única vía de entrada.
        assert!(!args[3].contains(url));
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


