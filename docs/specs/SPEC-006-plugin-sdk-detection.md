# SPEC-006 — Detección de juego con SDK Twinpad (modelo B)

| | |
|---|---|
| ID | SPEC-006 |
| Title | Detección de juego con SDK Twinpad (REQ-006) |
| Version | 1.6.0 |
| Status | `Approved` |
| Owner | Eng lead |
| Implements | REQ-006 (AC-1; contratos de AC-2/AC-3 quedan en el SDK) |
| Realized by | DES-004 (CR-005/CR-006/CR-007/CR-008, Draft; adaptador Cemu) |
| Verified by | TEST-021, TEST-044, TEST-046, TEST-047, TEST-048, TEST-049 |
| Last updated | 2026-10-18 |

## 1. Purpose

Define cómo el Servidor PC detecta automáticamente que un Juego / Aplicación huesped con SDK Twinpad integrado está en ejecución (REQ-006 AC-1) y qué estado observable produce esa detección: el juego aparece como "compatible disponible" para la app y sus streams pueden activarse.

## 2. Definitions

- **Juego compatible**: juego con SDK Twinpad integrado en ejecución en el PC Host.
- **Anuncio**: identificación que el juego entrega al Servidor al arrancar (nombre, id de juego, capacidades).
- Ver CTX §8: Plugin, SDK Twinpad, Servidor PC.

## 3. Preconditions

- Servidor Twinpad activo (REQ-001).
- Un juego con SDK integrado arranca en el mismo PC (mismo host, sin detección por red), o se ejecuta el adaptador experimental Cemu autorizado por CR-004.

## 4. Inputs

| Field | Type | Constraints | Required |
|---|---|---|---|
| `game_id` | string | 1–64 chars, `[a-z0-9_.-]`, único por juego | yes |
| `game_name` | string | 1–128 chars legibles | yes |
| `capabilities` | conjunto | subconjunto de `second_screen`, `audio`, `input_consumption` | yes |

Canal: TCP localhost NDJSON. El anuncio y `game_frame` usan la conexión primaria del SDK al listener `127.0.0.1:8421` (ADR-008). La fuente PCM abre una segunda conexión a `127.0.0.1:8422`, independiente de los frames, que envía `game_audio_start`, `game_audio_chunk` y `game_audio_stop`, con `game_id` y PCM decodificado máximo 16 KiB por chunk. Solo se acepta audio de un `game_id` vivo anunciado y con capability `audio`; una segunda conexión de audio del mismo juego o juego desconocido se cierra. El SDK consume la fuente callback en hilo dedicado y usa una cola acotada de 8 chunks: al llenarse descarta el chunk más antiguo, sin bloquear `push_frame`.

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

Invariant: solo juegos con anuncio vivo figuran como disponibles; un crash nunca deja un "fantasma".

## 7. Main flow

1. El juego con SDK envía su anuncio (`game_id`, `game_name`, `capabilities`) al Servidor. Para Cemu experimental, al elegir “Iniciar conexión” con un título activo, Cemu inicia `twinpad-server` desde PATH si no hay una instancia local que responda; luego obtiene el QR vigente por TCP solo en `127.0.0.1:8423`, lo presenta en Cemu y, al cerrarlo, inicia la conexión del adaptador. El endpoint devuelve el URI canónico y la matriz vigente derivados del TokenStore del Servidor PC; no emite tokens nuevos. La conexión automática sigue siendo persistente y false por defecto; si se habilita con título activo y no existe conexión, la conexión comienza inmediatamente; deshabilitarla no desconecta una conexión existente y solo impide auto-inicio en títulos posteriores. El servidor iniciado por Cemu permanece activo tras el título; al cerrar Cemu se pregunta si detener únicamente ese proceso. Con adaptador iniciado, Cemu solo mientras el título esté activo conecta/reintenta cada 2 s y anuncia. Mientras la conexión Cemu esté viva, el Server espera el primer framebuffer DRC/GamePad en lugar de escoger desktop/demo; al llegar, Cemu lo captura internamente aunque “Separate GamePad View” esté cerrada, limitada a 5 fps, y conserva PCM DRC 48 kHz desacoplado. No abre la vista separada ni solicita elegir una pantalla; el render visual normal de Cemu no cambia. Al salir del título la conexión y captura se detienen.
2. El Servidor valida el anuncio (§4) y lo registra como disponible.
3. Con sesión autenticada, el Servidor publica la lista de juegos disponibles a la app.
4. La app muestra el juego como compatible disponible (REQ-006 AC-1).
5. Si el juego declara `audio`, el SDK abre la conexión de audio separada y consume el callback de fuente PCM; `game_audio_start` anuncia rate/canales/s16le, los chunks incluyen `seq` incremental y `data` base64url (≤16 KiB decodificados), y `game_audio_stop` termina la fuente. Cierre/crash de la conexión primary o de audio termina el stream, sin afectar la sesión de Pairing.
6. Para Cemu (CR-005), el Servidor reenvía cada `input_report` válido desde la primera sesión autenticada que envía inputs al socket primario del anuncio activo, como NDJSON con LF; el adaptador aplica el reporte al GamePad Wii U emulado. Mientras está conectado, Twinpad es la fuente exclusiva de controles VPAD y los mappings locales se ignoran. Bajo CR-008, el origen de video Twinpad de Cemu es la textura DRC capturada internamente y no requiere “Separate GamePad View”; el selector normal de esa vista permanece independiente para pruebas/depuración. Los streams genéricos SDK y su fallback conservan SPEC-003; no se agrega selección de pantalla al flujo Cemu.

