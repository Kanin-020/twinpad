//! Canal media binario del Plugin (CR-054).
//!
//! Listener loopback adicional al IPC NDJSON (ADR-008): После del handshake
//! `game_media_start` el Plugin envía cada access unit H.264 como
//! `[header de 32 bytes][anexo]` (framing de `twinpad_core::game_media`)
//! sin base64url ni JSON. La ingestión es idéntica a la rama H.264 de
//! `handle_game_connection`: `store_access_unit` + `MediaHub::publish`.
//!
//! Contrato (DES-004 §7.6):
//! - El juego DEBE estar vivo (anuncio por el canal primario, ADR-008);
//!   si no lo está, la conexión se cierra tras el handshake.
//! - El binario no lleva liveness: la vida del juego sigue siendo la
//!   conexión primaria. Al EOF de este canal, nada se des-registra.
//! - Un frame con header inválido cierra el canal (el Plugin decide
//!   reabrirlo o volver al NDJSON).

use std::io::Read;
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;
use std::time::Duration;

use twinpad_core::game::GameMsg;
use twinpad_core::game_media::{
    decode_header, header_valid, MEDIA_FRAME_HEADER_BYTES,
};
use twinpad_core::stream::FrameFormat;

use crate::games::{GameFrame, GameRegistry};
use crate::media::MediaHub;

/// Timeout del handshake NDJSON que abre el canal (CR-054).
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);

/// Leer exactamente `buf.len()` bytes de un socket bloqueante (CR-054).
/// Devuelve `false` en EOF o error: en cualquier caso el canal muere.
fn read_exact_socket(stream: &mut TcpStream, buf: &mut [u8]) -> bool {
    let mut offset = 0;
    while offset < buf.len() {
        match stream.read(&mut buf[offset..]) {
            Ok(0) => return false,
            Ok(read) => offset += read,
            Err(_) => return false,
        }
    }
    true
}

/// Lee una línea NDJSON con timeout de handshake (CR-054). Al no haber
/// línea o timeout: `None`.
fn read_announce(stream: &mut TcpStream) -> Option<String> {
    stream.set_read_timeout(Some(HANDSHAKE_TIMEOUT)).ok()?;
    let mut line = Vec::with_capacity(128);
    loop {
        let mut byte = [0_u8; 1];
        match stream.read(&mut byte) {
            Ok(1) if byte[0] == b'\n' => return String::from_utf8(line).ok(),
            Ok(1) => line.push(byte[0]),
            Ok(_) => return None,
            Err(_) => return None,
        }
    }
}

/// Handshake: valida una línea `game_media_start` y devuelve el `game_id`.
fn handshake(stream: &mut TcpStream) -> Option<String> {
    let line = read_announce(stream)?;
    match GameMsg::decode(&line) {
        Ok(GameMsg::GameMediaStart { game_id }) => Some(game_id),
        _ => None,
    }
}

/// Ingesta de un frame binario validado: registra y publica (CR-054),
/// misma semántica que la rama H.264 NDJSON de `handle_game_connection`.
fn ingest(
    game_id: &str,
    registry: &GameRegistry,
    hub: Option<&Arc<MediaHub>>,
    frame: GameFrame,
) {
    if registry.store_access_unit(game_id, frame.clone()) {
        if let (Some(hub), Some(timestamp_us), Some(is_idr)) =
            (hub, frame.ts_capture_us, frame.idr)
        {
            hub.publish(crate::media::MediaSource {
                game_id: game_id.to_owned(),
                width: frame.w,
                height: frame.h,
                latest: crate::media::AccessUnit {
                    fn_: frame.gfn,
                    timestamp_us,
                    idr: is_idr,
                    annex_b: frame.data,
                },
            });
        }
    }
}

pub fn run_media_ipc_listener(
    listener: TcpListener,
    registry: Arc<GameRegistry>,
    media_hub: Option<Arc<MediaHub>>,
) {
    for stream in listener.incoming() {
        let Ok(stream) = stream else { continue };
        let registry = Arc::clone(&registry);
        let media_hub = media_hub.clone();
        std::thread::spawn(move || handle_media_connection(stream, registry, media_hub));
    }
}

/// Ciclo de vida de una conexión media binaria (CR-054): handshake →
/// header+payload por access unit → EOF/cierre. Al terminar, el estado
/// del juego (registro/liveness) queda intacto: eso lo gobierna la
/// conexión primaria NDJSON.
fn handle_media_connection(
    mut stream: TcpStream,
    registry: Arc<GameRegistry>,
    media_hub: Option<Arc<MediaHub>>,
) {
    let Some(game_id) = handshake(&mut stream) else {
        println!("Handshake del canal media binario inválido (CR-054); conexión cerrada.");
        return;
    };
    if !registry.has_live_game(&game_id) {
        println!(
            "Canal media binario de '{}' rechazado: sin anuncio vivo (§14).",
            game_id
        );
        return;
    }
    // Habilitado el handshake: todas las lecturas vuelven a bloqueantes.
    stream.set_read_timeout(None).ok();
    let hub = media_hub.as_ref();
    let mut header = [0_u8; MEDIA_FRAME_HEADER_BYTES];
    loop {
        if !read_exact_socket(&mut stream, &mut header) {
            return; // EOF o caída; el liveness es del canal primario
        }
        if !header_valid(&header) {
            println!(
                "Header binario inválido de '{}'; canal media cerrado (CR-054).",
                game_id
            );
            return;
        }
        let Some((gfn, ts_capture_us, idr, w, h, len)) = decode_header(&header) else {
            return;
        };
        if len == 0 {
            return;
        }
        // Lectura del payload con un solo buffer reutilizado (mismo patrón
        // que el staging del audio NDJSON). Al fallar, muere el canal.
        let mut annex_b = vec![0_u8; len];
        if !read_exact_socket(&mut stream, &mut annex_b) {
            return;
        }
        let frame = GameFrame {
            gfn,
            w,
            h,
            fmt: FrameFormat::H264,
            ts_capture_us: Some(ts_capture_us),
            idr: Some(idr),
            data: annex_b.into(),
        };
        ingest(&game_id, &registry, hub, frame);
    }
}
