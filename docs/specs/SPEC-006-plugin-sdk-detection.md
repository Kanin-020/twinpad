# SPEC-006 — Detección de juego con SDK Twinpad (modelo B)
| | |
|---|---|
| ID | SPEC-006 |
| Title | Detección de juego con SDK Twinpad (REQ-006) |
| Version | 1.19.0 |
| Status | `Approved` |
| Owner | Eng lead |
| Implements | REQ-006 (AC-1..AC-6) |
| Realized by | DES-004 (CR-005/CR-006/CR-007/CR-008/CR-009/CR-010/CR-012/CR-013/CR-015/CR-016/CR-017, Draft; adaptador Cemu) |
| Verified by | TEST-021, TEST-044, TEST-046, TEST-047, TEST-048, TEST-049, TEST-050, TEST-051, TEST-052, TEST-053, TEST-054, TEST-055, TEST-062, TEST-064, TEST-079, TEST-092, TEST-093, TEST-094, TEST-095, TEST-096, TEST-097, TEST-098, TEST-099, TEST-0100 |
| Last updated | 2026-10-29 |

## 1. Purpose

Define el registro de Plugins vivos, su selección por sesión Android y el relay de audio/video e inputs entre el Plugin y el Android device. El Servidor PC solo retransmite/adapta medios publicados por el Plugin; no captura escritorio ni genera demo. Implementa detección y salida audiovisual de REQ-006.

Para terceros, el SDK Twinpad es la vía principal del giroscopio: el snapshot `input_report` con `motion` llega al Plugin mediante `poll_input()`. Las Config externas y el gamepad estándar no transportan giroscopio.

## 2. Definitions

- **Juego compatible**: juego con SDK Twinpad integrado en ejecución en el PC Host.
- **Anuncio**: identificación que el juego entrega al Servidor al arrancar (nombre, id de juego, capacidades).
- Ver CTX §8: Plugin, SDK Twinpad, Servidor PC.

## 3. Preconditions

- La integración host del Plugin inicia/reutiliza el Servidor PC y presenta el QR de vinculación.
- Un juego con SDK integrado arranca en el mismo PC; el SDK conecta y anuncia su Plugin (Cemu es la primera integración host).

## 4. Inputs

| Field | Type | Constraints | Required |
|---|---|---|---|
| `game_id` | string | 1–64 chars, `[a-z0-9_.-]`, único por juego | yes |
| `game_name` | string | 1–128 chars legibles | yes |
| `capabilities` | conjunto | subconjunto de `second_screen`, `audio`, `input_consumption` | yes |
| Inicio host del Servidor PC | acción de integración host | inicia si falta, reutiliza si está activo y presenta QR vigente | al comenzar Pairing |
| `game_select` | mensaje Android→Servidor de sesión | `type:"game_select"`, `game_id` de 1–64 chars válido | al seleccionar fuente |
| `game_selected` | mensaje Servidor→Android de sesión | `type:"game_selected"`, `game_id` vivo previamente solicitado | sí, al aceptar selección |
| `games_available` | mensaje Servidor→Android de sesión | lista completa ordenada de Plugins vivos | al conectar sesión y al cambiar liveness |
| `rumble` | mensaje Cemu→Servidor→Android de sesión (CR-033) | `type:"rumble"`, `length` 0–120, `data` base64url de exactamente `length` bytes; `length` 0 = parar | por cada patrón aceptado de `VPADControlMotor` del Jugador 1 mientras Cemu está conectado; no persistido; Android lo ignora si el dispositivo no tiene vibrador |
| `camera_frame` | mensaje Android→Servidor→Cemu de sesión (CR-034) | `type:"camera_frame"`, `w:320`, `h:240`, `ts_capture_us:u64` monotónico desde el inicio del encoder, `idr:boolean`, `data` base64url de una access unit H.264 no vacía; sin persistir | por cada access unit de la cámara frontal del teléfono (30 fps) solo mientras Cemu está seleccionado **Y** hay demanda vigente de cámara del juego (CR-038: `camera_request on=true`); Cemu la descarta si no hay decodificador |
| `camera_request` | mensaje Cemu→Servidor→Android de sesión (CR-038) | `type:"camera_request"`, `on:boolean` exactamente | en cada transición del número de instancias HLE de cámara abiertas por el juego (a demanda/vigente al anunciar o re-seleccionar); no persistido; encola con coalescing en el Servidor (tope 8) y se re-emite a la sesión Android vigente/reconectada |
| Input Plugin→SDK | mensaje NDJSON por IPC primary | `InputMsg::InputReport` de SPEC-004 §4 | cuando existe snapshot retenido para el Plugin seleccionado |

