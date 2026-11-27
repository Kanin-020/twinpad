# Twinpad

Segunda pantalla y gamepad HID para PC, controlados desde un celular Android. Se vinculan escaneando un QR en la red local.

## Funcionalidades que Twinpad emula

Twinpad no hace mirroring del escritorio ni captura medios por su cuenta: el Plugin del juego publica lo que quiere mostrar y Twinpad lo adapta, lo transporta y lo presenta en el teléfono. Estas son las capacidades que el sistema emula o sustituye hoy:

| Función | Qué aporta Twinpad | Transporte | Estado y límites |
|---|---|---|---|
| Segunda pantalla | Convierte el teléfono en la pantalla del juego con frames H.264 publicados por el Plugin (por ejemplo, el DRC de Cemu). No es mirroring del escritorio. | LAN | Implementado. Solo contenido publicado por el Plugin; sin Plugin no hay imagen. |
| Toque en la Segunda pantalla | Convierte el toque sobre la imagen en `touch_x`/`touch_y` del GamePad. | LAN | Implementado. Se conserva la última coordenada al soltar. |
| Controles táctiles | Superficie de la app con sticks, D-pad, botones y triggers que se comportan como un gamepad. | Bluetooth HID o LAN | Implementado. El editor de perfiles táctiles (REQ-008) sigue en Draft. |
| Mando físico del teléfono | Un gamepad conectado por USB/Bluetooth al Android se fusiona con los controles táctiles y se envía al juego. | Bluetooth HID o LAN | Implementado (CR-028). Los botones fuera de la Signature se ignoran. |
| Acelerómetro y giroscopio | Envía movimiento del teléfono como movimiento del GamePad Wii U de Cemu o del DualSense virtual; incluye monitores de orientación opcionales. | LAN / SDK | Implementado. No viaja por Bluetooth HID; para terceros, solo por el SDK Twinpad. |
| Vibración / rumble | Hace vibrar el teléfono con la vibración que pide el juego: patrones de Cemu, output reports del gamepad BT o intensidades de los motores del DualSense virtual. | Bluetooth HID o LAN | Implementado. Si el dispositivo no tiene vibrador, el mensaje se ignora. |
| Audio del juego | Reproduce en el teléfono el PCM que publica el Plugin (en Cemu, el audio GamePad/DRC). | LAN | Implementado. El audio de TV del juego sigue sonando en la PC; las rutas no se mezclan. |
| Micrófono | Usa el micrófono del teléfono como micrófono del GamePad de Cemu, bajo demanda del juego. | LAN | Implementado para Cemu. Requiere permiso `RECORD_AUDIO`, demanda vigente y el interruptor del drawer. |
| Cámara | Usa la cámara del teléfono como cámara del GamePad de Cemu, con lente frontal o trasera. | LAN | Implementado para Cemu. H.264 320×240 a 30 fps, solo bajo demanda del juego y opt-in del usuario. |
| NFC / Amiibo | Lee un Amiibo con el NFC del teléfono y Cemu lo monta como tag virtual con una copia por UID. | LAN | Implementado para Cemu. Lectura de solo lectura: nunca escribe el tag físico. |
| Teclado | Muestra el teclado del GamePad de Cemu en el IME del teléfono y devuelve el texto, la confirmación o la cancelación. | LAN | Implementado para Cemu. Solo aparece con demanda vigente del juego. |
| Gamepad HID Bluetooth | El teléfono se presenta al PC como un gamepad HID estándar, sin drivers adicionales. | Bluetooth | Implementado. Lleva inputs y vibración; no lleva vídeo, audio, cámara, micrófono, NFC, touch ni giroscopio. |
| Mando virtual DualSense | El servidor crea un DualSense virtual por `/dev/uhid`; el PC y Steam lo ven como un mando USB con touchpad, giroscopio, acelerómetro y botón PS. | LAN para inputs; USB para el sistema | Implementado solo en Linux con `/dev/uhid`. Al activarlo, el teléfono suspende su HID Bluetooth y los inputs van por LAN; la vibración vuelve al teléfono. |
| Transporte LAN | Sesión autenticada por TCP y canal local del Plugin; transporta control, vídeo, audio, cámara, micrófono, NFC, touch, movimiento y rumble. | LAN | Implementado. Solo red local; no hay streaming por internet ni remote play. |
| Transporte Bluetooth | Enlace HID clásico para usar el teléfono como gamepad incluso sin WiFi. | Bluetooth | Implementado. Es un modo alternativo de controles; la Segunda pantalla y el audio quedan fuera. |
| Vinculación y descubrimiento | QR, código de vinculación manual cuando no hay cámara y descubrimiento mDNS del servidor. | LAN | Implementado. El QR caduca y se puede regenerar desde la página local o Decky. |

