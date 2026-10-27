# SPEC-003 — Stream de video de la Segunda pantalla (H.264/AVC)

| | |
|---|---|
| ID | SPEC-003 |
| Title | Stream de video de la Segunda pantalla (H.264/AVC) |
| Version | 1.7.0 |
| Status | `Approved` |
| Owner | Eng lead |
| Implements | REQ-004 (primario), REQ-005 (parcial: solo video) |
| Realized by | DES-003 (CR-014; Draft), DES-004 (CR-020; Draft) |
| Verified by | TEST-071, TEST-072, TEST-073, TEST-074, TEST-082, TEST-051, TEST-054 |
| Last updated | 2026-10-27 |

## 1. Purpose

Define transporte y presentación del Stream de video de la Segunda pantalla, publicado como access units H.264/AVC por el Plugin seleccionado y descodificado por Android. El canal multimedia TCP separado se autentica con la sesión SPEC-001, usa AES-256-GCM y framing binario. El servidor retransmite opacamente; no captura, decodifica ni genera la fuente. La versión 1.5.0 permite al adaptador Cemu optar por encoder H.264 hardware con fallback CPU sin cambiar el wire contract ni el perfil exigido. La versión 1.4.0 reemplazó el transporte JPEG/tiles de 1.3.0 (CR-014); no existe negociación ni fallback entre versiones.

## 2. Definitions

- **Access unit (AU)**: unidad completa de acceso H.264/AVC en formato Annex B; SPS/PPS preceden cada IDR.
- **Stream de video**: secuencia ordenada de AU transmitida PC → Android durante una Sesión autenticada.
- **Frame number (`fn`)**: contador `u64` estrictamente creciente asignado al AU por Cemu desde cero al cambiar fuente.
- **Frame activo**: última imagen decodificada y presentada por Android.
- **IDR**: punto de acceso aleatorio que reinicia estado del decoder y contiene SPS/PPS.
- **Degradación**: ajuste de resolución/fps comunicado por control; nunca recodificación en el Servidor.
- Los términos **Sesión autenticada**, **Pairing**, **Stream**, **Segunda pantalla** y **Servidor PC**: ver CTX §8 y SPEC-001 §2.

## 3. Preconditions

- Sesión autenticada establecida según SPEC-001 (estado U4/`Established`); el transporte de SPEC-003 no acepta conexiones fuera de sesión (NFR-004).
- La sesión ha confirmado un Plugin vivo con `game_selected` (SPEC-006); solo los Framebuffers publicados por esa fuente pueden alimentar el stream.

## 4. Inputs

### 4.1 Control NDJSON por sesión (SPEC-001 socket persistente)

| Message | Fields | Constraints |
|---|---|---|
| `stream_start` | `w`,`h`,`fmt`,`fps`,`media_port` | `fmt="h264"`; dimensiones 16–3840 × 16–2160; fps 1–60; `media_port=8425` |
| `stream_stop` | `reason` | `plugin_stopped` \| `server_shutdown` |

El control se codifica como línea UTF-8 JSON + LF. `stream_start` se emite solo para la fuente seleccionada y cuando existe una AU IDR disponible. Una actualización de encoder/dimensiones genera `stream_stop`, `stream_start` nuevo y una IDR; no se envían medios sin un start vigente. `video_frame`, `video_patch` y `stream_gap` no forman parte de v1.5.0.

### 4.2 Handshake del canal media TCP

Android conecta a `server_addr:8425` y envía `session_id[16] || nonce_c[32] || HMAC-SHA256[32]`, donde HMAC cubre ASCII `twinpad.v1 media client` || `session_id` || `nonce_c` con `session_key`. El servidor valida que la sesión y el socket de control sigan activos, genera `nonce_s[32]` criptográficamente aleatorio y responde `nonce_s || HMAC-SHA256[32]`; el MAC cubre ASCII `twinpad.v1 media server` || `session_id` || `nonce_c` || `nonce_s`. Fallo cierra TCP sin payload.

Ambos derivan `media_key = HKDF-SHA256(ikm=session_key, salt=nonce_c||nonce_s, info="twinpad.v1 media server-to-client aes-256-gcm", L=32)`. La clave solo vive mientras el canal media; el servidor retiene `session_key` solo en memoria por la vida del Pairing.

