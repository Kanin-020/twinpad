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

/// UI estática inyectada en tiempo de compilación (CR-063 0.2.1): el markup
/// vive en `server/ui/index.html` y la hoja en `server/ui/styles.css`; los
/// tokens `__*__` de la plantilla los sustituye `render_page` con los datos
/// vivos de la consulta (sin `format!`: el CSS no tendría que escapar llaves).
const PAGE_TEMPLATE: &str = include_str!("../ui/index.html");
const PAGE_STYLE: &str = include_str!("../ui/styles.css");

/// Inyección de estado para la página (los efectos viven en `main.rs`).
pub struct UiDeps {
    /// URI de vinculación VIGENTE al momento de la consulta con los ms
    /// restantes de validez del token (W8/W9: `current_uri` se consulta
    /// UNA sola vez por petición de página, y una por `GET /qr`; CR-073
    /// reutiliza el ciclo R7: regeneración si el token de 300 s venció).
    pub current_uri: Box<dyn Fn() -> (String, u64) + Send + Sync>,
    /// Rotación a demanda del operador («Regenerar QR», CR-073 0.2.0):
    /// INVALIDA el token vigente y emite uno nuevo (tokens::rotate_qr);
    /// cada llamada es una rotación (el botón consume la primera).
    pub rotate_uri: Box<dyn Fn() -> (String, u64) + Send + Sync>,
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
        "/qr" => {
            // W9 (CR-073): vencimiento del QR vigente para el contador y
            // la regeneración sin recargar; misma frescura por consulta
            // que la página (W8). Sin SVG renderizable → 503 fail-soft.
            let (uri, ttl_ms) = (deps.current_uri)();
            match qr_payload(&uri, ttl_ms) {
                Some(body) => {
                    respond(stream, "200 OK", "application/json; charset=utf-8", &body);
                }
                None => {
                    respond(
                        stream,
                        "503 Service Unavailable",
                        "text/plain; charset=utf-8",
                        "QR no renderizable; se conserva el vigente en pantalla.",
                    );
                }
            }
            RequestOutcome::Served
        }
        "/qr/rotate" => {
            // W9 manual (CR-073 0.2.0): «Regenerar QR» ROTA el token —
            // invalida el vigente no consumido y emite uno nuevo de
            // inmediato (tokens::rotate_qr); el UI lo muestra SIN recargar.
            let (uri, ttl_ms) = (deps.rotate_uri)();
            match qr_payload(&uri, ttl_ms) {
                Some(body) => {
                    respond(stream, "200 OK", "application/json; charset=utf-8", &body);
                }
                None => {
                    respond(
                        stream,
                        "503 Service Unavailable",
                        "text/plain; charset=utf-8",
                        "QR no renderizable; se conserva el vigente en pantalla.",
                    );
                }
            }
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
/// respuesta (W8: `current_uri` se consulta UNA sola vez aquí). El
/// contador inicial (W9) se calcula server-side para renderizar sin JS.
pub fn render_page(deps: &UiDeps) -> String {
    let (uri, ttl_ms) = (deps.current_uri)();
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
    PAGE_TEMPLATE
        .replace("__PAGE_STYLE__", PAGE_STYLE)
        .replace("__QR_SVG__", &qr_svg)
        .replace("__URI__", &uri)
        .replace("__TTL_CLOCK__", &mmss(ttl_ms))
        .replace("__TTL_MS__", &ttl_ms.to_string())
        .replace("__SESSIONS__", &sessions.to_string())
        .replace("__GAMES__", &games_list)
        .replace("__WIDTH__", &width.to_string())
        .replace("__HEIGHT__", &height.to_string())
        .replace("__BITRATE__", &bitrate.to_string())
}

/// Carga del vencimiento (CR-073 W9): JSON con el URI vigente, los ms
/// restantes y el SVG del MISMO token (W8); el cliente sustituye QR y
/// casilla juntos, nunca desincronizados. `None` si el SVG no es
/// renderizable (→ 503 en la ruta).
fn qr_payload(uri: &str, ttl_ms: u64) -> Option<String> {
    let svg = qr_svg(uri)?;
    Some(format!(
        "{{\"uri\":{uri_json},\"ttl_ms\":{ttl_ms},\"svg\":{svg_json}}}",
        uri_json = json_string(uri),
        svg_json = json_string(&svg),
    ))
}

/// Escape JSON mínimo (ENG §6: sin dependencias nuevas): el SVG contiene
/// comillas y el URI puede contener caracteres reservados de JSON.
fn json_string(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for ch in s.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            ch if (ch as u32) < 0x20 => {
                out.push_str(&format!("\\u{:04x}", ch as u32));
            }
            ch => out.push(ch),
        }
    }
    out.push('"');
    out
}

