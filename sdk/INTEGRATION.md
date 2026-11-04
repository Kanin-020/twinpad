# INTEGRATION.md — Integrar Twinpad en un proyecto de juego

> Guía para agentes de IA y desarrolladores: cómo integrar el SDK Twinpad
> (`twinpad-sdk`, crate Rust) para que un juego corra en el PC host y su
> contenido aparezca en el teléfono como Segunda pantalla (REQ-006).
> Contrato funcional completo: `docs/specs/SPEC-006-plugin-sdk-detection.md`
> (wire §4, estados §6, flujo §7). Lectura previa recomendada:
> `docs/product/context.md` y `docs/architecture/architecture.md`.

## 1. Qué es Twinpad y qué papel juega el SDK

Twinpad convierte un teléfono Android en la Segunda pantalla + gamepad del
juego. Las piezas son (CTX §8):

| Pieza | Crate / código | Tu proyecto toca |
|---|---|---|
| Servidor PC | `server/` (`twinpad-server`) | No. Lo inicia la *integración host* (p. ej. Cemu) |
| Plugin/juego con SDK | `sdk/` (`twinpad-sdk`) | **Sí: esto es lo que integras** |
| Tipos/wire compartidos | `core/` (`twinpad-core`) | Solo como dependencia (el SDK ya la trae) |
| Aparato del teléfono | `android/` | No |

El SDK **no** administra procesos ni UI: anuncia el juego al Servidor por
IPC TCP localhost y expone tres canales: input (governado por la app),
frames de Segunda pantalla (`second_screen`) y audio (`audio`). No toca la
red/Wi-Fi ni la sesión Android — eso es frontera de ARCH §3: el SDK solo
habla con el Servidor PC vía `127.0.0.1`.

## 2. Requisitos previos

1. **Servidor PC activo** en el host, anunciándose con QR de vinculación
   (Cemu es la primera integración host; en pruebas puedes lanzar
   `twinpad-server` manualmente).
2. **Teléfono emparejado** (QR escaneado; SPEC-002).
3. Un proceso de juego en el mismo PC que use este SDK. No necesita grandes
   privilegios: solo TCP localhost (puertos **8421** anuncio/input y **8422**
   audio, ADR-008/SPEC-006).
4. Cargo (Rust stable; `rust-version.workspace` define el mínimo) y añadir
   la dependencia por path si el proyecto vive en este workspace:
   `twinpad-sdk = { path = "../sdk" }`.

## 3. Quickstart (código mínimo)

```rust
use twinpad_core::stream::FrameFormat;
use twinpad_sdk::{GameConfig, TwinpadGame};

// 1) Configura y valida la identidad (SPEC-006 §4). game_id: 1–64 chars
//    [a-z0-9_.-], único entre juegos vivos; nombre 1–128 chars.
let config = GameConfig::new(
    "mi_juego",                       // game_id
    "Mi Juego",                       // game_name
    vec!["second_screen".into(), "audio".into()],
)?;

// 2) Anuncia. Mientras el handle esté vivo, el juego figura como disponible.
//    Al soltarlo —o si el proceso muere— el juego desaparece automáticamente
//    (liveness §6; no hay fantasmas).
let mut game = TwinpadGame::announce(config)?;

// 3) Publica frames de Segunda pantalla (REQ-006 AC-2): datos crudos
//    fila-mayor, exactamente w*h*bpp para formatos sin comprimir.
let buf = vec![0u8; 320 * 180 * 3];
game.push_frame(320, 180, FrameFormat::Rgb8, &buf)?;

// 4) Consume input del teléfono (sondeo, nunca bloquea; último snapshot).
if let Some(input) = game.poll_input() {
    // input.buttons / sticks / touch; motion en input.gyro/accel (UART del
    // giroscopio: para terceros este es el canal principal del gyro).
}

// 5) Audio: fuente callback en hilo dedicado (solo con capability "audio").
game.start_audio_source(44_100, 2, || {
    // devuelve Some(pcm_s16le) por chunk o None para terminar
    None
})?;
```

Ejemplo completo ejecutable: `sdk/examples/demo_game.rs`:

```bash
cargo run -p twinpad-sdk --example demo_game mi_juego "Mi Juego"
```

El ejemplo anuncia un juego, publica un gradiente animado a 10 fps y se
retira al Ctrl+C (verificable en la lista de juegos disponibles de la app).

## 4. Contracto API (resumen normativo)

| Regla | Detalle |
|---|---|
| Liveness | La conexión viva **es** el anuncio. Drop/crash ⇒ el juego deja de estar disponible (SPEC-006 §6). |
| `push_frame` | `gfn` lo asigna el SDK (monotónico por conexión). H.264 (`FrameFormat::H264`) exige `push_h264_access_unit` con `ts_capture_us` monotónico e `idr` (CR-015). |
| Audio | PCM s16le, chunks ≤ 16 KiB, rate 8k–192k. Cola interna de 8 chunks: al llenarse descarta el más viejo y `push_frame` nunca se bloquea. |
| Input | `poll_input()` devuelve el último snapshot de `input_report` o `None`; sin cola histórica. |
| Errores | `SdkError::ServerUnreachable` (server caído), `SdkError::AnnouncementRejected` (server cerró la conexión: id duplicado o inválido), `SdkError::InvalidConfig` (violación de §4 antes de conectar). |
| Reanudación | Si el proceso muere, el juego desaparece; para proseguir el stream basta re-anunciar + re-seleccionar el juego en la app. |

