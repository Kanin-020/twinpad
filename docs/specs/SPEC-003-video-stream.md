# SPEC-003 — Stream de video de la Segunda pantalla (H.264/AVC)

| | |
|---|---|
| ID | SPEC-003 |
| Title | Stream de video de la Segunda pantalla (H.264/AVC) |
| Version | 1.16.0 |
| Status | `Approved` |
| Owner | Eng lead |
| Implements | REQ-004 (primario), REQ-005 (parcial: solo video) |
| Realized by | DES-003 (CR-014; Draft), DES-004 (CR-020; Draft) |
| Verified by | TEST-071, TEST-072, TEST-073, TEST-074, TEST-082, TEST-051, TEST-054, TEST-088, TEST-0131, TEST-0132, TEST-0138, TEST-0139, TEST-0140, TEST-0162, TEST-0163, TEST-0164 |
| Last updated | 2026-11-12 |

## 1. Purpose

Define transporte y presentación del Stream de video de la Segunda pantalla, publicado como access units H.264/AVC por el Plugin seleccionado y descodificado por Android. El canal multimedia TCP separado se autentica con la sesión SPEC-001, usa AES-256-GCM y framing binario. El servidor retransmite opacamente; no captura, decodifica ni genera la fuente. La versión 1.5.0 permite al adaptador Cemu optar por encoder H.264 hardware con fallback CPU sin cambiar el wire contract ni el perfil exigido. La versión 1.4.0 reemplazó el transporte JPEG/tiles de 1.3.0 (CR-014); no existe negociación ni fallback entre versiones.

## 2. Definitions

- **Access unit (AU)**: unidad completa de acceso H.264/AVC en formato Annex B; SPS/PPS preceden cada IDR.
- **Stream de video**: secuencia ordenada de AU transmitida PC → Android durante una Sesión autenticada.
- **Frame number (`fn`)**: contador `u64` estrictamente creciente asignado al AU por Cemu desde cero al cambiar fuente.
- **Frame activo**: última imagen decodificada y presentada por Android.
- **IDR**: punto de acceso aleatorio que reinicia estado del decoder y contiene SPS/PPS.
- **Degradación**: ajuste de resolución comunicado por control; nunca recodificación en el Servidor.
- Los términos **Sesión autenticada**, **Pairing**, **Stream**, **Segunda pantalla** y **Servidor PC**: ver CTX §8 y SPEC-001 §2.

## 3. Preconditions

- Sesión autenticada establecida según SPEC-001 (estado U4/`Established`, o S4 con retención viva — CR-056, SPEC-001 §8.2); el transporte de SPEC-003 no acepta conexiones fuera de sesión (NFR-004).
- La sesión ha confirmado un Plugin vivo con `game_selected` (SPEC-006); solo los Framebuffers publicados por esa fuente pueden alimentar el stream.

## 4. Inputs

### 4.1 Control NDJSON por sesión (SPEC-001 socket persistente)

| Message | Fields | Constraints |
|---|---|---|
| `stream_start` | `w`,`h`,`fmt`,`fps`,`media_port` | `fmt="h264"`; dimensiones 16–3840 × 16–2160; `fps` metadata informativa del wire, dominio u8 0–255 (`0` = sin límite declarado, CR-029); `media_port=8425` |
| `stream_stop` | `reason` | `plugin_stopped` \| `server_shutdown` |