Los mensajes de selección de §12 viajan en la conexión autenticada de sesión Android y el SDK consulta el último `input_report` recibido por su conexión primaria. Canal Plugin→Servidor: TCP localhost NDJSON. `game_announce`, `game_frame` e inputs encaminados por el Servidor usan la conexión primaria SDK al listener `127.0.0.1:8421` (ADR-008). Un `game_frame` H.264 añade obligatoriamente `ts_capture_us:u64` (monotónico desde el inicio del encoder) e `idr:boolean` (true si la AU contiene un IDR), según CR-015; otros formatos no incluyen estos campos. `gfn` sigue siendo el contador del Plugin, independiente del contador de presentación del stream SPEC-003. La fuente PCM abre una segunda conexión a `127.0.0.1:8422`, independiente de los frames, que envía `game_audio_start`, `game_audio_chunk` y `game_audio_stop`, con `game_id` y PCM decodificado máximo 16 KiB por chunk. Solo se acepta audio de un `game_id` vivo anunciado y con capability `audio`; una segunda conexión de audio del mismo juego o juego desconocido se cierra. El SDK consume la fuente callback en hilo dedicado y usa una cola acotada de 8 chunks: al llenarse descarta el chunk más antiguo, sin bloquear `push_frame`.

El Servidor acepta `input_report` solo por una sesión autenticada; conserva su último snapshot válido por sesión incluso antes de elegir Plugin. Al aceptar una selección, entrega ese snapshot inicial a la conexión primary del Plugin seleccionado y enruta allí cada actualización posterior aceptada. `poll_input()` del SDK devuelve una copia del snapshot más reciente disponible, o `None` si aún no se recibió input; no bloquea ni devuelve una cola histórica de eventos. Estados intermedios pueden coalescerse.

Para selección de medios: `game_select` selecciona un Plugin vivo identificado por `game_id`; `game_selected` confirma ese mismo id. Si el id no está vivo, el Servidor conserva la selección previa si aún vive y devuelve `games_available` con la lista actual; si la previa ya no vive, queda sin fuente seleccionada. Una selección obsoleta no interrumpe una fuente vigente. Mensajes desconocidos/malformados violan el contrato estricto de la sesión (E4, SPEC-001).

## 5. Outputs

| Result | Value / shape | Condition |
|---|---|---|
| Juego detectado | aparece como "juego compatible disponible" en el estado del server (visible por logs/UI y por la app) | anuncio válido mientras el proceso viva |
| Juego detectado publicado a la app | mensaje de sesión que lista el juego disponible | sesión autenticada activa |
| Juego desaparecido | deja de estar disponible al terminar su proceso (crash incluido) | el server verifica liveness |

## 6. States

- `D0 NoGame`: ningún juego compatible en ejecución.
- `D1 Available`: ≥ 1 juego compatible detectado y vivo.

| From | Event | To | Side effects |
|---|---|---|---|
| `D0` | anuncio válido | `D1` | el juego se lista como disponible |
| `D1` | el proceso del juego termina (cualquier causa) | `D0` si era el único | deja de estar disponible; streams que dependían de él se detienen con `stream_stop`/`audio_stop` (SPEC-003/005) |

