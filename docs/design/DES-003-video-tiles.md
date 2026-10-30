# DES-003 — Realización de video H.264 cifrado (SPEC-003 / CR-014)

| | |
|---|---|
| ID | DES-003 |
| Title | Realización del Stream H.264 de Segunda pantalla |
| Version | 0.3.0 |
| Status | `Draft` |
| Owner | Eng |
| Realizes | SPEC-003 §4–§16 (CR-014) |
| Depends on | DES-001, DES-004, ADR-005 |
| Last updated | 2026-10-30 |

## 1. Overview

CR-014 reemplaza la ruta JPEG/tiles por una ruta H.264/AVC: Cemu produce AU Annex B con OpenH264 software; el servidor coordina selección, autentica el socket media Android, cifra/registra y retransmite sin decodificar; Android valida AES-GCM y entrega los access units a `MediaCodec` sobre `SurfaceView`. Pairing, lista/selección e inputs continúan por NDJSON en la conexión de control.

## 2. Fit with the architecture

- Components: Adaptador Cemu (captura/encode), Servidor PC (selección, autorización y relay) y App Twinpad Android (socket cifrado, decode hardware y presentación).
- La clave `session_key` se deriva durante Pairing y solo reside en memoria de Android/servidor; nunca se transmite a Cemu ni se registra.
- El canal media está ligado a la vida de la conexión Pairing; cerrar Pairing cierra todos sus sockets media.
- No hay fallback a JPEG ni interop con v1.3.0; despliegue coordinado.

## 3. Domain model

- `EncodedAccessUnit`: `fn:u64`, `ts_capture_us:u64`, flag IDR y bytes H.264 Annex B.
- `MediaChannel`: sesión Pairing, socket TCP 8425, claves y counters únicos para esa conexión.
- `DecoderSurface`: MediaCodec AVC y SurfaceView Android, espera IDR tras inicio/reset.
- `EncoderState`: OpenH264 CPU (Constrained Baseline Level 4.2) o encoder hardware FFmpeg cuyo candidato negociado debe cumplir el contrato CR-041 (Baseline 66 o Main 77, nivel ≤4.2, YUV 4:2:0, sin B-frames); IDR con SPS/PPS. La entrada se convierte de RGB8 a YUV con ruta SSSE3 y fallback escalar bit-exacto, y NV12 directo para el encoder hardware (CR-043).

## 4. Interfaces

| Interface | Contract | Notes |
|---|---|---|
| Cemu → SDK IPC | `game_frame` con `fmt:"h264"`, AU Annex B Base64URL temporal | Cambio coordinado posterior puede usar IPC binario; no altera protocolo de red SPEC-003 |
| Server → Android control | `stream_start/stream_stop` sobre Pairing NDJSON | `fmt:"h264"`, w/h/fps, `media_port=8425` |
| Android → Server handshake | `session_id || nonce_c || HMAC` / `nonce_s || HMAC` | HMAC-SHA256 con context strings y `session_key` de SPEC-001 |
| Server → Android media | framing `length:u32 BE`, `counter:u64 BE`, AES-256-GCM record | HKDF-SHA256 subclave nueva por conexión; max 4 MiB |
| Cemu render target → encoder | RGB/RGBA readback actual → I420 → OpenH264 | El screenshot API sigue independiente e intacto |
| Server relay | fuente seleccionada → una AU pendiente por cliente | newest wins; no decode/re-encode |

## 5. Data model and persistence

Sin persistencia nueva. `session_key`, subclave media, nonce y counters viven solo en memoria por duración del Pairing/socket. Frame/AU pendiente se descarta al cambiar fuente, cortar sesión o cerrar el proceso.

## 6. Internal flows

```mermaid
sequenceDiagram
  participant C as Cemu Renderer/Encoder
  participant I as SDK IPC localhost
  participant S as Server relay
  participant A as Android control/MediaCodec
  C->>C: DRC readback → I420 → OpenH264 (IDR/SPS/PPS)
  C->>I: game_frame(fmt=h264, AU)
  I->>S: validar fuente viva y guardar AU más reciente
  A->>S: TCP 8425: session_id, nonce_c, HMAC
  S-->>A: nonce_s, HMAC; ambos derivan media_key
  S->>S: length/counter/AES-256-GCM
  S-->>A: registro cifrado con payload fn/ts/flags/AU
  A->>A: verificar counter/tag → MediaCodec → SurfaceView
  Note over S,A: Pairing cerrado revoca socket media
```

## 7. Concurrency