## 5. Cómo probarlo sin app

Para integraciones automatizadas (CI/agentes), `TwinpadGame::announce_on`
acepta un endpoint explícito, así que puedes apuntar a un server de loopback:

```bash
# server en un puerto/test listener propio:
cargo run -p twinpad-sdk --example demo_game   # con el server local arriba
```

Los tests del propio SDK (`cargo test -p twinpad-sdk`) no requieren server.
La integración punta a punta vive en el workspace del server
(`tests/sdk_audio_integration.rs`, `tests/sdk_frames_integration.rs`).

## 6. Verificación post-integración (checklist del agente)

- [ ] `cargo fmt --check` y `cargo clippy --all-targets` limpios con la
      dependencia añadida.
- [ ] Juego aparece como *compatible disponible* en la app con el server
      corriendo (SPEC-006 §7 paso 1–2).
- [ ] Cerrar el juego lo retira de la lista (sin fantasma).
- [ ] Con `second_screen`: la app muestra los frames publicados.
- [ ] Con `audio`: el audio del juego suena por el teléfono.
- [ ] `poll_input()` refleja botones/sticks/touch del gamepad de la app.

## 7. Estructura de carpeta de una integración (referencia: Cemu)

Las integraciones viven en `integrations/<game_id>/` — cada una es el
**fork del motor de juego** (submódulo git si el upstream es externo, como
Cemu) con la capa Twinpad adentro. La estructura canónica es la que usa
Cemu (`integrations/cemu`):

```text
integrations/
├── cemu/                        # Fork del motor (submódulo) con la integración viva
│   └── src/Cafe/Twinpad/        # Capa Twinpad del plugin
│       ├── Core/                # Contrato compartido: TwinpadTypes, TwinpadTransport,
│       │                        # TwinpadVideoMetrics (wire, framing, métricas CR-053)
│       ├── Client/              # Máquina de estados y sus bucles: connection
│       │                        # (8421 input/NDJSON, 8422 audio, 8426 media),
│       │                        # InputLoop/FrameLoop/VideoSendLoop/AudioLoop, encoder
│       ├── Video/               # Pipeline de imagen: H264 (TwinpadH264), YUV,
│       │                        # decoder de cámara (TwinpadCameraDecoder)
│       ├── Channels/            # Dispositivos frontales por canal: micrófono,
│       │                        # teclado (TwinpadMicChannel, TwinpadKeyboardChannel)
│       └── Tests/               # Pruebas stand-alone del módulo (Base64Url,
│                                # GyroMonitor, Motion; binarios de CMake)
├── cemu-vcpkg-overlay-linux/    # Overlays de dependencias de construcción
│                                # del host (al lado del fork, no adentro)
└── dusklight/                   # Siguiente integración: misma filas de carpetas
```

Reglas de organización tomadas de Cemu (aplicables a cualquier motor):

| Carpeta | Qué va (y qué NO) |
|---|---|
| `Core/` | Tipos del wire y utilidades que **comparten varias** piezas del plugin (transport NDJSON/binary, métricas, constantes). Sin estado del juego. |
| `Client/` | Orquestación viva: con conexión de canales, la máquina de estados del plugin y los bucles de envío/recepción. Un archivo por responsabilidad (`TwinpadClientConnect.cpp`, `…Pipeline.cpp`, …). |
| `Video/` | Códec y conversión aislados de sus llamantes (un archivo .h + .cpp por pieza); sin sockets. |
| `Channels/` | Un adapter por canal frontal del teléfono (mic, keyboard…): su estado y su wire particular, encapsulados. |
| `Tests/` | Pruebas ejecutables in CMake del módulo (mismos gates del build del host según `workflow §8`). |

Para integrar un juego **sin motor embebido** (el caso del Quickstart §3),
la estructura se aplana: el adaptador es un crate Rust normal que depende
de `twinpad-sdk` y vive en su propio `integrations/<game_id>/`; las mismas
filas (contrato / cliente / video / canales / tests) aplican como módulos
internos del crate.

## 8. Límites conocidos (v1)

- Un solo `game_id` por proceso: re-anunciar con otro id en la misma
  conexión no está soportado (un juego = un anuncio).
- `TwinpadGame` no es `Sync`: úsalo desde un hilo o envuélvelo tú.
- Los frames solo viajan al teléfono si la sesión Android tiene el juego
  **seleccionado** (`game_select`); sin selección no hay fuente (SPEC-006 §4).
