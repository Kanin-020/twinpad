//! Adaptador del heartbeat de sesión del Servidor PC — SPEC-001 §4.3, CR-055
//! (DES-001 §3/§7). El emisor de `ping` (HB1) y la lectura con deadline (HB4)
//! viven aquí; la política pura está en `twinpad-core::heartbeat`.
//!
//! Semántica de fin: `HeartbeatRead::Ended` unifica puntual EOF/HB4
//! (§4.3 HB6: el comportamiento observable es el de un corte de conexión,
//! sin `pair_error` ni códigos E nuevos).

use std::io::{BufRead, BufReader, Write};
use std::net::TcpStream;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::{Duration, Instant};

use twinpad_core::heartbeat::{HEARTBEAT_INTERVAL_MS, PongLiveness};

/// Contador de pings emitidos de una sesión (HB3: todo `pong` aceptado debe
/// ser eco de un `ping` efectivamente enviado, ≤ último contador).
#[derive(Clone, Default)]
pub struct SentPings(Arc<AtomicU64>);

impl SentPings {
    pub fn load(&self) -> u64 {
        self.0.load(Ordering::Acquire)
    }
}

/// Envia `ping` cada [HEARTBEAT_INTERVAL_MS] mientras `stop` esté en alto y
/// la escritura al socket siga siendo posible. Devuelve el contador de pings
/// emitidos (`SentPings`) para la validación HB3 del lector.
///
/// `write_lock` es la exclusión por línea de la sesión (paridad video/audio:
/// un ping se escribe como unidad atómica, nunca intercalado a mitad de una
/// línea NDJSON grande — ENG P10).
pub fn spawn_ping_sender(
    session_alive: bool,
    socket: Option<TcpStream>,
    write_lock: Arc<std::sync::Mutex<()>>,
    stop: Arc<AtomicBool>,
    interval: Duration,
) -> (Option<std::thread::JoinHandle<()>>, SentPings) {
    let sent = SentPings(Arc::new(AtomicU64::new(0)));
    if !session_alive {
        return (None, sent);
    }
    let Some(mut socket) = socket else {
        return (None, sent);
    };
    let counter = sent.clone();
    let handle = std::thread::spawn(move || {
        loop {
            if stop.load(Ordering::Acquire) {
                break;
            }
            std::thread::sleep(interval);
            if stop.load(Ordering::Acquire) {
                break;
            }
            let t = counter.0.fetch_add(1, Ordering::AcqRel); // HB1: t=0,1,2…
            let payload =
                match twinpad_core::message::encode(&twinpad_core::message::HandshakeMsg::Ping {
                    t,
                }) {
                    Ok(line) => line,
                    // MalformedMessage no puede ocurrir con campos de dominio propio.
                    Err(_) => break,
                };
            // Unidad atómica bajo el lock de línea de la sesión (CR-055).
            let written = {
                let _guard = write_lock.lock().ok();
                write_all_line(&mut socket, &payload)
            };
            if !written {
                break; // el lector declarará el fin (EOF/HB4)
            }
        }
    });
    (Some(handle), sent)
}

/// Lectura de una línea del socket de control respetando el deadline HB4:
/// el timeout de lectura es el tiempo restante, así el tráfico legítimo
/// (input_report, video_config) NO refresca la vida — solo el `pong`
/// aceptado por HB3 (vía [SessionHeartbeat::on_pong]).
pub enum HeartbeatRead {
    /// Línea completa recibida (el caller la procesa).
    Line,
    /// EOF, error de E/S o deadline vencido (HB4): mismo fin que un corte.
    Ended,
}

pub struct SessionHeartbeat {
    origin: Instant,
    liveness: PongLiveness,
    pings: SentPings,
}

impl SessionHeartbeat {
    pub fn new(pings: SentPings) -> Self {
        Self {
            origin: Instant::now(),
            liveness: PongLiveness::new(0),
            pings,
        }
    }

    /// Variante con deadline inyectado (tests: values cortos).
    pub fn with_deadline(deadline_ms: u64, pings: SentPings) -> Self {
        Self {
            origin: Instant::now(),
            liveness: PongLiveness::with_deadline(deadline_ms, 0),
            pings,
        }
    }

    /// Lee una línea; `line` se limpia al entrar (contrato del caller de
    /// `BufRead::read_line` en `main.rs`).
    pub fn read_line(
        &mut self,
        reader: &mut BufReader<TcpStream>,
        line: &mut String,
    ) -> HeartbeatRead {
        line.clear();
        let remaining = self.liveness.remaining_ms(self.ms());
        if remaining == 0 {
            return HeartbeatRead::Ended;
        }
        let _ = reader
            .get_ref()
            .set_read_timeout(Some(Duration::from_millis(remaining)));
        match reader.read_line(line) {
            Ok(0) => HeartbeatRead::Ended,
            Err(_) => HeartbeatRead::Ended, // deadline (HB4) o corte: mismo fin (HB6)
            Ok(_) => HeartbeatRead::Line,
        }
    }

    /// HB3: valida y acepta el eco de un `pong` (fresco y ≤ último ping).
    /// Devuelve si el eco refrescó la vida; un eco inválido se descarta sin
    /// cerrar la sesión (§4.3).
    pub fn on_pong(&mut self, t: u64) -> bool {
        self.liveness.accept(t, self.pings.load(), self.ms())
    }

    fn ms(&self) -> u64 {
        self.origin.elapsed().as_millis() as u64
    }
}

fn write_all_line(socket: &mut TcpStream, line: &str) -> bool {
    socket
        .write_all(line.as_bytes())
        .and_then(|_| socket.write_all(b"\n"))
        .and_then(|_| socket.flush())
        .is_ok()
}

/// Intervalo de producción (HB1).
pub const fn production_ping_interval() -> Duration {
    Duration::from_millis(HEARTBEAT_INTERVAL_MS)
}

// TEST-0121 vive en `server/tests/heartbeat_session.rs` (integración con
// sockets loopback reales); la política pura se verifica en `core` (TEST-0120).
