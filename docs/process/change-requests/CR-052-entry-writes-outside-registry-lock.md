# Change Request (CR) — CR-052 — Twinpad

| | |
|---|---|
| ID | CR-052 |
| Title | Escrituras al Plugin fuera del lock global del registro: writer por entrada (`Arc<Mutex<TcpStream>>`) para que cámara/mic lentos no frenen inputs ni ops del registro |
| Type | Behavior change |
| Status | Applied |
| Author | Buffy (agente) |
| Created | 2026-10-31 |
| Decided | 2026-10-31 |
| Decided by | Product owner (solicitud directa del owner: «el siguiente cuello de botella») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | DES-004 |
| Sections | §7 Concurrency (fila de escritura IPC), Changelog |
| Current version → new version | `1.14.0 → 1.15.0` |

## 2. Requested change

**Current:**

```text
Cada envío server→plugin (input_report, video_config, camera_frame,
mic_chunk, keyboard_input, rumble, video_force_idr, snapshot) se hace
RETENIENDO el lock global `Mutex<BTreeMap<game, GameEntry>>`: el
`write_all` + `flush` de hasta ~1–4 MiB (frames de cámara/mic/teclado en
base64) bloquea dentro del guard del mapa. Si el socket del plugin
drena lento, la escritura bloqueante retiene TODO el registro: los
input_report de cualquier juego, `list()`, `register`/`unregister` y la
publicación del anuncio esperan detrás de un frame en cola.
```

**Proposed:**

```text
`GameEntry` guarda `writer: Arc<Mutex<TcpStream>>`. El envío toma el Arc
bajo el lock del mapa (casi gratuito), SUELTA el mapa y escribe bajo un
lock por entrada: el bloqueo de una escritura afecta solo a su propia
entrada; el registro (inputs de cualquier juego, listas, bajas,
anuncios) nunca espera detrás de un socket lento. El lock por entrada
preserva la atomicidad NDJSON (línea + '\n' + flush sin entrelazado).
```

## 3. Motivation

Item #5 del mapa de cuellos de botella revisado con el owner (2026-10-31),
post CR-048/049/051: todo el I/O de sesión (input, mic, cámara, audio)
comparte un `Mutex`; con audio a chunks de ~16 ms y frames de cámara por
33 ms, un plugin que drena lento congela además el forwarding de inputs —
el camino del «feel como gamepad». La escritura bloqueante dentro del
lock del mapa es el único punto donde un solo socket puede retrasar a
todos los hilos de sesión a la vez.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003/004/005/006 | no | contratos intactos | none |
| SPEC-006 | no | semántica por mensaje sin cambios | none |
| ARCH | no | sin cambio de fronteras | none |
| DES-004 | yes | §7 Concurrency (escritura IPC fuera del mapa), changelog 1.15.0 | update |
| TEST-0114 (nuevo) | yes | el registro no bloquea con una escritura tapada (SNDBUF 4 KiB, línea 8 MiB, peer sin drenar) | add row |
| Code | yes | `server/src/games.rs` (GameEntry, register, 6 send methods, write_line_to) | done esta iteración |
| Data / migrations | no | — | none |
| Integrations / contracts | no | sin cambio de wire | none |

## 5. Options considered

1. Writer `Arc<Mutex<TcpStream>>` por entrada + soltar el mapa antes de
   escribir (propuesto) — elimina el freeze global sin cambiar wire.
2. Do nothing — un plugin sin espacio en SNDBUF congela inputs de TODA
   la sesión hasta que drene.
3. Pool de hilos + cola sin bloqueo por entrada — más maquinaria para el
   mismo efecto; el lock por entrada ya acota el daño al par afectado.

## 6. Decision

- **Outcome:** Approved (owner: «el siguiente cuello de botella»).
- **Rationale:** mismo encuadre que CR-049 (el forwarder del Plugin nunca
  debe bloquear), aplicado al lado servidor del IPC.
- **Rollout:** mismo release; solo cambia el Servidor PC.

## 7. Execution checklist

- [x] DES-004 actualizado (1.15.0, changelog con CR-052)
- [x] Tests: TEST-0114 nuevo (registro no bloqueante con socket tapado)
- [x] `testing/traceability.md` fila nueva
- [x] `_index.md` del registro de CRs
- [x] Gates server verdes (cargo test + clippy -D warnings)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-31 | Buffy (agente) | Opened and applied en la misma iteración |