Invariant: solo juegos con anuncio vivo figuran como disponibles; un crash nunca deja un "fantasma". Cada sesión tiene como máximo un `game_id` seleccionado; sin selección viva no recibe audio ni video.

## 7. Main flow

1. La integración host de cada Plugin SDK solicita iniciar o reutilizar el Servidor PC y presenta el QR vigente. El SDK Rust es headless y no administra procesos ni UI. Cemu es la primera integración host y ejecuta este paso mediante su adaptador.
2. Tras el Pairing, el SDK envía `game_announce` con `game_id`, `game_name` y `capabilities`. El Servidor registra liveness y publica `games_available` al conectar una sesión y ante cambios.
3. Android autoelige el Plugin único o presenta una lista si hay varios; envía `game_select`. El Servidor valida liveness y confirma el id con `game_selected`. Si el id ya no está vivo, envía `games_available` vigente y conserva la fuente previamente confirmada si aún vive.
4. El Plugin seleccionado publica `game_frame` y PCM. Para `fmt:"h264"`, `game_frame` incluye además `ts_capture_us` e `idr`; el Servidor retransmite la AU y esta metadata conforme a SPEC-003 sin sustituir el reloj del encoder. El Servidor adapta/retransmite únicamente esos medios. Sin fuente seleccionada no transmite video ni audio, no captura escritorio ni genera demo/PCM sintético.
5. Android envía `input_report`. El Servidor conserva el último snapshot válido por sesión incluso antes de una selección, envía el snapshot inicial al Plugin al seleccionarlo y reenvía cada actualización posterior aceptada. `poll_input()` devuelve ese snapshot o `None`; los estados intermedios pueden coalescerse y el polling no bloquea la publicación A/V.
6. Al cambiar de Plugin, el Servidor detiene los streams previos antes de iniciar los nuevos. Si la fuente seleccionada desaparece, detiene sus streams y Android procesa la nueva lista.
7. Cemu publica el framebuffer DRC/GamePad internamente sin tope de framerate (sin pacing: cada frame en cuanto captura+encoder lo permiten, CR-029) y PCM DRC, sin ventana separada ni selector, y consume los snapshots de input enviados por el Servidor. Si Twinpad se conecta y Jugador 1 no tiene VPAD emulado, Cemu crea uno temporal en memoria; si ya hay VPAD, lo reutiliza. El temporal no se persiste y se retira al desconectar sin alterar un perfil/asignación configurados. Conforme a CR-012, la salida TV del juego continúa local en Cemu/PC; la única fuente PCM de Cemu que se publica al Plugin SDK y se retransmite a Android es GamePad/DRC. La ausencia o fallo del stream Android no silencia TV y las rutas no se mezclan. El wire de SPEC-005 no cambia.

El host de Cemu conserva “Iniciar conexión” y “Configuraciones”; su política de auto/manual start se mantiene salvo donde contradiga este flujo. Bajo CR-016/CR-017, Configuraciones permite activar un monitor de orientación en una ventana Cemu separada y redimensionable, inicialmente apagado; muestra yaw/pitch/roll recibido y su mapeo al VPAD desde el último snapshot. Sin gyro válido, la ventana indica que espera entrada. No se superpone al juego ni requiere cambiar inputs o contrato wire.


## 8. Alternative flows

### 8.1 Varios Plugins vivos

- **Trigger:** hay más de un Plugin vivo cuando se vincula Android o cambia la lista.
- **Flow:** Android presenta las entradas recibidas en `games_available`; al tocar una envía `game_select`.
- **Result:** solo el Plugin confirmado por `game_selected` alimenta medios e inputs de esa sesión.

### 8.2 Plugin desaparece durante una selección

- **Trigger:** se cierra la conexión primary de un Plugin seleccionado.
- **Flow:** el Servidor deja de considerarlo vivo, envía `stream_stop` y `audio_stop` según corresponda y publica la lista vigente. Android elimina la selección obsoleta; si queda exactamente una fuente, la selecciona automáticamente; si quedan varias, presenta la lista.
- **Result:** no se presentan medios retenidos de la fuente desaparecida; hasta una nueva selección no hay fuente audiovisual.

