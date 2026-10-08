//! Juego de ejemplo con SDK Twinpad (SPEC-006, TEST-021).
//!
//! Anuncia un juego compatible al Servidor (IPC localhost, ADR-008) y
//! publica frames de su Segunda pantalla con [`TwinpadGame::push_frame`]
//! (REQ-006 AC-2): el server los re-empaqueta como `video_frame` de
//! SPEC-003 hacia la app. Al terminar (Ctrl+C o crash), el juego deja de
//! estar disponible automáticamente — así se verifica el liveness §6.
//!
//! Uso: `cargo run -p twinpad-sdk --example demo_game [game_id] [nombre]`

use twinpad_core::stream::FrameFormat;
use twinpad_sdk::{GameConfig, TwinpadGame};

/// Parámetros del stream de demostración (v1 sin compresión, SPEC-003).
const DEMO_WIDTH: u16 = 320;
const DEMO_HEIGHT: u16 = 180;
const DEMO_FPS: u32 = 10;

/// Genera un Framebuffer RGB8 animado y determinista (ENG P10): gradiente +
/// barra vertical que recorre la imagen según `gfn` (mismo patrón que el
/// emisor demo del server, para que la vista sea reconocible).
fn demo_frame(gfn: u64, w: u16, h: u16) -> Vec<u8> {
    let mut buf = Vec::with_capacity(usize::from(w) * usize::from(h) * 3);
    let bar_x = (gfn % u64::from(w)) as u16;
    for y in 0..h {
        for x in 0..w {
            let r = ((u32::from(x) * 255) / u32::from(w)) as u8;
            let g = ((u32::from(y) * 255) / u32::from(h)) as u8;
            let b = if x == bar_x { 255 } else { 64 };
            buf.extend_from_slice(&[r, g, b]);
        }
    }
    buf
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let game_id = args.get(1).cloned().unwrap_or_else(|| "pacdemo".into());
    let game_name = args.get(2).cloned().unwrap_or_else(|| "Pac Demo".into());

    let config = GameConfig::new(
        game_id,
        game_name,
        vec!["second_screen".into(), "audio".into()],
    );
    let config = match config {
        Ok(c) => c,
        Err(e) => {
            eprintln!("Config inválida ({e:?}): revisa game_id/nombre (SPEC-006 §4).");
            std::process::exit(1);
        }
    };

    println!(
        "Juego '{}' ({}) anunciando al Servidor en 127.0.0.1:{}…",
        config.game_name,
        config.game_id,
        twinpad_sdk::DEFAULT_IPC_PORT
    );
    let mut game = match TwinpadGame::announce(config) {
        Ok(g) => g,
        Err(e) => {
            eprintln!("Anuncio rechazado o server no disponible ({e:?}).");
            std::process::exit(1);
        }
    };
    println!(
        "Anunciado: el juego figura como compatible disponible hasta que este proceso termine (Ctrl+C)."
    );

    // REQ-006 AC-2: publicar la Segunda pantalla por el canal IPC. El server
    // la re-empaqueta como stream de SPEC-003 hacia la app; publicamos cada
    // 1/fps. El frame_period mínimo del SO puede exceder 1/30 s: v1 demuestra
    // el pipeline con fps moderado.
    let period = std::time::Duration::from_millis(1_000 / u64::from(DEMO_FPS));
    let mut gfn: u64 = 0;
    loop {
        let frame = demo_frame(gfn, DEMO_WIDTH, DEMO_HEIGHT);
        match game.push_frame(DEMO_WIDTH, DEMO_HEIGHT, FrameFormat::Rgb8, &frame) {
            Ok(()) => {}
            Err(e) => {
                eprintln!(
                    "Conexión con el Servidor perdida ({e:?}); el juego deja de estar disponible."
                );
                std::process::exit(1);
            }
        }
        gfn += 1;
        std::thread::sleep(period);
        std::hint::black_box(&game);
    }
}
