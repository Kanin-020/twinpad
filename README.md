# Twinpad

Segunda pantalla y gamepad HID para PC, controlados desde un celular Android. Se vinculan escaneando un QR en la red local.

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
