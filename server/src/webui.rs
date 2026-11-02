//! Página HTTP de gestión del Servidor PC (SPEC-010 W2/W3/W4/W6/W8, CR-061).
//!
//! HTTP artesanal sobre `std::net` (sin framework web, DES-005 §1), servido
//! EXCLUSIVAMENTE en loopback (W6): el listener se ata a `127.0.0.1` y, por
//! defensa en profundidad, la conexión cuyo peer no sea loopback se dropea
//! antes de leer la petición. La página muestra el QR vigente como SVG, el
//! URI copiable, el estado del servidor (sesiones activas, juegos
//! compatibles) y las configs de video vigentes del encoder del Plugin; el
//! botón «Detener servidor» activa el flag compartido de parada (W4).

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

/// Cierre de petición HTTP: máximo de bytes que leemos por petición (la
/// página es un recurso propio, no un servicio abierto: acotado).
const MAX_REQUEST_BYTES: usize = 8 * 1024;

/// Puerto local de la página (SPEC-010: 8430 por defecto, configurable por
/// argumento desde `main.rs`; fuera de los puertos de sesión/IPC 8420–8426).
pub const PORT: u16 = 8430;

/// Inyección de estado para la página (los efectos viven en `main.rs`).
pub struct UiDeps {
    /// URI de vinculación VIGENTE al momento de la consulta (W8: fuerza la
    /// regeneración R7 si el token de 300 s venció).
    pub current_uri: Box<dyn Fn() -> String + Send + Sync>,
    /// Número de sesiones activas (W2: estado del servidor).
    pub session_count: Box<dyn Fn() -> usize + Send + Sync>,
    /// Catálogo de juegos compatibles, en orden (W2).
    pub games: Box<dyn Fn() -> Vec<String> + Send + Sync>,
    /// (ancho, alto, bitrate) vigentes del encoder del Plugin (W2).
    pub video_config: Box<dyn Fn() -> (u32, u32, u32) + Send + Sync>,
    /// Flag compartido de parada con gracia (W4).
    pub shutdown: Arc<AtomicBool>,
}

pub(crate) enum RequestOutcome {
    Served,
    StopRequested,
}

/// Bucle del hilo webui: una conexión por petición, corto y acotado. Nunca
/// aborta el proceso: un fallo por conexión solo descarta esa petición
/// (W7 fail-soft se extiende a toda la superficie local).
pub fn run_webui_listener(listener: TcpListener, deps: Arc<UiDeps>) {
    for stream in listener.incoming() {
        let Ok(mut stream) = stream else { continue };
        let deps = Arc::clone(&deps);
        std::thread::spawn(move || match handle_connection(&mut stream, &deps) {
            RequestOutcome::Served => {}
            RequestOutcome::StopRequested => {
                println!("Twinpad: parada solicitada desde la interfaz local (CR-061, W4).");
            }
        });
    }
}

/// Sirve UNA conexión; devuelve si la petición activó la parada. Las
/// conexiones desde peers no-loopback se dropean sin contestar (W6).
pub(crate) fn handle_connection(stream: &mut TcpStream, deps: &UiDeps) -> RequestOutcome {
    let loopback = std::net::IpAddr::from([127, 0, 0, 1]);
    if !stream
        .peer_addr()
        .map(|addr| addr.ip() == loopback)
        .unwrap_or(false)
    {
        // Sin contestar: la página no existe fuera del propio PC (W6).
        return RequestOutcome::Served;
    }
    let mut buf = [0u8; MAX_REQUEST_BYTES];
    let Ok(read) = stream.read(&mut buf) else {
        return RequestOutcome::Served;
    };
    let request = String::from_utf8_lossy(&buf[..read]);
    let Some(request_line) = request.lines().next() else {
        return RequestOutcome::Served;
    };
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or("");
    let path = parts.next().unwrap_or("");
    if method != "GET" {
        respond(stream, "405 Method Not Allowed", "text/plain", "Solo GET.");
        return RequestOutcome::Served;
    }
    let path = path.split('?').next().unwrap_or(path);
    match path {
        "/" => {
            let page = render_page(deps);
            respond(stream, "200 OK", "text/html; charset=utf-8", &page);
            RequestOutcome::Served
        }
        "/stop" => {
            // W4: el flag compartido se activa en el propio handler (única
            // vía de parada: página, tray y tests comparten el mismo flag).
            deps.shutdown.store(true, Ordering::Release);
            respond(
                stream,
                "200 OK",
                "text/plain; charset=utf-8",
                "Servidor Twinpad detenido. Ya no se aceptan nuevas sesiones.",
            );
            RequestOutcome::StopRequested
        }
        _ => {
            respond(stream, "404 Not Found", "text/plain", "Sin recurso.");
            RequestOutcome::Served
        }
    }
}