El menú superior “Twinpad” contiene exactamente los elementos “Iniciar conexión” y “Configuraciones”. “Iniciar conexión” se deshabilita sin título activo y no inicia otra conexión si el cliente ya está activo. Antes de solicitar la conexión muestra una ventana con el QR vigente; si el inicio/consulta local falla, informa el error y no inicia el adaptador. “Configuraciones” abre una ventana modal con el control persistente “Conexión automática”.

## 8. Alternative flows

### 8.1 Dos juegos con SDK simultáneos

- **Trigger:** un segundo juego se anuncia mientras el primero vive.
- **Flow:** ambos quedan listados; la app/user elige con cuál operar; los streams de SPEC-003/005 emiten del juego elegido.
- **Result:** cada anuncio es independiente; no hay conflicto de `game_id` (id distinto por juego).

## 9. Errors

| Condition | Observable behavior | Error code / message |
|---|---|---|
| Anuncio con campos fuera de constraints | anuncio rechazado; el juego no aparece disponible | sin código de sesión (error local del SDK) |
| `game_id` duplicado de un juego ya vivo | el segundo anuncio se rechaza | error local del SDK |
| `game_audio_*` sin juego/capability `audio`, o conexión PCM duplicada | conexión de audio rechazada sin afectar la sesión | cierre de conexión IPC |
| Fuente callback sin datos durante el jitter buffer | se mantiene el último estado de audio; al finalizar se envía `game_audio_stop` | sin error final |
| El juego crashea | deja de estar disponible; la sesión (pairing) no se rompe; los streams envían `*_stop` | sin error al usuario |
| No se puede iniciar el servidor o consultar su QR vigente | Cemu muestra un error y no inicia el adaptador | error observable local de Cemu |

## 10. Edge cases

| Case | Expected behavior |
|---|---|
| Anuncio con capacidades vacías | el juego figura como compatible pero sin streams; la app muestra "sin segunda pantalla activa" |
| El server se reinicia con el juego vivo | el juego re-anuncia (el SDK reintenta); mientras no haya anuncio, no disponible |
| Capacidad declarada que luego no se usa | sin efecto: las capacidades son declarativas |

## 11. Business rules

- **R1:** la detección de anuncios de juegos SDK es automática: ningún paso del usuario habilita un juego compatible (REQ-006 AC-1). La conexión del adaptador Cemu es una excepción experimental controlada por CR-006.
- **R2:** la disponibilidad exige anuncio vivo (liveness verificado por el server).
- **R3:** el crash de un juego jamás rompe la sesión de SPEC-001 (REQ-006 §8).
- **R4 (CR-006):** auto-connect Cemu persistente y opt-in, inicialmente desactivado; desactivarlo no cierra una conexión actual.
- **R5 (CR-007):** “Iniciar conexión” muestra en Cemu el QR vigente antes de solicitar el adaptador. El Servidor PC atiende su consulta únicamente en loopback; Cemu reutiliza una instancia local o inicia el servidor por PATH. Al cerrar, solo pregunta si detener la instancia que inició.
- **R6 (CR-008):** con el adaptador Cemu conectado y un título activo, la captura DRC es interna, automática, independiente de la ventana “Separate GamePad View” y limitada a 5 fps; no pregunta ni cambia el render visible normal. Esa opción de Cemu no se elimina y es independiente para pruebas/depuración.

## 12. Contracts

| Contract | Canonical source | Notes |
|---|---|---|
| Campos del anuncio | este documento §4 | v1 |
| Canal IPC SDK ↔ Servidor | DES-004 (adaptador Cemu, CR-005) / ADR-008 (SDK) | local al PC |
| Consulta de QR Cemu ↔ Servidor | DES-004 (CR-007) | TCP 127.0.0.1:8423; petición `GET_QR\n`, respuesta NDJSON `{uri, modules}` |
| Streams que el juego alimenta | SPEC-003 (video), SPEC-005 (audio) | referenciados |

## 13. Examples (Given / When / Then)