### 4.3 Registros cifrados y payload

Cada registro servidor→Android tiene `length:u32 BE` (bytes que siguen, incluidos counter y tag), `counter:u64 BE`, `ciphertext[length-24]` (incluye tag GCM de 16 B). Máximo `length=4 MiB`; mínimo 24 B. AES-256-GCM nonce = `00000000:u32 BE || counter:u64 BE`; AAD = bytes wire `length || counter`. `counter` inicia en 0 y aumenta exactamente en uno; longitud inválida, truncamiento, salto/repetición o tag erróneo cierra el canal.

Plaintext: `fn:u64 BE || ts_capture_us:u64 BE || flags:u8 || AnnexB access_unit`. `fn` comienza en cero y aumenta estrictamente; `ts_capture_us` es monótono desde inicio del encoder; bit 0 de flags indica IDR y bits 1–7 deben ser cero. H.264/AVC Constrained Baseline, nivel 4.2, YUV 4:2:0; sin B-frames. Cemu usa OpenH264 CPU por defecto. La preferencia persistente local `twinpad_hardware_acceleration` (false por defecto) habilita el intento de un encoder hardware disponible; si el encoder no puede inicializarse/configurarse o falla el contrato AVC, Cemu reinicia la codificación con OpenH264 CPU, sin alterar las dimensiones ni el FPS configurados. El cambio de preferencia se aplica en el siguiente frame/configuración vigente. Ambas rutas emiten Annex B con SPS/PPS en cada IDR. Longitudes fuera de rango, AU vacía o flags inválidos cierran canal; el servidor no modifica el payload.

Los nonces frescos derivan subclave nueva en cada conexión; no hay reanudación de clave/counter. Cerrar el socket Pairing revoca y cierra inmediatamente su canal media asociado. El canal solo recibe la fuente seleccionada; el servidor conserva como máximo una AU pendiente por cliente (latest-frame-wins), no decodifica ni recodifica y nunca sirve a clientes no autenticados.
## 5. Outputs

| Result | Value / shape | Condition |
|---|---|---|
| Pantalla de Segunda pantalla | `SurfaceView` presenta el frame decodificado por `MediaCodec` | stream H.264 activo y un IDR válido decodificado |
| Estado "sin segunda pantalla activa" | mensaje en la vista, no pantalla congelada | sesión activa sin stream (REQ-004 AC-3) |
| AU descartada | no se presenta contenido corrupto ni no autenticado; el decoder conserva la última imagen válida y espera IDR tras discontinuidad | fallo AEAD/framing, AU inválida o falta IDR de recuperación |
| Reanudación | el stream se recibe de nuevo tras corte transitorio <10 s | nuevo handshake media sobre sesión Pairing vigente y nueva IDR (REQ-005 AC-2) |

## 6. States

Estados del stream, independientes del estado de sesión de SPEC-002:

- `V0 Inactive`: sin stream; la vista muestra "sin segunda pantalla activa".
- `V1 Streaming`: canal media autenticado, AU válidas y presentación activa.
- `V2 Interrupted`: socket media caído o sin AU durante ≥2 s; Pairing puede seguir U4.
- `V1`/`V2`: la imagen se presenta sin texto superpuesto; solo `V0` muestra el mensaje de estado inactivo (CR-026).

| From | Event | To | Side effects |
|---|---|---|---|
| `V0` | `stream_start(fmt=h264)` | `V1` | crea MediaCodec/SurfaceView, conecta a 8425 y espera handshake + IDR |
| `V1` | AU AEAD válida y `fn` creciente | `V1` | encola AU en MediaCodec; decoder presenta en Surface |
| `V1` | AU no-IDR mientras se espera IDR | `V1` | descarta sin entregar al decoder |
| `V1` | counter/AEAD/`fn` inválido | `V2` | cierra canal y reinicia decoder; conserva último frame presentado |
| `V1` | `stream_stop` | `V0` | cierra canal, libera decoder y muestra estado inactivo |
| `V1` | sin AU ≥2 s o corte del socket media | `V2` | reconecta y reautentica si Pairing continúa activo (§8.2) |
| `V2` | handshake media nuevo + IDR | `V1` | nueva subclave/counter y recuperación sin re-pairing |
| `V2` | Pairing finalizado o recuperación ≥10 s | `V0` | libera recursos; estado de sesión según SPEC-002 |

