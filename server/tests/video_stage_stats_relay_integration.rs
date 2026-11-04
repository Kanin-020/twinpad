//! Test de integración del peaje CR-075 v2 (REQ-006/SPEC-006; test-strategy
//! §2: el protocolo no se mockea): un Plugin crudo por TCP real anuncia un
//! juego y envía LA LÍNEA EXACTA que emite Cemu; el registro del server
//! (GameRegistry) debe retenerla verbatim para el drenaje por sesión a las
//! 5 s. Un par de resolución incompleto NO se registra (validación core)
//! y la conexión del plugin sigue viva.

// ENG §6: unwrap/expect/panic permitidos solo en tests (este crate es todo test).
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::io::Write;
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;
use std::time::Duration;

use twinpad_core::error::PairError;
use twinpad_core::game::{GameInfo, GameMsg};
use twinpad_server::games::{GameRegistry, Publisher, run_ipc_listener};

fn spawn_ipc_server(registry: Arc<GameRegistry>) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind IPC loopback");
    let addr = listener.local_addr().unwrap().to_string();
    let publish: Arc<Publisher> = Arc::new(|_| {});
    std::thread::spawn(move || run_ipc_listener(listener, registry, publish));
    addr
}

/// Poll hasta que el predicado se cumpla (los hilos del listener son reales).
/// Basado en iteraciones: sin `Instant` (`FnMut` para slots mutables del
/// caller: el pop del registro es one-shot, se conserva en el caller).
fn wait_for(max_polls: usize, mut until: impl FnMut() -> bool) {
    let mut done = 0usize;
    for _ in 0..max_polls {
        if until() {
            done += 1;
            break;
        }
        std::thread::sleep(Duration::from_millis(10));
    }

    assert!(
        until(),
        "condición no cumplida tras {done} de {max_polls} sondeos"
    );
}

fn send_and_expect_stats(raw: &str, expected: &str) {
    let registry = Arc::new(GameRegistry::new());
    let addr = spawn_ipc_server(Arc::clone(&registry));

    let announce = GameMsg::GameAnnounce {
        game: GameInfo {
            game_id: "cemu".into(),
            game_name: "Cemu".into(),
            capabilities: vec!["second_screen".into(), "audio".into()],
        },
    }
    .encode()
    .expect("encode del anuncio");

    let mut plugin = TcpStream::connect(addr.as_str()).expect("plugin conecta al listener");
    plugin
        .write_all(announce.as_bytes())
        .and_then(|_| plugin.write_all(b"\n"))
        .and_then(|_| plugin.flush())
        .expect("plugin anuncia al listener");
    wait_for(2_000, || registry.len() == 1);

    plugin
        .write_all(raw.as_bytes())
        .and_then(|_| plugin.write_all(b"\n"))
        .and_then(|_| plugin.flush())
        .expect("plugin envía la línea de stats");
    // El pop es one-shot: retengo la primera línea obtenida sin volver a
    // consumir (el wait_for sondea sin veces más el registro).
    let mut seen: Option<String> = None;
    wait_for(200, || {
        if seen.is_none() {
            seen = registry.pop_video_stage_stats("cemu");
        }
        seen.as_deref()
            .map(|line| line == expected)
            .unwrap_or(false)
    });
    assert_eq!(seen.as_deref(), Some(expected));
    assert!(registry.pop_video_stage_stats("cemu").is_none());
    assert_eq!(
        registry.len(),
        1,
        "el juego sigue vivo tras la línea de stats"
    );
}

/// TEST-0155 — verifies REQ-006/SPEC-006 (CR-075 v2): la línea EXACTA del
/// Plugin (claves CR-075 incluidas) atraviesa el decode del server pese al
/// `deny_unknown_fields` del enum y queda retenida verbatim en el registro
/// para el drenaje por sesión.
#[test]
fn exact_cemu_stats_line_reaches_the_registry_verbatim() {
    let cemu_line = "{\"type\":\"video_stage_stats\",\"window_ms\":5000,\"hw\":true,\"in_w\":854,\"in_h\":480,\"out_w\":854,\"out_h\":480,\"stages\":[],\"events\":[]}";
    send_and_expect_stats(cemu_line, cemu_line);
}

/// TEST-0155 — variedad sin claves CR-075: la línea canónica CR-053 sigue
/// pasando intacta (compatibilidad con Plugin viejos).
#[test]
fn canonical_cr053_line_reaches_the_registry_verbatim() {
    let canonical =
        "{\"type\":\"video_stage_stats\",\"window_ms\":5000,\"stages\":[],\"events\":[]}";
    send_and_expect_stats(canonical, canonical);
}

/// TEST-0155 — variedad inválida: un par de resolución incompleto no se
/// registra y la conexión del plugin sigue viva (E4: descarte de línea).
#[test]
fn incomplete_resolution_pair_is_discarded_and_plugin_survives() {
    let registry = Arc::new(GameRegistry::new());
    let addr = spawn_ipc_server(Arc::clone(&registry));

    let announce = GameMsg::GameAnnounce {
        game: GameInfo {
            game_id: "cemu".into(),
            game_name: "Cemu".into(),
            capabilities: vec!["second_screen".into(), "audio".into()],
        },
    }
    .encode()
    .expect("encode del anuncio");

    let mut plugin = TcpStream::connect(addr.as_str()).expect("plugin conecta");
    plugin
        .write_all(announce.as_bytes())
        .and_then(|_| plugin.write_all(b"\n"))
        .and_then(|_| plugin.flush())
        .expect("plugin anuncia");
    wait_for(2_000, || registry.len() == 1);

    let bad = "{\"type\":\"video_stage_stats\",\"window_ms\":5000,\"in_w\":854,\"stages\":[],\"events\":[]}";
    plugin
        .write_all(bad.as_bytes())
        .and_then(|_| plugin.write_all(b"\n"))
        .and_then(|_| plugin.flush())
        .expect("plugin envía la línea inválida");

    // La validación del par la compruebo también a nivel core (frontera).
    assert_eq!(
        GameMsg::decode(bad).err().unwrap(),
        PairError::MalformedMessage
    );

    // Poll hasta que el listener procese la línea (200 ms de margen): no hay
    // registro (None).
    std::thread::sleep(Duration::from_millis(200));
    assert!(
        registry.pop_video_stage_stats("cemu").is_none(),
        "la línea con par incompleto no debía registrarse"
    );

    // La conexión sigue viva: una línea válida posterior sí se registra.
    let good = "{\"type\":\"video_stage_stats\",\"window_ms\":5000,\"hw\":false,\"in_w\":640,\"in_h\":360,\"out_w\":640,\"out_h\":360,\"stages\":[],\"events\":[]}";
    plugin
        .write_all(good.as_bytes())
        .and_then(|_| plugin.write_all(b"\n"))
        .and_then(|_| plugin.flush())
        .expect("plugin envía la línea válida");
    let mut seen: Option<String> = None;
    wait_for(200, || {
        if seen.is_none() {
            seen = registry.pop_video_stage_stats("cemu");
        }
        seen.as_deref().map(|line| line == good).unwrap_or(false)
    });
    assert_eq!(seen.as_deref(), Some(good));
}