```gherkin
Scenario: Detección automática de juego SDK (REQ-006 AC-1)
  Given el servidor activo y ningún juego vivo
  When un juego con SDK arranca y anuncia game_id="pacdemo" con capabilities=[second_screen,audio]
  Then el juego figura como compatible disponible y la app lo muestra

Scenario: Crash no rompe la sesión (REQ-006 §8)
  Given "pacdemo" disponible y streams de video/audio activos
  When el proceso del juego crashea
  Then "pacdemo" deja de estar disponible, los streams envían *_stop
  And la sesión de pairing y la app siguen operativas

Scenario: Inicio manual del adaptador Cemu (TEST-046)
  Given Cemu tiene un título activo y la conexión automática está desactivada
  When el usuario elige "Iniciar conexión"
  Then Cemu solicita la conexión, anuncia el título y activa la captura GamePad
  And si no hay un título activo, "Iniciar conexión" está deshabilitado

Scenario: Activar auto-connect durante el título (TEST-046)
  Given un título está activo y no hay conexión Twinpad solicitada
  When el usuario activa "Conexión automática" en "Configuraciones" y confirma
  Then la preferencia se guarda y la conexión empieza inmediatamente
  When el usuario desactiva "Conexión automática" y confirma
  Then la preferencia persistida queda apagada
  And una conexión ya solicitada permanece conectada hasta que termine el título

Scenario: Mostrar el QR vigente antes de conectar (TEST-047)
  Given Cemu tiene un título activo y Twinpad no está solicitado
  When el usuario elige “Iniciar conexión”
  Then Cemu obtiene el URI y la matriz del QR vigente desde el Servidor PC por 127.0.0.1
  And muestra el QR en una ventana Cemu
  When el usuario cierra la ventana QR
  Then Cemu solicita la conexión del adaptador

Scenario: Fallo al iniciar/consultar el servidor
  Given no responde un servidor local
  When Cemu no puede iniciar twinpad-server desde PATH o consultar el QR
  Then Cemu muestra un error
  And no solicita la conexión del adaptador

Scenario: Captura automática y oculta del DRC para Twinpad (TEST-048)
  Given Cemu tiene un título activo, Twinpad está conectado y “Separate GamePad View” está cerrada
  When el juego renderiza el framebuffer DRC
  Then Twinpad recibe la imagen del GamePad automáticamente, como máximo a 5 fps
  And el audio DRC continúa transmitiéndose por el canal existente
  And Cemu no abre la vista separada ni pregunta qué pantalla transmitir
  And la salida visual normal de Cemu no cambia

Scenario: Preferencia Twinpad y elementos del menú (TEST-046)
  Given no existe valor guardado para twinpad_auto_connect
  When Cemu carga la configuración
  Then la preferencia vale false
  And el menú Twinpad contiene exactamente "Iniciar conexión" y "Configuraciones"
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
| Fuente PCM separada del SDK y protocolo `game_audio_*` | TEST-036 |
| Conexión Cemu title-scoped, reintento, framing IPC y recepción de input | TEST-044 |
| Preferencia Cemu persistente, estado inicial false, activación/desactivación en vivo y opciones del menú | TEST-046 |
| QR vigente consultado por loopback, mostrado antes de conectar; errores locales observables | TEST-047 |
| Captura DRC automática y oculta, limitada a 5 fps, sin selección de pantalla | TEST-048, TEST-049 |

## 17. Open questions

- ¿Lenguajes/runtimes soportados por el SDK en v1 (C/C++ nativo, otros)? — owner: Eng lead — 2026-10-07 — **resuelta por el owner (usuario): SDK v1 como crate Rust (`twinpad-sdk`) con API de anuncio; el wrapper C/C++ (motores/AAA) entra como capa FFI posterior.**
- ¿Mecanismo IPC concreto (named pipe, localhost socket)? — owner: Eng lead — 2026-10-07 — **resuelta por el owner (usuario): TCP localhost (127.0.0.1:8421 por defecto) con NDJSON, mismo estilo de framing que la sesión de SPEC-001; ver ADR-008. La conexión viva ES el anuncio vivo (liveness R2/§9); named pipes de Windows quedan como alternativa futura.**

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Eng lead + agent | Draft inicial: detección de juego con SDK |
| 1.0.0 | 2026-10-08 | Owner (usuario) | Approved: IPC por TCP localhost NDJSON (ADR-008); SDK v1 en Rust; publicación a la app incluida (AC-1 completo) |
| 1.1.0 | 2026-10-17 | Owner + Twinpad team (CR-003) | Segunda conexión IPC para PCM y source callback con cola bounded drop-old |
| 1.2.0 | 2026-10-17 | Product owner + Twinpad team (CR-004) | Añade excepción experimental Cemu con captura GamePad opt-in limitada a 5 fps y PCM DRC desacoplado |
| 1.3.0 | 2026-10-18 | Product owner + Twinpad team (CR-005) | Cemu conecta/reintenta por título y consume input_report del GamePad emulado; conserva fallback de video actual |
| 1.4.0 | 2026-10-18 | Product owner + Twinpad team (CR-006) | Auto-connect Cemu persistente opt-in y menú Twinpad con inicio manual/configuraciones |
| 1.5.0 | 2026-10-18 | Product owner + Twinpad team (CR-007) | “Iniciar conexión” inicia/reutiliza el Servidor PC, consulta el QR vigente solo por loopback, lo muestra antes de iniciar el adaptador y pregunta al cerrar si detener el proceso propio |
| 1.6.0 | 2026-10-18 | Product owner + Twinpad team (CR-008) | Cemu captura la imagen DRC automáticamente sin abrir Separate GamePad View ni pedir seleccionar pantalla; conserva audio DRC y el límite de 5 fps |