## 9. Errors

| Condition | Observable behavior | Error code / message |
|---|---|---|
| Anuncio con campos fuera de constraints | anuncio rechazado; el juego no aparece disponible | sin código de sesión (error local del SDK) |
| `game_select` referencia un Plugin que ya no está vivo | Servidor envía la lista `games_available` vigente; conserva la selección previa si sigue viva y de otro modo queda sin selección | sin error de sesión |
| `game_id` duplicado de un juego ya vivo | el segundo anuncio se rechaza | error local del SDK |
| `game_audio_*` sin juego/capability `audio`, o conexión PCM duplicada | conexión de audio rechazada sin afectar la sesión | cierre de conexión IPC |
| Fuente callback sin datos durante el jitter buffer | se mantiene el último estado de audio; al finalizar se envía `game_audio_stop` | sin error final |
| El juego crashea | deja de estar disponible; la sesión (pairing) no se rompe; los streams envían `*_stop` | sin error al usuario |
| La integración host no inicia/reutiliza el servidor o no obtiene el QR vigente | el host informa el fallo y no inicia Pairing | error observable local del host |

## 10. Edge cases

| Case | Expected behavior |
|---|---|
| Anuncio con capacidades vacías | el juego figura como compatible pero sin streams; la app muestra "sin segunda pantalla activa" |
| El server se reinicia con el juego vivo | el juego re-anuncia (el SDK reintenta); mientras no haya anuncio, no disponible |
| Capacidad declarada que luego no se usa | sin efecto: las capacidades son declarativas |

## 11. Business rules

- **R1:** el host solicita el Servidor/QR, pero una vez establecida la conexión del Plugin, el anuncio y liveness son automáticos y no requieren habilitación manual por juego.
- **R2:** la disponibilidad exige anuncio vivo (liveness verificado por el server).
- **R3:** el crash de un juego jamás rompe la sesión de SPEC-001 (REQ-006 §8).
- **R4 (CR-006):** auto-connect Cemu persistente y opt-in, inicialmente desactivado; desactivarlo no cierra una conexión actual.
- **R5 (CR-007/CR-009/CR-010):** la integración host inicia/reutiliza el Servidor PC y presenta el QR vigente; el SDK Rust no incorpora UI ni gestión de procesos. Cemu confirma `cemu_live=true` por loopback antes del QR. El endpoint QR permanece loopback-only.
- **R6 (CR-008/CR-010/CR-029):** Cemu publica internamente la textura DRC/GamePad sin tope de framerate ni pacing (la tasa es la que sostengan captura+encoder); no abre ni requiere “Separate GamePad View” y no altera el render visible normal.
- **R7 (CR-010):** el Servidor retransmite únicamente los medios del Plugin confirmado para la sesión; ningún medio audiovisual se captura/genera en el Servidor.
- **R8 (CR-010):** el snapshot válido más reciente de input se retiene por sesión, se entrega al Plugin seleccionado y se consulta con polling no bloqueante en el SDK; los estados intermedios pueden coalescerse.
- **R9 (CR-010):** una selección obsoleta no interrumpe la fuente actual si aún sigue viva; el Servidor devuelve la lista vigente.
- **R10 (CR-010):** el host de cada Plugin SDK inicia/reutiliza el Servidor PC y presenta el QR; la librería SDK permanece headless.
- **R11 (CR-012):** en el adaptador Cemu, TV solo usa la salida local PC y PCM Android procede solo de DRC/GamePad. Las salidas y volúmenes son independientes; no se mezclan ni se altera TV cuando el transporte Android se detiene. No se añade un segundo stream de sesión.
- **R12 (CR-013):** al conectar Cemu/Twinpad, crear un VPAD temporal en memoria para Jugador 1 solo si no existe uno; reutilizar sin modificar el VPAD existente; nunca persistir el temporal; retirarlo al desconectar y preservar cualquier controlador configurado que haya reemplazado ese temporal.
- **R13 (CR-016/CR-017):** el host Cemu permite activar por preferencia local un monitor visual de orientación en una ventana wxWidgets independiente y redimensionable (640×480 inicial, mínimo 480×360), inicialmente apagado. Presenta el snapshot recibido y las tres vueltas mapeadas al VPAD; se actualiza al llegar nuevos snapshots. Sin gyro válido indica que espera entrada. Desactivar la preferencia o cerrar la ventana la cierra y deja la preferencia apagada; habilitarla abre la ventana. No cambia input, A/V, selección ni contrato wire.

