//! Tests de integración del SDK sobre IPC TCP localhost real (ADR-008,
//! test-strategy §2/§10: el protocolo no se mockea). Cubren el aspecto AC-2
//! de TEST-021: el juego con SDK publica frames de Segunda pantalla vía
//! [`twinpad_sdk::TwinpadGame::push_frame`] y el Servidor los retiene como
//! fuente del Stream de SPEC-003.

// ENG §6: unwrap/expect/panic permitidos solo en tests (este crate es todo test).
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::net::TcpListener;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;
use twinpad_core::stream::FrameFormat;
use twinpad_server::games::{GameRegistry, run_ipc_listener};

use twinpad_sdk::GameConfig;

/// Lanza el listener IPC del server en un puerto libre y devuelve su dirección.
fn spawn_ipc_server(registry: Arc<GameRegistry>) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind IPC loopback");
    let addr = listener.local_addr().unwrap().to_string();
    let publish: Arc<twinpad_server::games::Publisher> = Arc::new(|_| {});
    std::thread::spawn(move || run_ipc_listener(listener, registry, publish));
    addr
}

// TEST-021 (AC-2) — verifies REQ-006/SPEC-006: el frame publicado vía SDK
// queda disponible en el server como fuente del stream de SPEC-003.
#[test]
fn published_frame_becomes_the_preferred_video_source() {
    let registry = Arc::new(GameRegistry::new());
    let addr = spawn_ipc_server(Arc::clone(&registry));

    let config = GameConfig::new("pacdemo", "Pac Demo", vec!["second_screen".into()])
        .expect("config válida");
    let mut game =
        twinpad_sdk::TwinpadGame::announce_on(addr.as_str(), config).expect("anuncio aceptado");
    assert_eq!(game.game_id(), "pacdemo");

    // El juego publica un frame (AC-2) tras dar tiempo al registro.
    std::thread::sleep(Duration::from_millis(100));
    let pixels = vec![7u8; 32 * 16 * 3];
    game.push_frame(32, 16, FrameFormat::Rgb8, &pixels)
        .expect("frame aceptado por el server");
    std::thread::sleep(Duration::from_millis(100));

    // El server lo retiene como fuente preferente del stream (SPEC-003).
    let (game_id, frame) = registry.preferred_source().expect("frame retenido");
    assert_eq!(game_id, "pacdemo");
    assert_eq!(frame.w, 32);
    assert_eq!(frame.h, 16);
    assert_eq!(frame.fmt, FrameFormat::Rgb8);
    assert_eq!(frame.data, pixels);
    // El SDK asigna `gfn` monotónico desde 1 (0 = "sin frames publicados").
    assert_eq!(frame.gfn, 1);

    // Y también por id (misma vista desde el emisor).
    let latest = registry.latest_frame("pacdemo").expect("frame por id");
    assert_eq!(latest.data, pixels);
}

// TEST-021 (AC-2/§14) — verifies REQ-006/SPEC-006: el frame muere con el
// juego; sin anuncio vivo no hay contenido (invariante §14) ni stream.
#[test]
fn frame_dies_with_the_game_process() {
    let registry = Arc::new(GameRegistry::new());
    let addr = spawn_ipc_server(Arc::clone(&registry));

    let config = GameConfig::new("pacdemo", "Pac Demo", vec!["second_screen".into()])
        .expect("config válida");
    let mut game =
        twinpad_sdk::TwinpadGame::announce_on(addr.as_str(), config).expect("anuncio aceptado");
    std::thread::sleep(Duration::from_millis(100));

    let pixels = vec![3u8; 16 * 16 * 3];
    game.push_frame(16, 16, FrameFormat::Rgb8, &pixels)
        .expect("frame aceptado");
    std::thread::sleep(Duration::from_millis(100));
    assert!(registry.preferred_source().is_some());

    // El proceso del juego termina (cualquier causa, §6): su socket cae.
    drop(game);
    std::thread::sleep(Duration::from_millis(200));

    // Poll hasta que el listener procese el EOF (loopback, debería ser al acto).
    let deadline = std::time::Instant::now() + Duration::from_secs(2);
    while registry.preferred_source().is_some() && std::time::Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(20));
    }
    assert!(
        registry.preferred_source().is_none(),
        "el frame sobrevivió al juego: rompe el invariante §14"
    );
    assert!(
        registry.list().is_empty(),
        "el juego sigue listado: fantasma"
    );
}

// TEST-021 (AC-2/§9) — verifies REQ-006/SPEC-006: un `game_frame` con data
// inconsistente se rechaza en la frontera (E4) y no corrompe el registro.
#[test]
fn frame_with_wrong_size_is_rejected_cleanly() {
    let registry = Arc::new(GameRegistry::new());
    let addr = spawn_ipc_server(Arc::clone(&registry));

    let config = GameConfig::new("pacdemo", "Pac Demo", vec!["second_screen".into()])
        .expect("config válida");
    let mut game =
        twinpad_sdk::TwinpadGame::announce_on(addr.as_str(), config).expect("anuncio aceptado");
    std::thread::sleep(Duration::from_millis(100));

    // El SDK valida primero (AC-2: error local, §9).
    let short = vec![1u8; 32 * 16 * 3 - 1];
    assert!(matches!(
        game.push_frame(32, 16, FrameFormat::Rgb8, &short),
        Err(twinpad_sdk::SdkError::InvalidConfig)
    ));
    // El juego sigue vivo y disponible.
    std::thread::sleep(Duration::from_millis(100));
    assert_eq!(registry.list().len(), 1);
    assert!(registry.preferred_source().is_none());
}

// TEST-021 (AC-2) — verifies REQ-006/SPEC-006: dos juegos vivos publican
// frames y la fuente preferente es determinista (primer game_id, §8.1).
#[test]
fn two_games_frames_source_is_deterministic() {
    let registry = Arc::new(GameRegistry::new());
    let addr = spawn_ipc_server(Arc::clone(&registry));

    let stop_a = Arc::new(AtomicBool::new(false));
    let stop_b = Arc::new(AtomicBool::new(false));

    let config_a =
        GameConfig::new("zeta", "Zeta", vec!["second_screen".into()]).expect("config válida");
    let mut game_a =
        twinpad_sdk::TwinpadGame::announce_on(addr.as_str(), config_a).expect("anuncio A aceptado");
    let config_b =
        GameConfig::new("alfa", "Alfa", vec!["second_screen".into()]).expect("config válida");
    let mut game_b =
        twinpad_sdk::TwinpadGame::announce_on(addr.as_str(), config_b).expect("anuncio B aceptado");

    // Los handles vivos mantienen las conexiones abiertas (ADR-008).
    std::thread::sleep(Duration::from_millis(150));
    assert_eq!(registry.list().len(), 2, "ambos juegos deben estar vivos");

    // Se publican frames en ambos; la fuente preferente es "alfa" (orden).
    game_a
        .push_frame(16, 16, FrameFormat::Rgb8, &vec![1u8; 16 * 16 * 3])
        .unwrap();
    game_b
        .push_frame(16, 16, FrameFormat::Rgb8, &vec![2u8; 16 * 16 * 3])
        .unwrap();
    std::thread::sleep(Duration::from_millis(150));

    let (game_id, frame) = registry.preferred_source().expect("fuente preferente");
    assert_eq!(game_id, "alfa", "el primer game_id vivo define la fuente");
    assert_eq!(frame.data[0], 2);

    stop_a.store(true, Ordering::SeqCst);
    stop_b.store(true, Ordering::SeqCst);
    drop(game_a);
    drop(game_b);
}