El control se codifica como línea UTF-8 JSON + LF. `stream_start` se emite solo para la fuente seleccionada y cuando existe una AU IDR disponible. Una actualización de encoder/dimensiones genera `stream_stop`, `stream_start` nuevo y una IDR; no se envían medios sin un start vigente. Desde CR-029 el framerate no es un parámetro de control: ninguna capa fija el ritmo por un fps objetivo. La fuente (Plugin/Cemu) publica cada AU en cuanto captura+encoder lo permiten y el Servidor la retransmite en cuanto llega; `stream_start.fps` es metadata informativa (`0` = sin límite declarado, valor que anuncia el Servidor) sin validación de rango 1–60 ni efecto de pacing. La línea `video_config` de sesión (drawer de la app, SPEC-002 R15/R17) puede incluir `w`/`h` (dimensiones 16–3840 × 16–2160, o `0/0` = cargar la dimensión nativa del DRC), `bitrate_kbps` (1.000–30.000, por defecto 8.000) o ambas (clamp idéntico en server y adaptador; el `0/0` pasa sin clamp como centinela de nativo): el Servidor conserva el valor vigente y reenvía w/h y bitrate a Cemu; Cemu reinicia su encoder cuando cambian (CR-027/CR-030) y aplica w/h como resolución de emisión SOLO de la pantalla GamePad (DRC): escalado nearest-neighbor del frame capturado antes del encode, en ambas direcciones (CR-074 sube o baja según el target elegido; impares se reducen al par inferior). La resolución general del juego en TV no cambia. `video_config` ya no lleva `fps` (CR-029): el Servidor ignora ese campo si una app antigua lo envía. `video_frame`, `video_patch` y `stream_gap` no forman parte de v1.5.0.

### 4.2 Handshake del canal media TCP

Android conecta a `server_addr:8425` y envía `session_id[16] || nonce_c[32] || HMAC-SHA256[32]`, donde HMAC cubre ASCII `twinpad.v1 media client` || `session_id` || `nonce_c` con `session_key`. El servidor valida que la sesión y el socket de control sigan activos o la retención de la ligadura siga viva (SPEC-001 §8.2/S4, CR-056), genera `nonce_s[32]` criptográficamente aleatorio y responde `nonce_s || HMAC-SHA256[32]`; el MAC cubre ASCII `twinpad.v1 media server` || `session_id` || `nonce_c` || `nonce_s`. Fallo cierra TCP sin payload.

Ambos derivan `media_key = HKDF-SHA256(ikm=session_key, salt=nonce_c||nonce_s, info="twinpad.v1 media server-to-client aes-256-gcm", L=32)`. La clave solo vive mientras el canal media; el servidor retiene `session_key` solo en memoria por la vida del Pairing.

### 4.3 Registros cifrados y payload

Cada registro servidor→Android tiene `length:u32 BE` (bytes que siguen, incluidos counter y tag), `counter:u64 BE`, `ciphertext[length-24]` (incluye tag GCM de 16 B). Máximo `length=4 MiB`; mínimo 24 B. AES-256-GCM nonce = `00000000:u32 BE || counter:u64 BE`; AAD = bytes wire `length || counter`. `counter` inicia en 0 y aumenta exactamente en uno; longitud inválida, truncamiento, salto/repetición o tag erróneo cierra el canal.

Plaintext: `fn:u64 BE || ts_capture_us:u64 BE || flags:u8 || AnnexB access_unit`. `fn` comienza en cero y aumenta estrictamente; `ts_capture_us` es monótono desde inicio del encoder; bit 0 de flags indica IDR y bits 1–7 deben ser cero. H.264/AVC Baseline (66, con o sin `constraint_set0`) o Main (77), nivel ≤4.2, YUV 4:2:0; sin B-frames (CR-041: el driver VAAPI freeworld no expone Constrained Baseline y el contrato estricto rebota; la ruta hardware descarta cualquier candidato negociado fuera de este contrato y reinicia con OpenH264 CPU). Cemu usa OpenH264 CPU por defecto. CR-036: Cemu no recrea el encoder por cambios de cadencia medida; la cadencia solo alimenta la metadata del rate-control y el encoder se recrea únicamente cuando cambian las dimensiones o el bitrate configurados. Con plugin conectado y sin frame nuevo del DRC en 100 ms, Cemu publica una repetición del último frame (mismo contrato de AU, sin `stream_gap`). La preferencia persistente local `twinpad_hardware_acceleration` (false por defecto) habilita el intento de un encoder hardware disponible; si el encoder no puede inicializarse/configurarse o falla el contrato AVC, Cemu reinicia la codificación con OpenH264 CPU, sin alterar las dimensiones ni el FPS configurados. El cambio de preferencia se aplica en el siguiente frame/configuración vigente. Ambas rutas emiten Annex B con SPS/PPS en cada IDR. Longitudes fuera de rango, AU vacía o flags inválidos cierran canal; el servidor no modifica el payload.