## 12. Contracts

| Contract | Canonical source | Notes |
|---|---|---|
| Campos del anuncio y selección (`game_select`/`game_selected`/`games_available`) | este documento §4/§12 | NDJSON estricto; selección obsoleta conserva fuente viva y retorna lista |
| Canal IPC SDK ↔ Servidor | este documento §4/§12; ADR-008; CR-015 para metadata H.264 | local al PC; medios Plugin→Servidor y snapshot input Servidor→Plugin |
| Consulta de QR Cemu ↔ Servidor | DES-004 (CR-007/CR-009) | TCP 127.0.0.1:8423; petición `GET_QR\n`, respuesta NDJSON `{uri, modules, cemu_live}` |
| Streams que el juego alimenta | SPEC-003 (video), SPEC-005 (audio) | referenciados |
| Diagnóstico de orientación Cemu (CR-016/CR-017) | SPEC-004 §4 | La ventana externa se alimenta del snapshot de `input_report`; no crea un mensaje nuevo ni se transmite fuera del PC. |

## 13. Examples (Given / When / Then)

```gherkin
Scenario: El host de Plugin inicia el servidor y presenta el QR (REQ-006 AC-6)
  Given la integración host del Plugin SDK se inicia
  When el Servidor PC no existe
  Then el host inicia el Servidor y presenta el QR vigente
  When el Servidor PC ya está activo
  Then el host reutiliza la instancia y presenta el QR sin crear otro proceso

Scenario: Selección automática de fuente única
  Given una sesión vinculada y un único Plugin vivo
  When Android recibe games_available
  Then envía game_select para ese game_id y espera game_selected antes de mostrar medios

Scenario: Selección explícita entre varios Plugins
  Given una sesión vinculada y varios Plugins vivos
  When Android recibe games_available
  Then muestra una lista de elección
  When el usuario elige un Plugin vivo
  Then el Servidor confirma game_selected y retransmite solo sus medios

Scenario: Selección obsoleta conserva la fuente viva
  Given un Plugin A seleccionado y vivo
  When Android solicita un Plugin B que dejó de estar vivo
  Then el Servidor conserva A y envía la lista games_available vigente

Scenario: Inputs disponibles antes de seleccionar fuente
  Given una sesión activa sin Plugin seleccionado
  When Android envía input_report válido
  Then el Servidor conserva el snapshot por sesión
  When el usuario selecciona un Plugin
  Then el SDK puede obtener el snapshot mediante poll_input()

Scenario: Sin fuente no se inventan medios
  Given una sesión activa sin Plugin seleccionado
  When el Servidor produce la salida de streams
  Then no captura escritorio ni envía video demo o PCM sintético

Scenario: Plugin seleccionado desaparece
  Given un Plugin seleccionado con streams activos
  When se cierra su conexión primaria
  Then el Servidor detiene sus streams y publica la lista actualizada
  And Android procesa automáticamente una única fuente restante o muestra la lista si hay varias
```

## 14. Invariants

- La lista de juegos disponibles coincide exactamente con los anuncios vivos.
- Ningún stream de SPEC-003/005 emite contenido de un juego no disponible.

## 15. What "complies" means