El detalle normativo de cada fila está en [SPEC-004](docs/specs/SPEC-004-signature-hid.md), [SPEC-005](docs/specs/SPEC-005-audio-stream.md) y [SPEC-006](docs/specs/SPEC-006-plugin-sdk-detection.md).

## Para usuarios

### Qué necesitas

- Un PC con el **Servidor PC de Twinpad** (`twinpad-server`) en ejecución.
- Un celular Android con la app de Twinpad.
- Ambos en la **misma red WiFi**.

### Vincular el celular

1. Arranca `twinpad-server` en el PC.
2. Abre la página local del servidor en el navegador del PC: `http://127.0.0.1:8430`. Ahí aparece el QR de vinculación.
3. En el celular, abre Twinpad y escanea el QR.
4. La app queda vinculada. Si el QR caduca, pulsa **Regenerar QR** en la página o vuelve a escanear el nuevo.

### Configurar el servidor

- **Iniciar al arrancar el equipo:** en la página local, activa **Iniciar al arrancar el equipo**. Se crea una entrada de autoarranque XDG (`$XDG_CONFIG_HOME/autostart`, o `~/.config/autostart` si no está definida). También puedes activarlo desde el icono de bandeja del servidor.
- **Puertos:** el servidor usa 8420 (sesión), 8430 (página local, solo en este equipo) y 8421 para el SDK de juegos (solo loopback). Los argumentos opcionales cambian los puertos en este orden: `twinpad-server [puerto_sesion] [puerto_sdk] [puerto_pagina]`.
- **Detener:** desde la página local (**Detener servidor**) o cerrando el proceso.

### Steam Deck / SteamOS

El plugin de Decky Loader muestra en el Quick Access Menu el mismo QR, el estado y los botones de arrancar, detener y autoarranque. Se publica en su propio repositorio: [Kanin-020/decky-twinpad](https://github.com/Kanin-020/decky-twinpad/releases/latest). Descarga la última release y sigue sus instrucciones. El servidor debe correr en el mismo equipo que Decky, porque la página de gestión solo acepta conexiones locales.

### Estado actual

El flujo funciona de punta a punta en pruebas con hardware real: vinculación por QR, Segunda pantalla con el escritorio del PC y controles táctiles (D-pad y A/B/X/Y) enviando entradas al servidor.

## Para desarrolladores

### Requisitos

| Herramienta | Versión |
|---|---|
| Rust + cargo | stable ≥ 1.85 |
| JDK | 21 (Temurin) |
| Android SDK | platforms 34/35 + build-tools; `ANDROID_HOME` apuntando al SDK |
| Gradle | no se instala: se usa el wrapper (`android/gradlew`, descarga 8.10.2 al primer uso) |

### Compilar y verificar (gates)

Rust, en la raíz del workspace:

```bash
cargo fmt --all -- --check                            # formato (gate)
cargo clippy --workspace --all-targets -- -D warnings # lints (gate)
cargo build --workspace                               # compilar
cargo test --workspace                                # tests unitarios (gate)
```

Android, desde `android/`:

```bash
./gradlew ktlintCheck detekt   # lints Kotlin (gate)
./gradlew testDebugUnitTest    # tests unitarios (gate)
./gradlew assembleDebug        # APK debug: android/app/build/outputs/apk/debug/app-debug.apk
```

El CI (`.github/workflows/ci.yml`) ejecuta estos mismos gates en cada push y PR ([workflow §8](docs/process/workflow.md)).

### Estructura

```text
core/               crate Rust: protocolo de sesión y tipos (sin I/O)
server/             Servidor PC + Twinpad Core (capture/, config-loader/)
sdk/                SDK Twinpad para juegos (REQ-006)
android/app|hid/    app Android y Motor HID (Kotlin)
integrations/       plugin Decky y otras integraciones
docs/               documentación SDD (orden de lectura: docs/README.md)
```

### Desarrollo guiado por especificaciones

El comportamiento vive en `docs/specs/`, y no se implementa nada sin una SPEC `Approved` ([workflow](docs/process/workflow.md)). La matriz de qué prueba qué está en [`docs/testing/traceability.md`](docs/testing/traceability.md).

Para agentes de IA: leer [`AGENTS.md`](AGENTS.md) antes de tocar código.