| Concern | Approach |
|---|---|
| Renderer vs encoder | callback de readback actual; encoder posee estado por conexión Cemu, configura IDR al inicio/cambio |
| SDK IPC vs relay | el server retiene referencia al último AU; nunca cola sin límite |
| Sesiones concurrentes | handshake/key/counter/socket y buffer pending independientes por Android |
| Pairing vs media | cierre/EOF Pairing señala shutdown al handler media y elimina SessionId |
| Android reader vs decoder | socket lee/registra secuencialmente; MediaCodec recibe solo plaintext autenticado y ordenado |

## 8. Error handling

| Error | Behavior |
|---|---|
| HMAC/session no válida | socket se cierra sin enviar datos ni filtrar existencia de sesión |
| Registro >4 MiB, truncado, counter distinto o AEAD inválido | cerrar canal; plaintext nunca se procesa |
| AU inválida, flags reservados o dimensiones incoherentes | descartar/cerrar; reiniciar decoder y esperar stream_start + IDR |
| Cambio de fuente/resolución | stream_stop, liberar AU pendiente, reiniciar encoder/decoder y comenzar por IDR |
| Corte media con Pairing vigente | handshake fresh con nonces y subclave nueva; IDR para reanudar |
| Pairing cerrado | revocar y cerrar media inmediatamente |

## 9. Caching

Solo un AU pendiente por cliente y latest-frame-wins. Invalidar ante cambio de fuente, stream_stop o cierre de conexión. No cache persistente ni base de tiles.

## 10. Algorithms

| Problem | Algorithm | Complexity |
|---|---|---|
| OpenH264 | RGB/RGBA a I420 (4:2:0), configuración baseline/level, Annex B access units | O(W×H) por frame |
| Encriptar/relay | HKDF por canal; counter monotónico forma nonce; AES-GCM autentica header y payload | O(bytes AU), sin decode |
| Android | validar length/counter/tag antes de usar bytes; feed MediaCodec Surface input/output | O(bytes AU) más decoder hardware |
| Backpressure | reemplazar AU no enviada por la más reciente, con IDR al reconectar | O(1) memoria de cola por cliente |

## 11. Patterns applied

- **Opaque relay:** el servidor no necesita acceso al contenido H.264 descodificado.
- **Per-connection subkey:** nonces cliente/servidor frescos evitan reutilizar clave/nonce entre conexiones.
- **Latest-AU-wins:** latencia acotada a costa de descartar frames antiguos.

## 12. Traceability

| Aspect | Where realized | Test |
|---|---|---|
| HKDF/HMAC, framing, AEAD/replay | core/server/android media crypto | TEST-071 |
| Autorización y relay por sesión/fuente/revocación | server media listener/registry | TEST-072 |
| OpenH264 profile/IDR/SPS/PPS/Annex B | Cemu encoder + IPC | TEST-073 |
| MediaCodec, SurfaceView, reset/recovery IDR | Android stream pipeline | TEST-074 |
| Rendimiento real NFR-001/005/007 | banco Android+Cemu | pendiente; sin afirmación de rendimiento |

## 13. Related decisions

- CR-014 — migración aprobada de JPEG/tiles a H.264/AVC.
- SPEC-001 — Pairing y clave de sesión.
- SPEC-006 — origen Plugin/selección e IPC de Cemu.
- DES-004 — adaptador Cemu y límites de captura.

## 14. Open questions

La suite/protocolo está decidido. Interoperabilidad concreta de dispositivos, FPS, latencia, CPU y energía permanecen como pruebas físicas; no son cambios de wire.

## Definition of Done

- [x] Realizes SPEC-003 v1.4.0 y sus módulos explícitamente.
- [x] Respeta las fronteras core/adaptadores descritas en ARCH §3.
- [x] Errores de SPEC-003 mapeados a recuperación sin agregar errores observables.
- [x] Concurrencia, cachés y atomicidad abordadas.
- [x] Traceability enlaza TEST-071..074.
- [x] No duplica el contrato de comportamiento de SPEC-003.
- [x] Sin placeholders; este diseño permanece Draft hasta revisión del owner y mediciones del banco.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-17 | Usuario + agente | Diseño inicial de la realización de tiles bajo CR-002 |
| 0.2.0 | 2026-10-20 | Product owner + Twinpad team (CR-014) | Sustituye tiles/JPEG por canal H.264 autenticado, cifrado y MediaCodec |
| 0.3.0 | 2026-10-30 | Product owner + Twinpad team (CR-041/CR-043) | Contrato de perfil Baseline/Main nivel ≤4.2 para el encoder hardware; conversión RGB8→YUV SSSE3 con identidad bit a bit al escalar (TEST-0105) y consumo NV12 directo del encoder hardware |