Cumple este SPEC quien honre §3, produzca §5, reproduzca §9 con sus triggers, cumpla §10, mantenga §14 y pase §16. Lo no definido no es requerido.

## 16. Verification

| Aspect | Verified by |
|---|---|
| Detección automática (AC-1) y liveness | TEST-021 |
| Selección de fuente por sesión y relay A/V | TEST-051 |
| Retención, entrega y polling del snapshot input | TEST-052 |
| Selección automática de una fuente y lista Android para varias | TEST-053 |
| Fuente PCM separada del SDK y protocolo `game_audio_*` | TEST-036 |
| Conexión Cemu title-scoped, reintento, framing IPC y recepción de input | TEST-044 |
| Preferencia Cemu persistente, estado inicial false, activación/desactivación en vivo y opciones del menú | TEST-046 |
| QR vigente consultado por loopback y errores locales observables | TEST-047 |
| Relay Cemu del DRC sin tope de framerate (CR-029), sin selección de pantalla ni captura/demo Server | TEST-054 |
| Resolución de emisión del GamePad aplicada en Cemu: escala del DRC a w/h de `video_config` antes del encode, sin upscale (CR-030) | TEST-088 |
| Cemu TV→PC y DRC/GamePad→Android independientes, sin mezcla y con volúmenes/rutas separados (CR-012) | TEST-062 (gate de banco físico) |
| Anuncio Cemu confirmado antes de mostrar QR; cancelación manual limpia | TEST-050 |
| IPC H.264 conserva timestamp monotónico del encoder e indicador IDR (CR-015) | TEST-073 |
| VPAD temporal de Jugador 1 solo cuando falta, sin sobreescribir ni persistir perfil local (CR-013) | TEST-064 |
| Monitor Cemu opt-in en ventana externa, con orientación recibida y mapeada a VPAD (CR-016/CR-017) | TEST-079 |
| Vibración del VPAD de Cemu: patrón de `VPADControlMotor` reenviado como `rumble` y reproducido en el teléfono a 60 Hz, parada con longitud 0 (CR-033) | TEST-092 |
| Cámara frontal del teléfono enviada a Cemu como `camera_frame` H.264 320x240 a 30 fps, validada y enrutada solo al Plugin Cemu (CR-034) | TEST-093 (core), TEST-094 (servidor), TEST-095 (Android) |
| Demanda de cámara gobernar el envío: `camera_request` Cemu→Android con transiciones del uso HLE y gating combinado (juego «cemu» && demanda vigente) antes de encender la cámara frontal (CR-038) | TEST-097 (core), TEST-098 (servidor), TEST-099 (Android), TEST-0100 (Cemu) |

## 17. Open questions

