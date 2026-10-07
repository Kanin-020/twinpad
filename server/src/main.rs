//! Servidor PC — adaptador ejecutable (REQ-001, REQ-002; DES-001 §12).
//!
//! Listener TCP en la LAN con el registro de tokens (R2/R3/R6/R7) y el
//! Handshake de SPEC-001 sobre TCP real. Muestra el QR vigente como URI en
//! stdout y lo regenera automáticamente al expirar (R7). La renderización
//! gráfica del QR y la persistencia cifrada de sesiones (ARCH §5) son
//! iteraciones posteriores.
//!
//! DES-001 §11: la entropía criptográfica y el reloj del host se inyectan
//! aquí; el resto del programa sigue siendo determinista y testeable.

use std::net::TcpListener;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use twinpad_server::session::{SessionDeps, SessionOutcome, run_handshake};
use twinpad_server::tokens::{Clock, RandomBytes, TokenStore};

/// Puerto TCP del servidor (REQ-001; dentro del rango 1024–65535 de §4.1).
const DEFAULT_PORT: u16 = 8420;

/// Fuentes reales de entropía y reloj (ENG §5 P11: los efectos viven aquí).
struct OsRandomBytes;
impl RandomBytes for OsRandomBytes {
    fn fill(&self, buf: &mut [u8]) {
        getrandom::fill(buf).unwrap_or_else(|e| os_entropy_unavailable(&e));
    }
}

struct SystemClock;
impl Clock for SystemClock {
    fn now_ms(&self) -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0)
    }
}

/// Sin entropía del SO no hay tokens seguros: el servidor no puede arrancar.
fn os_entropy_unavailable(e: &getrandom::Error) -> ! {
    eprintln!("Twinpad: entropía del SO no disponible ({e}); abortando.");
    std::process::exit(1);
}

fn main() {
    let port: u16 = std::env::args()
        .nth(1)
        .and_then(|a| a.parse().ok())
        .unwrap_or(DEFAULT_PORT);

    let Ok(listener) = TcpListener::bind(("0.0.0.0", port)) else {
        eprintln!("Twinpad: no se pudo abrir el puerto {port}; ¿está en uso?");
        std::process::exit(1);
    };
    let addr = local_addr_for_qr();
    let store = Arc::new(TokenStore::new(
        OsRandomBytes,
        SystemClock,
        addr.clone(),
        port,
    ));
    let registry = Arc::new(twinpad_server::registry::SessionRegistry::new());

    println!("Twinpad Servidor PC — escuchando en {addr}:{port}");
    println!("Escanea este QR con la app Twinpad:");
    println!();
    let current = store.ensure_fresh_qr();
    match twinpad_server::qr_render::render_terminal(&current.qr_uri) {
        Ok(qr_text) => println!("{qr_text}"),
        // El URI canónico de §4.1 siempre cabe; si no, mostramos solo el URI.
        Err(_) => println!("(QR no renderizable) {}", current.qr_uri),
    }
    println!("URI (por si necesitas copiarlo): {}", current.qr_uri);
    println!("El QR se regenera automáticamente cada 300 s si nadie lo escanea (R7).");

    for stream in listener.incoming() {
        let Ok(stream) = stream else { continue };
        // R2: cada conexión usa el registro vigente; el consumo lo arbitra
        // el almacén con exclusión mutua (DES-001 §10).
        let record = store.ensure_fresh_qr().record;
        let token_for_thread = record.token;
        let store_ref = Arc::clone(&store);
        let registry_ref = Arc::clone(&registry);
        std::thread::spawn(move || {
            let mut deps = SessionDeps {
                now_ms: &mut || SystemClock.now_ms(),
                next_nonce: &mut || {
                    let mut bytes = [0u8; 32];
                    OsRandomBytes.fill(&mut bytes);
                    twinpad_core::Nonce(bytes)
                },
                next_session_id: &mut || {
                    let mut bytes = [0u8; 16];
                    OsRandomBytes.fill(&mut bytes);
                    twinpad_core::SessionId(bytes)
                },
            };
            let mut stream = stream;
            match run_handshake(&mut stream, record, &mut deps) {
                SessionOutcome::Established {
                    session_id,
                    session_key,
                } => {
                    store_ref.mark_consumed(&token_for_thread); // R2, §14
                    // Conexión persistente (SPEC-001 §8.2): el server retiene
                    // un duplicado del stream para enviarle a la app; la
                    // conexión no se cierra tras el pair_ack.
                    if let Ok(held) = stream.try_clone() {
                        registry_ref.insert(
                            twinpad_server::registry::ActiveSession {
                                session_id,
                                session_id_b64: twinpad_core::message::b64_16(&session_id.0),
                            },
                            held,
                        );
                        println!(
                            "Vinculación exitosa: session_id={} — sesión activa ({} en total)",
                            twinpad_core::message::b64_16(&session_id.0),
                            registry_ref.len(),
                        );
                    }
                    let _ = session_key; // se usará por el canal cifrado (futuro)
                    // El hilo retiene `stream` abierto (lectura keepalive del
                    // cliente; EOF cae aquí sin cortar a otros).
                    use std::io::Read;
                    let mut buf = [0u8; 64];
                    loop {
                        match stream.read(&mut buf) {
                            Ok(0) | Err(_) => {
                                // El cliente cerró: liberar la sesión.
                                registry_ref.remove(&session_id);
                                println!("Sesión finalizada.");
                                break;
                            }
                            Ok(_) => {} // tráfico de sesión posterior (futuro)
                        }
                    }
                }
                SessionOutcome::Rejected(e) => {
                    println!("Vinculación rechazada ({}).", e.code());
                }
            }
        });
    }
}

/// Dirección LAN del host para el QR (§4.1): la primera IP privada no-loopback.
fn local_addr_for_qr() -> String {
    std::net::UdpSocket::bind("0.0.0.0:0")
        .and_then(|s| {
            s.connect("192.168.255.255:9")?;
            Ok(s.local_addr()?.ip().to_string())
        })
        .unwrap_or_else(|_| "127.0.0.1".to_string())
}
