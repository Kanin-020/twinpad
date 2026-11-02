# Report — Refactor del SDK (twinpad-sdk)

Fecha: 2026-11-02 · Alcance: solo `sdk/`, sin cambios de funcionalidad.

## Resumen

Mismo orden de trabajo que los refactors de Android y server: (1) limpieza de
typos, nombres y rustdoc, (2) SOLID/DRY, (3) barrido de código muerto. El
crate ya salía del refactor previo en muy buen estado (docs del API completo),
así que el alcance fue reducido: +9 / −12 líneas netas.

Estadísticas: 1 archivo modificado (`sdk/src/lib.rs`), `examples/demo_game.rs`
sin cambios (ya limpio y documentado).

## 1. Limpieza (typos, nombres, rustdoc)

- Rustdoc añadido a los tres tipos privados que lo carecían:
  - `AudioQueue` (política productor/consumidor y descarte del más viejo).
  - `AudioQueueState` (estado guardado por el mutex).
  - `AudioSourceHandle` (cancelación determinista de ambos hilos en `Drop`).
- Rustdoc para la función privada `read_input_snapshots` (semántica de
  snapshot del §8).
- Typos corregidos en los doc-comments recién escritos
  (`SPECD-006` → `SPEC-006`, mayúsculas tras punto).
- Un `use base64::Engine as _;` repetido en dos funciones se movió al bloque
  superior de imports del módulo.

## 2. SOLID / DRY (duplicados consolidados)

- Los 5 sitios que repetían el patrón
  `write_all(line.as_bytes()).and_then(|_| write_all(b"\n"))` (anuncio IPC
  §7, línea `audio_start` CR-003, chunk de audio, `audio_stop` y
  `game_frame` AC-2) se consolidaron en el helper privado
  `write_ipc_line(stream, line)`. Cada sitio conserva su tratamiento de
  error propio (`ServerUnreachable` vs `AnnouncementRejected`),
  sin alterar comportamiento.

## 3. Código muerto (funciones `pub` sin referencias)

Barrido de API pública contra todo el workspace (server, tests, ejemplos):

- `DEFAULT_IPC_PORT`, `DEFAULT_AUDIO_IPC_PORT`, `SdkError`, `GameConfig`,
  `TwinpadGame::announce` / `announce_on` / `game_id` / `poll_input` /
  `start_audio_source` / `push_frame` / `push_h264_access_unit` — todos con
  usos. Sin hallazgos de código muerto.
- Nota (fuera de alcance): `server/src/main.rs` declara un
  `DEFAULT_AUDIO_IPC_PORT` local duplicado del del SDK; no se consolidó porque
  cruzaría la frontera ARCH §3 (el server no debe depender del SDK en
  runtime, solo al revés).

## Verificación (gates)

| Gate | Resultado |
|---|---|
| `cargo fmt --check` (sdk) | OK |
| `cargo clippy --all-targets` (sdk) | 0 advertencias, 0 errores |
| `cargo test -p twinpad-sdk` | 3 unit + 0 doc-tests, 0 fallos |
| Integración server que usa el SDK | `sdk_audio_integration` (2) y `sdk_frames_integration` (4), 0 fallos |

## Riesgos y alcances

- Ningún cambio comopecto de wire: los mensajes encodeados y el orden de
  `write_all`+`\n` son idénticos (el helper solo deduplica la misma secuencia).
- Los tests de integración (anuncio, réplicas, audio, frames) rodean las
  rutas tocadas sin haberse modificado.