Invariant: V1/V2 requieren Pairing activo; cerrar Pairing fuerza V0 y libera todos los recursos.

## 7. Main flow

1. Android selecciona un Plugin conforme a SPEC-006. Sin selección confirmada no hay stream y la vista indica “sin segunda pantalla activa”.
2. El Servidor relaya solo las AU codificadas por el Plugin seleccionado; no captura ni genera medios. Anuncia dimensiones/fps mediante `stream_start` por el canal de control.
3. Android conecta a TCP 8425, autentica la sesión con challenge-response HMAC y establece AES-256-GCM con clave derivada para esta conexión.
4. Cemu envía IDR inicial/SPS/PPS y luego AU Annex B con `fn` creciente y timestamp monotónico. El servidor encripta/framing y relaya sin transformación de códec; limita cada cliente a un AU pendiente.
5. Android valida counter/tag/longitud antes de entregar AU a MediaCodec; la Surface presenta la salida. Tras inicio/cambio/discontinuidad descarta hasta el siguiente IDR.
6. Al cambiar de Plugin, el server emite `stream_stop`, cierra/reset decoder y el stream nuevo inicia con IDR y `fn=0`. Al desaparecer fuente, cesa medios y actualiza la selección conforme a SPEC-006.

## 8. Alternative flows

### 8.1 Cambio de resolución

- **Trigger:** Cemu recibe `video_config` con dimensión o fps nuevos.
- **Flow:** Cemu reinicia el encoder; el servidor envía `stream_stop` y después `stream_start` con `fmt="h264"`, nueva configuración y `media_port=8425`. El primer AU es IDR con SPS/PPS; `fn` reinicia en cero.
- **Result:** Android reinicia MediaCodec/surface; no mezcla resoluciones ni requiere Pairing nuevo.

### 8.2 Corte transitorio del canal media (REQ-005 AC-2)

- **Trigger:** pérdida del TCP media <10 s con Pairing activo.
- **Flow:** Android conserva `session_id` y `session_key` solo en memoria, reconecta a `server_addr:8425`, genera `nonce_c` fresco y repite §4.2. El servidor valida el Pairing vivo, genera `nonce_s` y solicita IDR para el nuevo suscriptor. Nonces/subclave/counter son nuevos.
- **Result:** decodificación desde IDR sin re-escanear QR. Si Pairing terminó, se requiere Pairing nuevo.

### 8.3 Cambio de Plugin seleccionado

- **Trigger:** Android selecciona otro Plugin y recibe `game_selected`.
- **Flow:** el servidor envía `stream_stop`, descarta la AU pendiente anterior y deja de relayar esa fuente. El nuevo `stream_start` incluye dimensiones/fps; Cemu inicia con IDR y `fn=0`.
- **Result:** Android reinicia decoder; ninguna AU de la fuente anterior se presenta después del cambio.

### 8.4 Congestión

- **Trigger:** el relay no puede mantener el ritmo configurado.
- **Flow:** la AU pendiente se reemplaza por la más reciente; el servidor comunica la configuración deseada por el control existente. El Plugin reconfigura el encoder y emite IDR. El servidor nunca transcodifica.
- **Result:** pueden presentarse menos frames, sin acumular video viejo; NFR permanece sujeto al banco físico.

## 9. Errors

| Condition | Observable behavior | Error code / message |
|---|---|---|
| AU corrupta o de orden inválido | no se entrega a MediaCodec; se mantiene el último frame presentado y se espera IDR tras reconectar | sin mensaje al usuario |
| fallo AEAD, framing/counter inválido o cierre de control | socket media cerrado; ningún plaintext se procesa | sin payload de error |
| Control desconocido o `stream_start` inválido | termina la sesión con el mecanismo de SPEC-002 | `E4` (SPEC-001 §9) |
| handshake media sin Pairing activo, SessionId desconocido o HMAC inválido | cierra TCP sin enviar ni aceptar AU | sin respuesta/payload |
| Pairing cerrado o revocado | socket media asociado se cierra inmediatamente | estado de sesión según SPEC-002 |
| sin AU ≥2 s con Pairing vivo | V2 e indicador de reconexión mientras se reintenta TCP media | indicador, no error final |
| recuperación del canal media ≥10 s | permanece Pairing activo pero stream pasa a V0 | indicador "sin segunda pantalla activa" |