Los nonces frescos derivan subclave nueva en cada conexión; no hay reanudación de clave/counter. Cerrar el socket Pairing revoca y cierra inmediatamente su canal media asociado. El canal solo recibe la fuente seleccionada; el servidor conserva como máximo una AU pendiente por cliente (latest-frame-wins), no decodifica ni recodifica y nunca sirve a clientes no autenticados.

CR-058 (R10) — degradación automática por congestión en el relay: por ventana móvil de 2 000 ms y por sesión cliente el Servidor cuenta P = AUs publicadas por la fuente destinadas a esa sesión y D = AUs que sustituyeron a una AU pendiente por latest-frame-wins sin haber podido escribirse al socket (descartes). Sesión congestionada si D/P ≥ 0,25 y P ≥ 10 en la ventana; sale de congestión si D/P < 0,05 durante 10 000 ms continuados (histéresis). Ante ≥ 2 ventanas congestionadas el Servidor baja un rung de la escalera del encoder [configurado → 1/2 → 1/4 → 1/8 → suelo 500 kbps] reenviando `video_config` con el nuevo `bitrate_kbps` (mismo mecanismo de CR-027/CR-030; resolución intacta) y solicita IDR (`video_force_idr`, CR-051). Con múltiples sesiones, el rung ejecutado es el mínimo entre sesiones congestionadas. Tras 10 000 ms sin congestión en todas las sesiones se sube un rung, sin superar jamás el bitrate configurado por el usuario. La escalera y los umbrales son parámetros de configuración del Servidor, no contrato wire.

### 4.4 Canal media UDP preferente con fallback TCP (CR-062)

Canal **unidireccional Servidor→Android** por datagramas UDP al mismo `media_port=8425`, OPCIONAL y PREFERENTE: el TCP de §4.2–§4.3 queda como fallback intacto. El teléfono decide: envía JOIN y, si en 900 ms no llega NINGÚN datagrama autenticado individual por UDP (CR-081: cuenta la señal por datagrama — cualquier envelope TWDU autenticado aunque la AU no ensamble —, no la entrega de AU completas), revierte al TCP media existente (§8.5).

**JOIN (Android→Servidor, 92 B):** `magic[8 b"TWPUDP01"] || session_id[16] || last_seq:u32 BE || join_nonce[32] || HMAC-SHA256[32]`; el MAC cubre ASCII `"twinpad.v1 media udp client"` || `session_id` || `last_seq:u32 BE` || `join_nonce` con `session_key`. Validación idéntica a §4.2 (sesión activa o retención viva, CR-056). El teléfono reenvía el MISMO join como keepalive cada 1 000 ms mientras quiera UDP (reporte R11: `last_seq` = último `seq` de datos recibido, 0 si aún ninguno). El Servidor lo usa solo para refrescar binding/estadística, sin IDR; un JOIN con `join_nonce` distinto del vigente es re-join: deriva clave nueva y toma la propiedad del canal (IDR).

**Clave:** `udp_media_key = HKDF-SHA256(ikm = session_key, salt = join_nonce, info = "twinpad.v1 media udp server-to-client aes-256-gcm")`, misma técnica RFC 5869 que §4.2. El contador de datagramas renueva con cada re-join; sin reanudación.

**Envelope data (Servidor→Android):** `magic[4 b"TWDU"] || seq:u32 BE || au_id:u16 BE || flags:u8 || frag_index:u16 BE || frag_total:u16 BE || ciphertext || tag[16]` — header de 15 B + tag AES-256-GCM de 16 B; AAD = los 15 B del header; nonce = `00000000:u32 || seq:u64 BE`. Total ≤ 1 400 B; payload ≤ 1 360 B. `seq` arranca en 0 por join y es estrictamente creciente por datagrama (con recovery incluido). `au_id` identifica la AU (creciente por AU, u16; `0xFFFF` resérvalo para FEC). `flags`: bit0 = primer fragmento, bit1 = último fragmento, bits 2–7 deben ser 0. Una AU se fragmenta en `frag_total ≥ 1` trozos consecutivos (`frag_index` 0-based); `frag_total = 1` lleva ambos bits. El plaintext concatenado de la AU es EXACTAMENTE el plaintext de §4.3 (`fn`, `ts_capture_us`, `flags` de AU, AU Annex B): las mismas invariantes (fn/ts crecientes, bit0 IDR) se verifican al ensamblar.