- ¿Lenguajes/runtimes soportados por el SDK en v1 (C/C++ nativo, otros)? — owner: Eng lead — 2026-10-07 — **resuelta por el owner (usuario): SDK v1 como crate Rust (`twinpad-sdk`) con API de anuncio; el wrapper C/C++ (motores/AAA) entra como capa FFI posterior.**
- ¿Mecanismo IPC concreto (named pipe, localhost socket)? — owner: Eng lead — 2026-10-07 — **resuelta por el owner (usuario): TCP localhost (127.0.0.1:8421 por defecto) con NDJSON, mismo estilo de framing que la sesión de SPEC-001; ver ADR-008. La conexión viva ES el anuncio vivo (liveness R2/§9); named pipes de Windows quedan como alternativa futura.**

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Eng lead + agent | Draft inicial: detección de juego con SDK |
| 1.0.0 | 2026-10-08 | Owner (usuario) | Approved: IPC por TCP localhost NDJSON (ADR-008); SDK v1 en Rust; publicación a la app incluida (AC-1 completo) |
| 1.1.0 | 2026-10-17 | Owner + Twinpad team (CR-003) | Segunda conexión IPC para PCM y source callback con cola bounded drop-old |
| 1.2.0 | 2026-10-17 | Product owner + Twinpad team (CR-004) | Añade excepción experimental Cemu con captura GamePad opt-in inicialmente limitada a 5 fps (límite histórico supersedido por CR-010) y PCM DRC desacoplado |
| 1.3.0 | 2026-10-18 | Product owner + Twinpad team (CR-005) | Cemu conecta/reintenta por título y consume input_report del GamePad emulado; conserva fallback de video actual |
| 1.4.0 | 2026-10-18 | Product owner + Twinpad team (CR-006) | Auto-connect Cemu persistente opt-in y menú Twinpad con inicio manual/configuraciones |
| 1.5.0 | 2026-10-18 | Product owner + Twinpad team (CR-007) | “Iniciar conexión” inicia/reutiliza el Servidor PC, consulta el QR vigente solo por loopback, lo muestra antes de iniciar el adaptador y pregunta al cerrar si detener el proceso propio |
| 1.6.0 | 2026-10-18 | Product owner + Twinpad team (CR-008) | Cemu publica la imagen DRC automáticamente sin abrir Separate GamePad View ni pedir seleccionar pantalla; el límite inicial de 5 fps fue supersedido por CR-010 |
| 1.7.0 | 2026-10-18 | Product owner + Twinpad team (CR-009) | Cemu se registra antes del QR para que el primer Pairing seleccione DRC; captura desktop se limita a pruebas explícitas |
| 1.8.0 | 2026-10-18 | Product owner + Twinpad team (CR-010) | El host inicia/reutiliza servidor y presenta QR; selección de Plugin Android, relay exclusivo, polling de input y DRC hasta 60 fps |
| 1.9.0 | 2026-10-19 | Product owner + Twinpad team (CR-012) | Audio TV permanece local; PCM DRC/GamePad Cemu es la única fuente de Android; no cambia el contrato wire |
| 1.10.0 | 2026-10-19 | Product owner + Twinpad team (CR-013) | Cemu crea temporalmente el VPAD de Jugador 1 si falta; conserva configuraciones existentes y no persiste el temporal |
| 1.11.0 | 2026-10-20 | Product owner (CR-015) | Define `ts_capture_us` e `idr` obligatorios en `game_frame(fmt=h264)` para preservar metadata del encoder en el relay |
| 1.12.0 | 2026-10-22 | Product owner + Twinpad team (CR-016) | Añade monitor Cemu opt-in de orientación recibida y mapeada a VPAD, sin cambio del protocolo |
| 1.13.0 | 2026-10-22 | Product owner + Twinpad team (CR-017) | Mueve el monitor Cemu a ventana wxWidgets independiente y ampliable; sin cambio wire |
| 1.14.0 | 2026-10-27 | Product owner + Twinpad team (CR-023) | SDK Twinpad como vía principal del giroscopio para terceros; Config externa y gamepad estándar sin giroscopio; sin cambio wire |
| 1.15.0 | 2026-10-28 | Product owner (CR-029) | Cemu publica la DRC sin tope de framerate ni pacing; el wire de SPEC-005 no cambia |
| 1.16.0 | 2026-10-28 | Product owner (CR-030) | El DRC se escala a la resolución de `video_config` antes del encode (nearest, sin upscale); sin cambio wire |
| 1.17.0 | 2026-10-28 | Product owner (CR-033) | Añade el mensaje Cemu→Android `rumble` con el patrón de `VPADControlMotor` (máx. 120 bytes, parada con longitud 0) y su reproducción con vibrador a 60 Hz |
| 1.18.0 | 2026-10-28 | Product owner (CR-034) | Añade el mensaje Android→Cemu `camera_frame` (H.264 320x240, 30 fps) con cámara frontal; sin cambio en el resto del wire |
| 1.19.0 | 2026-10-29 | Product owner (CR-038) | Añade el mensaje Cemu→Android `camera_request` (transiciones del uso HLE de cámara del juego); Android solo enciende la cámara frontal con juego seleccionado Y demanda vigente |