## 10. Edge cases

| Case | Expected behavior |
|---|---|
| Primera AU no-IDR o `fn` inicial ≠0 | no se presenta; se reinicia la conexión y la fuente debe iniciar con IDR `fn=0` |
| `fn` repetido/decreciente, counter AEAD repetido/saltado | cerrar canal; nuevo handshake y nueva subclave antes de aceptar medios |
| Resolución distinta de `stream_start`, flags reservados, AU vacía o longitud >4 MiB | cerrar canal, resetear decoder y esperar `stream_start` + IDR vigentes |
| Orientación cambia durante V1 | SurfaceView reescala; no renegocia codec |
| `stream_stop` durante V2 | libera recursos y permanece V0; no reconecta sin nuevo start |
| Dos Android en sesión (NFR-006) | handshake, clave, contador, latest-AU y decoder independientes por sesión |
| Timestamp de captura no creciente | cerrar canal; no se entrega la AU |

## 11. Business rules

- **R1:** el stream solo existe sobre una sesión autenticada de SPEC-001; sin sesión no hay transmisión (NFR-004).
- **R2:** el orden de presentación lo determina exclusivamente `fn`; el timestamp es diagnóstico/sincronía, nunca reordena AU.
- **R3:** la latencia objetivo es NFR-001. Encoder sin B-frames; servidor conserva como máximo un AU pendiente por cliente y reemplaza por el más nuevo; no acumula ni retransmite AU antiguas.
- **R7 (CR-014):** ningún payload de media se procesa antes de validar AES-GCM; counter recibido debe ser exactamente el esperado. Cada conexión deriva clave nueva de nonces aleatorios frescos.
- **R8 (CR-014):** el canal media se asocia a un SessionId autenticado vivo y termina al cerrar su canal de Pairing.
- **R4:** ante corte < 10 s, la reanudación reutiliza la sesión autenticada y jamás exige re-pairing (REQ-005 AC-2).
- **R5:** la degradación la decide siempre el Server (fps primero, resolución después); el Android nunca la solicita en v1.
- **R6:** si no hay stream, la vista lo indica explícitamente; jamás muestra un Frame congelado como si estuviera vivo (REQ-004 AC-3).
- **R9 (CR-025):** el modo de escalado `contain`/`expand` es presentación local de Android; no cambia resolución, fps, codec ni wire. Cambiarlo relayout el SurfaceView sin renegociar el stream ni reiniciar el decoder.

## 12. Contracts

| Contract | Canonical source | Notes |
|---|---|---|
| Control `stream_start`/`stream_stop` | este documento §4.1; NDJSON por SPEC-001 §4.2 | v1.5.0; control solo en conexión Pairing |
| Canal media H.264 cifrado | este documento §4.2–4.3 | TCP 8425; HMAC/HKDF/AES-256-GCM; Annex B; CR-014 |
| Autenticación de la sesión | SPEC-001 §7/§8 | sin duplicación: se referencia |
| Perfil de bitstream | este documento §4.3 | AVC Constrained Baseline Level 4.2, YUV 4:2:0, sin B-frames |

## 13. Examples (Given / When / Then)

```gherkin
Scenario: Flujo principal (REQ-004 AC-1)
  Given sesión autenticada, plugin seleccionado y transmitiendo
  When llega stream_start(w=1280,h=720,fmt=h264,fps=30,media_port=8425)
  And Android completa handshake HMAC y recibe una AU IDR autenticada
  Then MediaCodec presenta el video en SurfaceView

Scenario: Registro media manipulado o replay
  Given una conexión media AES-256-GCM autenticada con counter=0
  When llega un registro con tag inválido o counter distinto del esperado
  Then se cierra el canal y no se procesa ni presenta la AU

Scenario: Corte del canal media sin perder Pairing
  Given Pairing U4 y stream H.264 en V1
  When se corta solo el socket media
  Then Android abre otro TCP, autentica con nonces frescos y espera IDR
  And no se muestra QR ni se pide re-pairing

Scenario: Sin juego compatible (REQ-004 AC-3)
  Given sesión activa y ningún stream en curso
  When el usuario abre la vista de Segunda pantalla
  Then ve "sin segunda pantalla activa", no una imagen congelada
```