**FEC (XOR, grupo ≤ 4):** cada grupo de hasta 4 datagramas de datos consecutivos por `seq` tiene UN datagrama de recuperación con envelope FEC (`au_id = 0xFFFF`, `frag_index = count`, `frag_total = 0`, flags bit2 = 1, bits 0–1 en 0) cuyo plaintext contiene: `group_base:u32 BE || count:u8 || len_i:u16 BE × count || metas_i (au_id:u16, flags:u8, frag_index:u16, frag_total:u16) × count || XOR`, donde `len_i` es el largo del ciphertext+tag del datagrama i y `XOR` es la XOR de los ciphertexts+tags del grupo rellenados con ceros al máximo `len_i`. Si falta EXACTAMENTE un datagrama del grupo y llega su recovery, el receptor reconstruye su ciphertext por XOR (cabecera recomputada desde las metas) y lo valida con GCM; si faltan 0 o ≥ 2, la AU queda perdida (discontinuidad §5). El grupo se cierra con su recovery al completar 4 datagramas de datos, o al terminar la AU en curso si quedó parcial (1–3).

**Recepción (Android):** ventana anti-replay de 256 datagramas por join: se acepta todo `seq` no visto previamente con `seq > watermark` o `watermark − seq < 256`; fuera de ventana o repetido → descartado silencioso (el canal NO cierra). Ensamblado por `au_id` con tope de 4 AUs pendientes y `frag_total ≤ 3 073` (plano máximo de §4.3); una AU pendiente se abandona cuando el `seq` corriente supera en ≥ 256 el `seq` de su primer fragmento. Datagrama inválido (GCM/flags/framing) → descartado silencioso; NUNCA se entrega una AU no autenticada al decoder. La entrega respeta latest-frame-wins de §4.3: una AU ensamblada con `fn`/`ts_capture_us` no estrictamente creciente se descarta sin entregar.

**Dueño del relay:** por sesión hay UN solo dueño del relay, decidido por recencia de eventos: un JOIN válido toma la propiedad UDP (y solicita IDR, CR-051); un handshake TCP §4.2 válido la devuelve a TCP (también con IDR). Entre eventos de propiedad, solo el dueño vigente escribe datagramas. Reenviar el mismo JOIN (keepalive) NO cambia la propiedad ni solicita IDR. El cierre/revocación del Pairing (R8) elimina el canal UDP y el de TCP simultáneamente.

**Reanudación (CR-099):** tras un `pair_resume` aceptado (SPEC-001 §8.2), el canal UDP previo queda sin dueño y NO se reutiliza: el teléfono debe emitir un JOIN nuevo (`join_nonce` nuevo, clave derivada nueva) con el `session_id` reanudado. Hasta recibir ese JOIN aceptado, el Servidor no cuenta silencio UDP como pérdida ni dispara el fallback de §8.5, y el relay no cuenta el binding del nonce anterior (su expiración de 10 000 ms se mide desde el nuevo JOIN). El JOIN nuevo toma la propiedad del canal y solicita IDR (CR-051).

**Señal de pérdida (R11):** el `last_seq` de los JOIN vigentes alimenta la MISMA escalera de R10: por ventana de 2 000 ms el Servidor cuenta `P` = datagramas de datos enviados y `D` = huecos estimados (`seq_corriente − last_seq_recibido`, acotado ≥ 0, solo con keepalive fresco ≤ 5 s; sin señal no se ingiere). Umbrales e histéresis idénticos a R10 (§4.3). Cada keepalive del mismo nonce refresca el binding y deja una línea de log en el Servidor (CR-081). **Expiración del binding (CR-081):** si durante 10 000 ms no llega ningún JOIN/keepalive autenticado del canal vigente, el relay del Servidor cesa de emitir (con log) y libera el canal; el siguiente JOIN válido reabre con IDR. Un socket del teléfono muerto no deja al relay disparando datagramas a un puerto sin dueño ni rastro.

