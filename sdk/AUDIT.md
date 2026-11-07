# Auditoría del SDK — claridad y seguridad para devs y agentes de IA

Fecha: 2026-11-06 · Alcance: `sdk/` (superficie pública, docs y ergonomía).
Sin cambios de wire: lo que se envía por el IPC es idéntico byte a byte.

Motivo: la integración con Dusklight (port nativo de Twilight Princess, C++
sobre Aurora) obliga a un host **que no es Rust** a hablar el mismo protocolo
que Cemu. Esta auditoría responde a dos preguntas: ¿el SDK puede integrarse sin
leer el código del servidor? y ¿qué trampas tiene hoy para un humano o un agente
que lo use por primera vez?

## 1. Hallazgos que se corrigieron aquí

| # | Hallazgo | Riesgo | Corrección |
|---|---|---|---|
| 1 | `SdkError` solo implementaba `Debug`: no se podía usar `?` con `anyhow`/`Box<dyn Error>` ni imprimir un mensaje legible | Un dev escribe `unwrap()`, un agente copia eso y el fallo se vuelve un panic en producción | `Display` + `std::error::Error` (con `source()` para el `io::Error`) |
| 2 | Las capacidades eran `Vec<String>` con nombres mágicos (`"second_screen"`, `"audio"`, `"input_consumption"`) | Un typo (`"secondscreen"`) compila y falla recién en `new()`; un agente no tiene de dónde copiar el nombre exacto | Constantes `CAPABILITY_*` documentadas y referenciadas desde `GameConfig::new` |
| 3 | El contrato de hilos no estaba escrito: `push_frame` escribe **síncrono** en el socket, con el timeout de escritura de 10 s del anuncio todavía activo | Llamarlo desde el hilo de render congela el juego hasta 10 s si el servidor no drena; es la causa número uno de "Twinpad me traba el juego" | Rustdoc explícito en `push_frame` y en `start_audio_source`, y sección de hilos en `INTEGRATION.md` |
| 4 | `announce` bloquea hasta 500 ms (sonda de rechazo) y no lo decía | Un agente lo llama dentro de la inicialización del frame 1 y parece un cuelgue | Documentado en `announce`/`announce_on` |
| 5 | No había guía para hosts que no son Rust; cada uno reimplementa el wire | Cemu y Dusklight ya tienen su propia copia; sin contrato escrito la deriva es cuestión de tiempo | `INTEGRATION.md` §9: contrato mínimo para un host C/C++ y la lista de lo que **no** se debe replicar a mano |

## 2. Hallazgos abiertos (requieren decisión o SPEC, no se tocaron)

| # | Hallazgo | Por qué no se corrigió aquí |
|---|---|---|
| A | El wire tiene **tres** implementaciones independientes (SDK Rust, `integrations/cemu/src/Cafe/Twinpad/Core/TwinpadTransport`, `integrations/dusklight/src/dusk/twinpad/Core/TwinpadProtocol`) y ningún test de conformidad compartido | Unificar (C ABI sobre el SDK, o una librería C++ común) es una decisión de arquitectura: toca ADR-006/ADR-008 y el build de cada host. Se documenta el contrato mínimo mientras tanto |
| B | No hay reconexión: tras `AnnouncementRejected` hay que re-anunciar a mano | Es comportamiento de SPEC-006 §9 (re-anunciar), no del SDK; cambiarlo implica cambiar el contrato |
| C | `push_frame` codifica el frame completo en base64 y lo clona dentro del JSON en cada llamada | Aceptable para frames comprimidos (JPEG/H.264), caro para `rgb8` 1080p. El camino correcto (canal media binario, CR-054) hoy solo existe del lado Cemu |
| D | `start_audio_source` no tiene `stop` ni reporta descartes de la cola (drop-old silencioso) | Añadir API es cambio de superficie del SDK: requiere SPEC/CR |
| E | El hilo lector de `input_report` no se une en `Drop` (solo se hace `shutdown` del socket) | No hay fuga observable (el hilo muere con el EOF), pero `Drop` no espera: un host que libere el handle y cierre el proceso justo después puede perder el último reporte. Se documenta como límite |

## 3. Lo que ya estaba bien

- Validación local antes de conectar (`GameConfig::new`) con el mismo catálogo
  de capacidades que el servidor.
- Liveness por conexión: soltar el handle retira el juego; no hay fantasmas ni
  heartbeats que mantener.
- Cola de audio acotada con política de descarte explícita y cierre determinista
  (`AudioSourceHandle::drop` cancela y une ambos hilos).
- `announce_on` como costura de test: permite CI sin servidor real (lo usan
  `tests/sdk_frames_integration.rs` y `tests/sdk_audio_integration.rs`).
- Rustdoc de cada mensaje del wire en `core::game`, `core::input`,
  `core::stream`: el "qué" del protocolo está en un solo sitio.

## Verificación

| Gate | Resultado |
|---|---|
| `cargo fmt --check` | limpio |
| `cargo clippy -p twinpad-sdk --all-targets` | sin advertencias |
| `cargo test -p twinpad-sdk` | 3 unit tests, 0 fallos |

Los cambios son de superficie (trait impls, constantes, docs): ningún mensaje
del wire cambia y los tests de integración del servidor siguen igual.