## 14. Invariants

- `fn` y timestamp de captura son estrictamente crecientes; ninguna AU se presenta fuera de orden.
- Ningún registro se descifra ni parsea antes de validar AES-GCM y el counter esperado.
- Ninguna AU se presenta antes de `stream_start` vigente y de IDR con SPS/PPS.
- El canal media requiere Pairing activo; su cierre revoca y cierra el TCP media.
- Tras cambio de fuente/error/corte se conserva última imagen válida o estado inactivo; no se presenta basura.
- El servidor no decodifica ni recodifica y mantiene máximo una AU pendiente por cliente.

## 15. What "complies" means

Una implementación cumple este SPEC si y solo si honra §3–§14 y pasa las verificaciones §16. La compatibilidad con v1.3.0 no se exige; Cemu, Servidor PC y Android deben actualizarse juntos.

## 16. Verification

| Aspect | Verified by |
|---|---|
| Flujo principal (§7) y AC-1 de REQ-004 | TEST-015 |
| Cambio de fuente confirmada y relay exclusivo | TEST-051 |
| Ausencia de medios generados/capturados por Server | TEST-054 |
| Frame corrupto/tardío y edge cases de §10 | TEST-016 |
| Reanudación tras corte breve (§8.2, REQ-005 AC-2) | TEST-017 |
| HKDF/HMAC/AEAD, framing, replay y validación de registros | TEST-071 |
| Relay por sesión/fuente, revocación y latest-frame-wins | TEST-072 |
| OpenH264 Annex B, perfil/IDR/SPS/PPS y timestamps | TEST-073 |
| Preferencia hardware opt-in, fallback CPU y contrato AVC preservado | TEST-082 |
| MediaCodec/Surface y recuperación por IDR | TEST-074 |
| Latencia NFR-001 y CPU NFR-005 | pruebas de performance en banco físico (fuera de CI) |

## 17. Open questions

La interoperabilidad y las mediciones físicas de FPS, latencia, CPU y energía siguen pendientes; no cambian el wire contract CR-014. El umbral exacto de degradación automática continúa abierto; no autoriza transcodificación en el Servidor.

## Definition of Done

- [x] Deterministic: two readers cannot reasonably interpret any statement differently.
- [x] All flows, errors and edge cases enumerated — no "etc.".
- [x] Every error and edge case has exact expected behavior.
- [x] Inputs/outputs fully enumerated with types and constraints.
- [x] Framing, autenticación, cifrado y perfil AVC están definidos por CR-014; no se duplica la implementación interna.
- [x] Every aspect has a TEST row; rows mirrored in traceability.md.
- [x] No contradictions with the implementing REQ.
- [x] No placeholders; Status: Approved (CR-014).

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Eng lead + agent | Draft inicial: transporte de framebuffers sobre la sesión de SPEC-001 |
| 1.0.0 | 2026-10-07 | Eng lead | Approved: OQ-1 resuelta (rgb8 sin compresión en v1) |
| 1.1.0 | 2026-10-08 | Owner (usuario, CR-001) | `fmt=jpeg`: compresión del stream para 720p/1080p a 30/60 fps |
| 1.2.0 | 2026-10-16 | Owner (usuario, CR-002) | `jpeg_tiles`, patches JPEG de 64×64, base_fn y keyframes ≤1 s; despliegue coordinado sin compatibilidad hacia atrás |
| 1.3.0 | 2026-10-18 | Product owner + Twinpad team (CR-010) | Fuente exclusiva Plugin seleccionado; sin captura/demo Server y reinicio limpio al cambiar fuente |
| 1.4.0 | 2026-10-20 | Product owner (CR-014) | Sustituye JPEG/tiles por AVC; OpenH264 en Cemu, relay TCP 8425 cifrado AES-256-GCM y MediaCodec SurfaceView |
| 1.5.0 | 2026-10-23 | Product owner (CR-020) | Añade encoder hardware H.264 opt-in en Cemu con fallback OpenH264 CPU; wire y perfil invariantes |
| 1.6.0 | 2026-10-27 | Product owner (CR-025) | R9: modo de escalado contain/expand como presentación local de Android |
| 1.7.0 | 2026-10-27 | Product owner (CR-026) | Pantalla limpia en V1/V2: sin texto superpuesto ni marco de controles |
