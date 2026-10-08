//! Juego de ejemplo con SDK Twinpad (SPEC-006, TEST-021).
//!
//! Anuncia un juego compatible al Servidor (IPC localhost, ADR-008) y se
//! mantiene vivo hasta Ctrl+C. Al terminar (o crashear), el juego deja de
//! estar disponible automáticamente — así se verifica el liveness §6.
//!
//! Uso: `cargo run -p twinpad-sdk --example demo_game [game_id] [nombre]`

use twinpad_sdk::{GameConfig, TwinpadGame};

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
    let game = match TwinpadGame::announce(config) {
        Ok(g) => g,
        Err(e) => {
            eprintln!("Anuncio rechazado o server no disponible ({e:?}).");
            std::process::exit(1);
        }
    };
    println!(
        "Anunciado: el juego figura como compatible disponible hasta que este proceso termine (Ctrl+C)."
    );

    // AC-2/AC-3 (frames e inputs vía SDK) llegan en iteraciones posteriores;
    // en v1 basta mantener la conexión viva (SPEC-006 §6/R2).
    loop {
        std::thread::sleep(std::time::Duration::from_secs(1));
        std::hint::black_box(&game);
    }
}
