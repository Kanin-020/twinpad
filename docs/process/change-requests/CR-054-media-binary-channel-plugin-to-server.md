# Change Request (CR) — CR-054 — Twinpad

| | |
|---|---|
| ID | CR-054 |
| Title | Canal media binario Plugin→Servidor: `game_frame` H.264 sin base64+JSON |
| Type | `New behavior` |
| Status | `Applied` |
| Author | Buffy (agente) |
| Created | 2026-10-31 |
| Decided | 2026-10-31 |
| Decided by | Owner (solicitud «ataca el siguiente cuello de botella»; el mapa de cuellos identificó el tramo base64+JSON del video Plugin→server) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `DES-004-cemu-adapter.md` (§7.6 nuevo, §12, changelog), `docs/testing/traceability.md` |
| Sections | DES-004 §7.6 (canal media binario), §12 (mapa de componentes) |
| Current version → new version | `DES-004 1.16.0 → 1.17.0` |

## 2. Requested change

**Current:**

```text
Cada AU H.264 del Plugin viaja al Servidor por el IPC NDJSON primario
(ADR-008) como una línea `game_frame` con `data` en base64url: el Plugin
codifica w×h bytes del anexo a ~1.33·w, reserva una cadena de ese tamaño,
la concatena con los campos JSON y el Servidor la re-decodifica. A 60 fps y
8 Mbps consume ~13 MB/s de generación+parse JSON en localhost solo para
producir los bytes originales; ese CPU y esa memoria compiten contra el
encode y estabilizan peor la cadencia de envío.
```

**Proposed:**

```text
Canal binario dedicado Plugin→Servidor en loopback, puerto 8426 (DES-004 §7.6;
8421 IPC primario, 8422 audio SDK, 8423 QR, 8424 hook E2E, 8425 media del
teléfono). El Plugin abre la conexión y ejecuta el handshake NDJSON de una
línea:

  {"type":"game_media_start","game_id":"cemu"}

A partir de ahí cada AU H.264 viaja como [header de 32 bytes little-endian]
[anexo H.264 tal cual], sin base64 ni JSON:

  magic u16 = 0x5457 ("TW") | gfn u64 | ts_capture_us u64 | idr u8 (0/1)
  | w u16 | h u16 | len u32 (1..=MAX_AU_BYTES) | fmt u8 = 1 (h264)
  | reservado u32 = 0

Contratos:
- El Servidor valida `game_media_start` con `GameMsg::GameMediaStart` (nueva
  variante CR-054) y exige juego vivo (`has_live_game`); si no lo está,
  cierra la conexión (§14: sin anuncio no hay contenido).
- Header inválido (magic ≠ 0x5457, fmt ≠ 1, idr ∉ {0,1}, dims fuera
  16–3840/16–2160, len 0 o > 4 MiB) cierra el canal.
- Ingesta idéntica a la rama H.264 NDJSON: `store_access_unit` +
  `MediaHub::publish` (misma IDR-on-join y gancho de CR-051).
- Sin liveness propio: la vida del juego sigue siendo la conexión primaria;
  al EOF binario no se des-registra nada.
- Ruta de envío en Cemu (CR-048): el AU desplazado a la cola ya no va
  serializado; la línea `game_frame` NDJSON (base64+JSON) se construye solo
  en el hilo de envío SI el canal binario no está vivo (fallback), o si el
  elemento es una línea pura (`video_stage_stats` de CR-053, que nunca viaja
  por el binario). `base64_json` pasa a medir solo el fallback real.
- El intento de `ConnectMedia` se repite en cada ciclo de reconexión del
  IPC. Server viejo → falla silenciosamente → queda el NDJSON (compatibilidad
  en ambos sentidos).
```

## 3. Motivation

El mapa de cuellos de botella post CR-048/051/053 identificó como siguiente
tramo el coste base64+JSON del plugin→server (el phone→server→phone ya era
binario desde CR-014). Además de CPU, el cambio saca de la cadena de encode
la reserva de ~1.33× el tamaño del AU y acota la latencia de la etapa
`ipc_send` (menos bytes por syscall y sin rodeo de re-decode en el server).

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004/005/006 | no | el contenido y campos de `game_frame` se conservan byte a byte | none |
| SPEC-003 | no | el tramo server↔phone no cambia | none |
| SPEC-006 | nota | handshake nuevo `game_media_start` es extensión del juego de mensajes del plugin (canal dedicado, no el IPC primario) | backlog: alta formal del mensaje en SPEC-006 |
| ARCH | no | sigue siendo Cemu app ↔ Servidor PC por loopback localhost; nueva conexión, no nueva frontera | none |
| DES-004 | yes | §7.6 (canal binario, layout del header y fallback), §12 fila, changelog 1.17.0 | update |
| TEST-0118 (nuevo) | yes | framing binario en core: roundtrip de header, catálogo de rechazos y handshake | add row |
| TEST-0119 (nuevo) | yes | ingest binaria end-to-end: registro+MediaHub, rechazo sin juego vivo, cierre por header inválido | add row |
| Code | yes | `core/src/game_media.rs` (nuevo), `core/src/game.rs` (`GameMediaStart`), `server/src/game_media.rs` (nuevo listener), `server/src/main.rs`, `server/src/lib.rs`, `TwinpadClient.{h,cpp}` | done esta iteración |
| Data / migrations | no | — | none |
| Integrations / contracts | no | peers viejos: Cemu sin canal media usa NDJSON; server sin listener ignora el puerto 8426 — ambos sentidos compatibles | none |

## 5. Options considered

1. **Canal binario dedicado + handshake NDJSON una línea (elegida)** — misma
   carga sin transformaciones, fallback NDJSON intacto, sin tocar el wire
   cifrado SPEC-003 ni el audio SDK.
2. Directivas JSON binarias (msgpack/CBOR) en el mismo canal primario —
   sigue pagando parse por línea y mezcla AU de ~26 KB con `input_report`
   pequeños en el mismo socket.
3. Solo optimizar base64 con bufwrite preasignado — anima el coste, no lo
   elimina (el server seguiría re-decodificando cada línea).

## 6. Decision

- **Outcome:** Applied (aprobada por el owner dentro del cuello de botella
  «siguiente»: implementar el tramo binario).
- **Rationale:** elimina codificación (plugin), transferencia (~25 % menos
  bytes) y decodificación (server) del camino del video, con fallback de
  compatibilidad y sin cambio en el tramo hacia el teléfono.
- **Rollout:** mismo release; combinaciones mixtas funcionan por el fallback.

## 7. Execution checklist

- [x] `core::game_media`: framing (header 32 B LE + VALID canónica) + `MediaIpcPort`=8426 + `GameMediaStart`
- [x] `core::game`: variante `GameMsg::GameMediaStart` con validación E4
- [x] Server: `game_media.rs` listener (handshake con timeout, juego vivo exigido, ingest = registrar+publish), listener en `main.rs`
- [x] Cemu: `ConnectMedia`/`CloseMedia`/`SendMediaFrame` con fallback NDJSON y reconexión por ciclo del IPC primario; `SendBytes` crudo (sin LF sobre el binario)
- [x] Tests nuevos con IDs (TEST-0118, TEST-0119)
- [x] Gates: `cargo test` + `clippy` verdes; `ninja CemuBin` compila
- [x] DES-004 actualizado (1.17.0), traceability e `_index.md`

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-31 | Buffy (agente) | Opened and applied en la misma iteración |
