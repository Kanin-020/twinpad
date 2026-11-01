//! TEST-0119 — verifies CR-054 (canal media binario del Plugin): handshake
//! `game_media_start` sobre el listener binario, ingestión de una access
//! unit H.264 (registro + MediaHub), rechazo sin juego vivo y cierre del
//! canal por header inválido.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;
use std::time::{Duration, Instant};
use twinpad_core::game::{GameInfo, GameMsg};
use twinpad_core::game_media::{encode_header, GameMediaFrame, MAX_AU_BYTES};
use twinpad_server::game_media::run_media_ipc_listener;
use twinpad_server::games::{GameRegistry, Publisher, run_ipc_listener};
use twinpad_server::media::MediaHub;


/// Listener IPC NDJSON + un juego vivo anunciarlo y sostener la conexión.
fn spawn_live_game(registry: Arc<GameRegistry>, game_id: &str) {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind game IPC");
    let address = listener.local_addr().unwrap().to_string();
    let publisher: Arc<Publisher> = Arc::new(|_| {});
    std::thread::spawn(move || run_ipc_listener(listener, registry, publisher));
    let mut stream = TcpStream::connect(address.as_str()).unwrap();
    let announce = GameMsg::GameAnnounce {
        game: GameInfo {
            game_id: game_id.to_owned(),
            game_name: "Test Game".into(),
            capabilities: vec![],
        },
    }
    .encode()
    .unwrap();
    stream.write_all(announce.as_bytes()).unwrap();
    stream.write_all(b"\n").unwrap();
    // La conexión viva ES el liveness (ADR-008): no cerrar el fd durante
    // el test. El hilo del server queda leyendo con EOF pendiente.
    std::mem::forget(stream);
}

fn spawn_media_listener(registry: Arc<GameRegistry>, hub: Option<Arc<MediaHub>>) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind media IPC");
    let address = listener.local_addr().unwrap().to_string();
    std::thread::spawn(move || run_media_ipc_listener(listener, registry, hub));
    address
}

fn wait_for(mut condition: impl FnMut() -> bool) -> bool {
    let deadline = Instant::now() + Duration::from_secs(2);
    while Instant::now() < deadline {
        if condition() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(5));
    }
    condition()
}

/// [header de 32 bytes][anexo] de un AU H.264 de juguete (CR-054).
fn binary_frame(gfn: u64, idr: bool) -> Vec<u8> {
    let frame = GameMediaFrame {
        gfn,
        ts_capture_us: 1_000 + gfn,
        idr,
        w: 1280,
        h: 720,
        annex_b: gfn.to_le_bytes().repeat(4),
    };
    let mut wire = encode_header(&frame).unwrap().to_vec();
    wire.extend_from_slice(&frame.annex_b);
    wire
}

fn open_channel(address: &str, game_id: &str) -> TcpStream {
    let mut stream = TcpStream::connect(address).unwrap();
    let start = GameMsg::GameMediaStart {
        game_id: game_id.to_owned(),
    }
    .encode()
    .unwrap();
    stream.write_all(start.as_bytes()).unwrap();
    stream.write_all(b"\n").unwrap();
    stream
}

fn hub_latest(hub: &MediaHub, game_id: &str) -> Option<u64> {
    // Retención: el hub solo guarda la última AU por juego (CR-051).
    hub.wait_for_forwardable(game_id, |_| true, Duration::from_millis(20))
        .map(|source| source.latest.fn_)
}

/// TEST-0119a — CR-054: handshake válido + frames binarios →
/// `latest_frame` del registro y publicación en MediaHub.
#[test]
fn binary_channel_ingests_h264_access_units() {
    let registry = Arc::new(GameRegistry::new());
    let hub = Arc::new(MediaHub::default());
    spawn_live_game(Arc::clone(&registry), "cemu");
    assert!(wait_for(|| registry.has_live_game("cemu")));

    let address = spawn_media_listener(Arc::clone(&registry), Some(Arc::clone(&hub)));
    let mut stream = open_channel(&address, "cemu");
    stream.write_all(&binary_frame(7, true)).unwrap();
    stream.write_all(&binary_frame(8, false)).unwrap();

    let stored = wait_for(|| matches!(registry.latest_frame("cemu").map(|f| f.gfn), Some(8)));
    assert!(stored, "el AU gfn=8 llega a GameRegistry");
    let frame = registry.latest_frame("cemu").unwrap();
    assert_eq!(frame.idr, Some(false));
    assert_eq!(frame.data.as_ref(), &8_u64.to_le_bytes().repeat(4));

    let published = wait_for(|| hub_latest(&hub, "cemu").is_some());
    assert!(published, "MediaHub publicó al menos una AU");
    // Retención del hub: solo la última AU por juego queda disponible.
    assert_eq!(hub_latest(&hub, "cemu"), Some(8));
}

/// TEST-0119b — CR-054: sin juego vivo, el listener cierra tras el
/// handshake y no ingesta nada (§14: sin anuncio no hay contenido).
#[test]
fn binary_channel_requires_live_announced_game() {
    let registry = Arc::new(GameRegistry::new());
    let hub = Arc::new(MediaHub::default());
    let address = spawn_media_listener(registry.clone(), Some(hub.clone()));

    let mut stream = open_channel(&address, "ghost");
    let mut eof_probe = [0_u8; 256];
    let closed =
        wait_for(|| stream.read(&mut eof_probe).map(|read| read == 0).unwrap_or(true));
    assert!(closed, "el server cierra la conexión sin juego vivo");
    assert!(!registry.has_live_game("ghost"));
    assert!(hub_latest(&hub, "ghost").is_none());
}

/// TEST-0119c — CR-054: un header inválido cierra el canal; el AU previo
/// válido sí quedó registrado y publicado.
#[test]
fn binary_channel_closes_on_invalid_header() {
    let registry = Arc::new(GameRegistry::new());
    let hub = Arc::new(MediaHub::default());
    spawn_live_game(Arc::clone(&registry), "cemu");
    assert!(wait_for(|| registry.has_live_game("cemu")));

    let address = spawn_media_listener(Arc::clone(&registry), Some(Arc::clone(&hub)));
    let mut stream = open_channel(&address, "cemu");
    stream.write_all(&binary_frame(1, true)).unwrap();

    // Header corrupto: una carga que excede el techo.
    let mut corrupt = binary_frame(2, false);
    corrupt[23..27].copy_from_slice(&(MAX_AU_BYTES as u32 + 1).to_le_bytes());
    stream.write_all(&corrupt).unwrap();

    let stored =
        wait_for(|| matches!(registry.latest_frame("cemu").map(|f| f.gfn), Some(1)));
    assert!(stored, "el AU gfn=1 es válido e ingresó");
    let published = wait_for(|| hub_latest(&hub, "cemu").is_some());
    assert!(published, "MediaHub publicó el AU gfn=1");
    assert_eq!(hub_latest(&hub, "cemu"), Some(1));

    // El canal se cierra tras el header inválido (EOF al leer).
    let mut eof_probe = [0_u8; 256];
    let closed =
        wait_for(|| stream.read(&mut eof_probe).map(|read| read == 0).unwrap_or(true));
    assert!(closed, "header inválido cierra el canal (CR-054)");
}