Los datagramas UDP no relajan las invariantes de §14: una AU con fallo de validación se descarta y se conserva la última imagen válida (§5), sin basura en el decoder.

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

- **Trigger:** Cemu recibe `video_config` con dimensión o bitrate_kbps nuevos (fps ya no es parámetro, CR-029).
- **Flow:** Cemu reinicia el encoder y escala el framebuffer del DRC a la dimensión objetivo (nearest-neighbor, en ambas direcciones; CR-030/CR-074); el servidor envía `stream_stop` y después `stream_start` con `fmt="h264"`, nueva configuración y `media_port=8425`. El primer AU es IDR con SPS/PPS; `fn` reinicia en cero.
- **Result:** Android reinicia MediaCodec/surface; no mezcla resoluciones ni requiere Pairing nuevo.

### 8.2 Corte transitorio del canal media (REQ-005 AC-2)

- **Trigger:** pérdida del TCP media <10 s con Pairing activo.
- **Flow:** Android conserva `session_id` y `session_key` solo en memoria, reconecta a `server_addr:8425`, genera `nonce_c` fresco y repite §4.2. El servidor valida el Pairing vivo, genera `nonce_s` y solicita IDR para el nuevo suscriptor. Nonces/subclave/counter son nuevos.
- **Result:** decodificación desde IDR sin re-escanear QR. Si Pairing terminó, se requiere Pairing nuevo.

### 8.3 Cambio de Plugin seleccionado

- **Trigger:** Android selecciona otro Plugin y recibe `game_selected`.
- **Flow:** el servidor envía `stream_stop`, descarta la AU pendiente anterior y deja de relayar esa fuente. El nuevo `stream_start` incluye dimensiones/fps; Cemu inicia con IDR y `fn=0`.
- **Result:** Android reinicia decoder; ninguna AU de la fuente anterior se presenta después del cambio.

### 8.4 Congestión (CR-058)

- **Trigger:** ≥ 2 ventanas de relay (2 000 ms cada una) con D/P ≥ 0,25 y P ≥ 10 en la sesión cliente (§4.3, R10).
- **Flow:** el Servidor baja un rung de la escalera de bitrate (configurado → 1/2 → 1/4 → 1/8 → suelo 500 kbps) reenviando `video_config` con el nuevo `bitrate_kbps` y solicita IDR (`video_force_idr`); el Plugin reconfigura el encoder y emite IDR. El servidor nunca transcodifica ni cambia resolución.
- **Recuperación:** 10 000 ms sin congestión (D/P < 0,05 en todas las sesiones) → sube un rung con IDR, sin superar el bitrate configurado por el usuario.
- **Result:** se reduce el descarte de AU conservando el video vivo; la calidad efectiva la decide el Servidor (R5/R10), sin mensajes nuevos en wire.

### 8.5 Fallback UDP → TCP (CR-062, REQ-005 AC-2)

- **Trigger:** el teléfono emitió JOIN (§4.4) y no recibe NINGÚN datagrama autenticado individual por UDP en 900 ms (señal por datagrama de CR-081; paired en retención o Pairing activo).
- **Flow:** el teléfono cierra el socket UDP, deja de enviar keepalives y sigue la ruta TCP existente de §4.2 (conexión, HMAC, subclave nueva, IDR). El Servidor, al autenticar ese TCP, toma la propiedad del relay (recencia, §4.4) y suspende el envío UDP. La recuperación de UDP tras un fallback exige un ciclo nuevo de reconexión media (§8.2): el teléfono vuelve a intentar JOIN al reiniciar el transporte.
- **Reanudación (CR-099):** tras `pair_resume` aceptado (SPEC-001 §8.2) el 900 ms de §4.4 se cuenta desde el JOIN nuevo, no desde el socket de control; un JOIN del nonce anterior tras la reanudación se descarta.
- **Result:** un solo dueño envía; ninguna AU duplicada llega al decoder; la pérdida del canal UDP se comporta igual que la pérdida del TCP media (V2 → reconexión).

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
- **R5:** la degradación la decide siempre el Server (resolución; el framerate ya no degrada por pacing desde CR-029); el Android nunca la solicita en v1.
- **R10 (CR-058):** el Servidor decide la degradación automática de bitrate del encoder compartido ante congestión observada en el relay del canal media (§4.3). El Android sigue sin solicitar degradación (R5 vigente).
- **R11 (CR-062):** el canal UDP (§4.4) es opcional y preferente; su pérdida observada (`last_seq` de los JOIN) alimenta la MISMA escalera de R10 con los mismos umbrales (§4.4). Un canal UDP defectuoso siempre termina en el TCP de §4.2 (§8.5); jamás hay dos dueños escribiendo simultáneamente ni AU no autenticada en el decoder.
- **R6:** si no hay stream, la vista lo indica explícitamente; jamás muestra un Frame congelado como si estuviera vivo (REQ-004 AC-3).
- **R9 (CR-025):** el modo de escalado `contain`/`expand` es presentación local de Android; no cambia resolución, codec ni wire. Cambiarlo relayout el SurfaceView sin renegociar el stream ni reiniciar el decoder.

