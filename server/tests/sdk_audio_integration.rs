//! TEST-039 — verifies SPEC-005/SPEC-006 PCM IPC acceptance, capability checks,
//! duplicate source rejection, chunk forwarding into the selected game's queue,
//! and cleanup when the audio IPC connection closes.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::io::Write;
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;
use std::time::{Duration, Instant};
use twinpad_core::game_audio::GameAudioMsg;
use twinpad_sdk::{GameConfig, TwinpadGame};
use twinpad_server::games::{GameRegistry, Publisher, run_audio_ipc_listener, run_ipc_listener};

fn spawn_game_listener(registry: Arc<GameRegistry>) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind game IPC");
    let address = listener.local_addr().unwrap().to_string();
    let publish: Arc<Publisher> = Arc::new(|_| {});
    std::thread::spawn(move || run_ipc_listener(listener, registry, publish));
    address
}

fn spawn_audio_listener(registry: Arc<GameRegistry>) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind audio IPC");
    let address = listener.local_addr().unwrap().to_string();
    std::thread::spawn(move || run_audio_ipc_listener(listener, registry));
    address
}

fn send_message(stream: &mut TcpStream, message: &GameAudioMsg) {
    let line = message.encode().expect("valid game audio message");
    stream.write_all(line.as_bytes()).unwrap();
    stream.write_all(b"\n").unwrap();
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

#[test]
fn audio_capability_accepts_one_source_and_forwards_pcm_until_disconnect() {
    let registry = Arc::new(GameRegistry::new());
    let game_address = spawn_game_listener(Arc::clone(&registry));
    let audio_address = spawn_audio_listener(Arc::clone(&registry));
    let config = GameConfig::new("audio_game", "Audio Game", vec!["audio".into()]).unwrap();
    let game = TwinpadGame::announce_on(game_address.as_str(), config).unwrap();
    assert!(wait_for(|| registry.list().len() == 1));

    let mut source = TcpStream::connect(audio_address.as_str()).unwrap();
    send_message(
        &mut source,
        &GameAudioMsg::Start {
            game_id: "audio_game".into(),
            rate: 48_000,
            channels: 2,
            sample_fmt: "s16le".into(),
        },
    );
    assert!(wait_for(|| registry.audio_active("audio_game")));

    let mut duplicate = TcpStream::connect(audio_address.as_str()).unwrap();
    send_message(
        &mut duplicate,
        &GameAudioMsg::Start {
            game_id: "audio_game".into(),
            rate: 48_000,
            channels: 2,
            sample_fmt: "s16le".into(),
        },
    );
    duplicate
        .set_read_timeout(Some(Duration::from_secs(1)))
        .unwrap();
    let mut byte = [0u8; 1];
    assert_eq!(std::io::Read::read(&mut duplicate, &mut byte).unwrap(), 0);

    use base64::Engine as _;
    send_message(
        &mut source,
        &GameAudioMsg::Chunk {
            game_id: "audio_game".into(),
            seq: 0,
            ts: 5,
            data: base64::engine::general_purpose::URL_SAFE_NO_PAD.encode([7u8; 16]),
        },
    );
    let forwarded = wait_for(|| registry.pop_audio("audio_game").is_some());
    assert!(forwarded, "valid source PCM should reach the game registry");

    drop(source);
    assert!(wait_for(|| !registry.audio_active("audio_game")));
    drop(game);
}

#[test]
fn audio_source_without_live_audio_capability_is_rejected() {
    let registry = Arc::new(GameRegistry::new());
    let game_address = spawn_game_listener(Arc::clone(&registry));
    let audio_address = spawn_audio_listener(Arc::clone(&registry));
    let config =
        GameConfig::new("silent_game", "Silent Game", vec!["second_screen".into()]).unwrap();
    let game = TwinpadGame::announce_on(game_address.as_str(), config).unwrap();
    assert!(wait_for(|| registry.list().len() == 1));

    let mut source = TcpStream::connect(audio_address.as_str()).unwrap();
    send_message(
        &mut source,
        &GameAudioMsg::Start {
            game_id: "silent_game".into(),
            rate: 48_000,
            channels: 2,
            sample_fmt: "s16le".into(),
        },
    );
    source
        .set_read_timeout(Some(Duration::from_secs(1)))
        .unwrap();
    let mut byte = [0u8; 1];
    assert_eq!(std::io::Read::read(&mut source, &mut byte).unwrap(), 0);
    assert!(!registry.audio_active("silent_game"));
    drop(game);
}