/// Contador inicial en mm:ss (techo de segundos; `0…59999 ms` → «01:00»:
/// el siguiente tick de 500 ms del cliente muestra el restante real).
fn mmss(ms: u64) -> String {
    let seconds = ms.div_ceil(1000);
    format!("{:02}:{:02}", seconds / 60, seconds % 60)
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
        current_uri: Box::new(|| {
            // TTL fijo alto: el vencimiento real lo ejercita TokenStore
            // (TEST-009); aquí se ancla que viaja intacto por el wire.
            (
                "twinpad://v1#s=127.0.0.1&p=8420&t=eW8tMTE1YQ".to_string(),
                300_000,
            )
        }),
        session_count: Box::new(|| 2),
        games: Box::new(|| vec!["cemu".to_string()]),
        video_config: Box::new(|| (1920, 1080, 30000)),
        // Rotación simulada: cada llamada emite un token DISTINTO (la
        // semántica real de invalidación la ejercita TEST-0148).
        rotate_uri: Box::new({
            let calls = std::sync::atomic::AtomicU8::new(0);
            move || {
                let n = calls.fetch_add(1, Ordering::Relaxed);
                (
                    format!(
                        "twinpad://v1#s=127.0.0.1&p=8420&t=eW8tMTE1Y{}Q",
                        (b'A' + n) as char
                    ),
                    300_000,
                )
            }
        }),
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

    /// TEST-0146 (CR-073, SPEC-010 W8/W9): `GET /qr` en loopback responde
    /// 200 application/json con el URI vigente, ttl_ms del token y el SVG
    /// del MISMO token (W8: desincronizar QR y casilla es imposible —
    /// misma respuesta, una sola consulta).
    #[test]
    fn qr_renewal_endpoint_serves_fresh_uri_ttl_and_same_token_svg() {
        let listener = loopback_listener();
        let deps = test_deps(Arc::new(AtomicBool::new(false)));
        let response = response_of(
            &listener,
            &deps,
            "GET /qr HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
        );
        assert!(response.starts_with("HTTP/1.1 200 OK"));
        assert!(response.contains("application/json"));
        // W8: el MISMO token vigente en URI y SVG (mecanizado como JSON).
        assert!(response.contains("eW8tMTE1YQ"));
        // W9: ttl_ms del token en el rango R3 (0 < ttl ≤ 300_000, aquí el
        // fixture fijo) y el SVG válido de qr_svg (el escape JSON no
        // independiza los fragmentos anclados).
        assert!(response.contains("\"ttl_ms\":300000"));
        assert!(response.contains("viewBox"));
    }

    /// TEST-0147 (CR-073 0.2.0, SPEC-010 W9 manual): `GET /qr/rotate` en
    /// loopback responde 200 con el token ROTADO (distinto por llamada);
    /// el swap sin recarga y la invalidación real son TEST-0146 (ruta) y
    /// TEST-0148 (semántica `rotate_qr`).
    #[test]
    fn qr_rotate_endpoint_serves_a_new_token_per_call() {
        let listener = loopback_listener();
        let deps = test_deps(Arc::new(AtomicBool::new(false)));
        let response_of = |request: &str| response_of(&listener, &deps, request);
        let first = response_of("GET /qr/rotate HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
        let second = response_of("GET /qr/rotate HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
        assert!(first.starts_with("HTTP/1.1 200 OK"));
        assert!(second.starts_with("HTTP/1.1 200 OK"));
        // Cada llamada rota: tokens distintos en el JSON (W9 manual).
        assert!(first.contains("eW8tMTE1YAQ"));
        assert!(second.contains("eW8tMTE1YBQ"));
        assert_ne!(first, second);
    }
}