## 12. Contracts

| Contract | Canonical source | Notes |
|---|---|---|
| Control `stream_start`/`stream_stop` | este documento §4.1; NDJSON por SPEC-001 §4.2 | v1.5.0; control solo en conexión Pairing |
| Canal media H.264 cifrado | este documento §4.2–4.3 | TCP 8425; HMAC/HKDF/AES-256-GCM; Annex B; CR-014 |
| Canal media UDP con FEC (opcional preferente) | este documento §4.4/§8.5 | UDP 8425; JOIN+keepalive HMAC; envelope AES-256-GCM con AAD; frag ≤ 1 360 B; FEC XOR ≤ 4; fallback TCP 900 ms; R11; CR-062 |
| Autenticación de la sesión | SPEC-001 §7/§8 | sin duplicación: se referencia |
| Perfil de bitstream | este documento §4.3 | AVC Baseline (66) o Main (77), nivel ≤4.2, YUV 4:2:0, sin B-frames (CR-041) |

## 13. Examples (Given / When / Then)

```gherkin
Scenario: Flujo principal (REQ-004 AC-1)
  Given sesión autenticada, plugin seleccionado y transmitiendo
  When llega stream_start(w=1280,h=720,fmt=h264,fps=0,media_port=8425)
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
| R10: política pura de congestión y escalera (ventanas, histéresis, límites) | TEST-0131 |
| R10: integración — congestión simulada baja rung reenviando video_config + IDR; recuperación sube rung | TEST-0132 |
| R11: primitivas UDP puras — JOIN/HMAC, derivación de clave, envelope GCM, anti-replay 256, ensamblado por AU y reconstrucción por FEC XOR (falta 1 → recupera; falta 0/≥2 → discontinuidad) | TEST-0138 |
| R11: integración server — JOIN toma dueño (IDR), keepalive no, TCP handshake devuelve dueño, datagramas solo del dueño; pérdida por `last_seq` alimenta la escalera R10 | TEST-0139 |
| R11: cliente Android — watchdog 900 ms con fallback al cliente TCP vigente; ventana anti-replay y ensamblado idénticos a core | TEST-0140 |
| CR-099: tras `pair_resume` el JOIN UDP nuevo toma el dueño (IDR); el silencio UDP previo no cuenta ni dispara §8.5; el JOIN del nonce anterior se descarta | TEST-0191 |

## 17. Open questions

La interoperabilidad y las mediciones físicas de FPS, latencia, CPU y energía siguen pendientes; no cambian el wire contract CR-014. La degradación automática quedó resuelta por CR-058 (R10 §11, política en §4.3/§8.4).

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
| 1.8.0 | 2026-10-27 | Product owner (CR-027) | `video_config` admite y reenvía `bitrate_kbps` (1.000–30.000, default 8.000); trigger §8.1 incluye el cambio de bitrate |
| 1.16.0 | 2026-11-12 | Buffy (agente) | CR-099: §4.4 re-JOIN UDP tras `pair_resume` (nonce nuevo, propiedad e IDR, silencio previo no cuenta); §8.5 reanudación; TEST-0191 |
| 1.15.0 | 2026-11-05 | Buffy (agente, CR-081) | Watchdog del fallback §8.5 por datagramas autenticados (no AU completas); expiración del binding del relay UDP (10 s sin keepalive → salida con log) y log del refresco por keepalive (§4.4); §8.5 alineado |
| 1.9.0 | 2026-10-28 | Product owner (CR-029) | FPS unlimited: sin pacing ni tope 30/60; `stream_start.fps` metadata 0–255 (`0` = unlimited); `video_config` sin fps |
| 1.10.0 | 2026-10-28 | Product owner (CR-030) | `video_config` reenvía w/h a Cemu; el DRC se escala (nearest, sin upscale) antes del encode; trigger §8.1 define el escalado |
| 1.11.0 | 2026-11-04 | Product owner (CR-074) | Escalado bidireccional del DRC (upscale permitido) y sentinela `0/0` = nativo en `video_config`; §8.1 sin cambio wire |
| 1.11.0 | 2026-10-30 | Product owner (CR-041) | Contrato de perfil relajado a Baseline (66) o Main (77), nivel ≤4.2, para admitir el encoder VAAPI freeworld; 4:2:0 y sin B-frames sin cambios |
| 1.12.0 | 2026-11-01 | Buffy (agente) (CR-056) | §3/§4.2: el handshake media también se admite con ligadura en retención (SPEC-001 §8.2/S4) |
| 1.13.0 | 2026-11-01 | Buffy (agente) (CR-058) | R10: degradación automática de bitrate por congestión (ventana D/P 2 000 ms, escalera 1/2→1/4→1/8→500 kbps, histéresis 10 000 ms); §8.4 reescrita; OQ §17 cerrada; TEST-0131/0132 |
| 1.14.0 | 2026-11-02 | Product owner (CR-062) | §4.4 nuevo: canal media UDP preferente (JOIN/keepalive HMAC, envelope AES-256-GCM con AAD, fragmentación ≤ 1 360 B, FEC XOR ≤ 4, anti-replay 256, dueño por recencia, señal de pérdida R11); §8.5 fallback TCP 900 ms; R11; TEST-0138/0139/0140 |
| 0.1.0 | 2026-10-07 | Eng lead + agent | Draft inicial: transporte de framebuffers sobre la sesión de SPEC-001 |
| 1.0.0 | 2026-10-07 | Eng lead | Approved: OQ-1 resuelta (rgb8 sin compresión en v1) |
| 1.1.0 | 2026-10-08 | Owner (usuario, CR-001) | `fmt=jpeg`: compresión del stream para 720p/1080p a 30/60 fps |
| 1.2.0 | 2026-10-16 | Owner (usuario, CR-002) | `jpeg_tiles`, patches JPEG de 64×64, base_fn y keyframes ≤1 s; despliegue coordinado sin compatibilidad hacia atrás |
| 1.3.0 | 2026-10-18 | Product owner + Twinpad team (CR-010) | Fuente exclusiva Plugin seleccionado; sin captura/demo Server y reinicio limpio al cambiar fuente |
| 1.4.0 | 2026-10-20 | Product owner (CR-014) | Sustituye JPEG/tiles por AVC; OpenH264 en Cemu, relay TCP 8425 cifrado AES-256-GCM y MediaCodec SurfaceView |
| 1.5.0 | 2026-10-23 | Product owner (CR-020) | Añade encoder hardware H.264 opt-in en Cemu con fallback OpenH264 CPU; wire y perfil invariantes |
| 1.6.0 | 2026-10-27 | Product owner (CR-025) | R9: modo de escalado contain/expand como presentación local de Android || 1.7.0 | 2026-10-27 | Product owner (CR-026) | Pantalla limpia en V1/V2: sin texto superpuesto ni marco de controles || 1.8.0 | 2026-10-29 | Product owner (CR-036) | Continuidad del video: sin recreación de encoder por cadencia medida (solo metadata de rate-control) y repetición del último AU del DRC cada ≤100 ms con plugin conectado; wire y perfil intactos |