fn respond(stream: &mut TcpStream, status: &str, content_type: &str, body: &str) {
    let head = format!(
        "HTTP/1.1 {status}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    );
    let _ = stream.write_all(head.as_bytes());
    let _ = stream.write_all(body.as_bytes());
    let _ = stream.flush();
}

/// Compone la página (W2): QR SVG + URI copiable + estado + configs +
/// acción de parada. El QR y el URI provienen del MISMO token de la
/// respuesta (W8: `current_uri` se consulta UNA sola vez aquí).
pub fn render_page(deps: &UiDeps) -> String {
    let uri = (deps.current_uri)();
    let sessions = (deps.session_count)();
    let games = (deps.games)();
    let (width, height, bitrate) = (deps.video_config)();
    let qr_svg = qr_svg(&uri)
        .unwrap_or_else(|| "<p>(QR no renderizable; usa el URI copiable de abajo)</p>".to_string());
    let games_list = if games.is_empty() {
        "<li>— sin juegos compatibles ahora mismo —</li>".to_string()
    } else {
        games.iter().map(|id| format!("<li>{id}</li>")).collect()
    };
    format!(
        "<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\">\
<title>Twinpad Servidor PC</title>\
<style>body{{font-family:sans-serif;max-width:640px;margin:2rem auto;padding:0 1rem}}\
code,textarea{{font-family:monospace}}</style></head><body>\
<section><h1>Twinpad Servidor PC — activo</h1>\
<h2>QR de vinculación vigente</h2>{qr_svg}\
<h2>URI de vinculación (copiar/pegar)</h2>\
<textarea readonly rows=\"3\" style=\"width:100%\">{uri}</textarea>\
<p><button onclick=\"navigator.clipboard.writeText(document.querySelector('textarea').value)\">Copiar URI</button></p></section>\
<section><h2>Estado</h2>\
<p>Sesiones activas: {sessions}</p>\
<p>Juegos compatibles:</p><ul>{games}</ul>\
<p>Video del Plugin: {width}×{height} a {bitrate} kbps (CR-058: el degradador nunca supera este target).</p></section>\
<section><h2>Control</h2>\
<p><button onclick=\"location.href='/stop'\">Detener servidor</button></p>\
<p>La parada termina el proceso; las sesiones activas observan el fin por su canal de control (SPEC-001 §4.3 HB6).</p></section>\
</body></html>",
        qr_svg = qr_svg,
        uri = uri,
        sessions = sessions,
        games = games_list,
        width = width,
        height = height,
        bitrate = bitrate,
    )
}

/// QR de `uri` como `<svg>` (DES-005 §10: reutiliza la matriz de
/// `qr_render::encode_matrix`; sin dependencias de render nuevas).
fn qr_svg(uri: &str) -> Option<String> {
    let matrix = crate::qr_render::encode_matrix(uri).ok()?;
    let size = matrix.first().map(|row| row.len()).unwrap_or(0);
    if size == 0 {
        return None;
    }
    let cell = 4u32;
    let quiet = 4u32;
    let dim = (size as u32 + 2 * quiet) * cell;
    let mut rects = String::new();
    for (y, row) in matrix.iter().enumerate() {
        for (x, module) in row.chars().enumerate() {
            if module == '1' {
                rects.push_str(&format!(
                    "<rect x=\"{}\" y=\"{}\" width=\"{cell}\" height=\"{cell}\"/>",
                    (x as u32 + quiet) * cell,
                    (y as u32 + quiet) * cell,
                ));
            }
        }
    }
    Some(format!(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"{dim}\" height=\"{dim}\" \
viewBox=\"0 0 {dim} {dim}\" shape-rendering=\"crispEdges\">\
<rect width=\"{dim}\" height=\"{dim}\" fill=\"white\"/>{rects}</svg>",
    ))
}

#[cfg(test)]
// ENG §6: unwrap/expect permitidos solo en tests. Los helpers de test
// (respuestas HTTP completas) se compilan bajo cfg(test).
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
pub(crate) fn tail_once(listener: &TcpListener, deps: &UiDeps) -> RequestOutcome {
    let Ok((mut stream, _)) = listener.accept() else {
        return RequestOutcome::Served;
    };
    stream
        .set_read_timeout(Some(std::time::Duration::from_secs(2)))
        .ok();
    handle_connection(&mut stream, deps)
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
pub(crate) fn response_of(listener: &TcpListener, deps: &UiDeps, request: &str) -> String {
    let addr = listener.local_addr().unwrap();
    let sender = std::thread::spawn({
        let request = request.to_string();
        move || {
            let mut stream = TcpStream::connect(addr).unwrap();
            stream
                .set_read_timeout(Some(std::time::Duration::from_secs(2)))
                .ok();
            stream.write_all(request.as_bytes()).unwrap();
            let mut buf = Vec::new();
            let _ = stream.read_to_end(&mut buf);
            String::from_utf8_lossy(&buf).into_owned()
        }
    });
    let outcome = tail_once(listener, deps);
    let response = sender.join().unwrap();
    if request.starts_with("GET /stop") {
        assert!(response.contains("200 OK"));
        assert!(matches!(outcome, RequestOutcome::StopRequested));
    } else {
        assert!(matches!(outcome, RequestOutcome::Served));
    }
    response
}

#[cfg(test)]
pub(crate) fn test_deps(shutdown: Arc<AtomicBool>) -> UiDeps {
    UiDeps {
        current_uri: Box::new(|| "twinpad://v1#s=127.0.0.1&p=8420&t=eW8tMTE1YQ".to_string()),
        session_count: Box::new(|| 2),
        games: Box::new(|| vec!["cemu".to_string()]),
        video_config: Box::new(|| (1920, 1080, 30000)),
        shutdown,
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;

    fn loopback_listener() -> TcpListener {
        // W6: el listener SIEMPRE vive en loopback; en tests puerto efímero.
        TcpListener::bind(("127.0.0.1", 0)).expect("loopback libre")
    }

    /// TEST-0135 (CR-061, SPEC-010 W2/W6/W8): `GET /` en loopback responde
    /// 200 con el URI vigente EN la casilla copiable, el QR SVG del MISMO
    /// token, el conteo de sesiones, el catálogo de juegos y la
    /// resolución/bitrate del encoder.
    #[test]
    fn webui_page_renders_fresh_qr_uri_state_and_loops_back_only() {
        let listener = loopback_listener();
        let deps = test_deps(Arc::new(AtomicBool::new(false)));
        let response = response_of(
            &listener,
            &deps,
            "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
        );
        assert!(response.starts_with("HTTP/1.1 200 OK"));
        // W8: el MISMO token en la casilla copiable y asentado como QR.
        assert!(response.contains("<textarea readonly"));
        assert!(response.contains("&t=eW8tMTE1YQ"));
        assert!(response.contains("<svg xmlns="));
        assert!(response.contains("viewBox="));
        // W2c/d: estado del server y configs de video.
        assert!(response.contains("Sesiones activas: 2"));
        assert!(response.contains("cemu"));
        assert!(response.contains("1920"));
        assert!(response.contains("1080"));
        assert!(response.contains("30000"));
        // W4: acción de parada disponible en la página.
        assert!(response.contains("/stop"));
    }

    /// TEST-0136 (CR-061, SPEC-010 W4): `GET /stop` responde 200 y activa el
    /// flag compartido de parada con gracia.
    #[test]
    fn stop_request_sets_shutdown_flag_and_answers_gracefully() {
        let listener = loopback_listener();
        let shutdown = Arc::new(AtomicBool::new(false));
        let deps = test_deps(Arc::clone(&shutdown));
        let response = response_of(
            &listener,
            &deps,
            "GET /stop HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
        );
        assert!(response.starts_with("HTTP/1.1 200 OK"));
        assert!(shutdown.load(Ordering::Acquire));
    }
}
