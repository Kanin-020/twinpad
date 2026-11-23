# Twinpad — guía de la release

Twinpad convierte un teléfono Android en la segunda pantalla y el gamepad de tu PC: el servidor captura la pantalla del juego, el celular la muestra y envía los controles por la red local, y la vinculación se hace con un QR.

Esta release trae cuatro piezas. Cada una se instala por separado; abajo se explica qué es y cómo configurarla.

| Pieza | Archivo | Dónde corre |
|---|---|---|
| El server | `twinpad-server-<versión>-x86_64.AppImage` | PC con Linux x86_64 |
| Cemu | `cemu-linux-x86_64-<tag>.tar.gz` | PC con Linux x86_64 |
| Android | `twinpad-android-<versión>-debug-signed.apk` | Teléfono Android |
| Decky | `decky-twinpad-<versión>.zip` | Steam Deck / SteamOS |

---

## El server

**Qué es.** El proceso que corre en el PC. Captura el escritorio o la ventana del juego, atiende la sesión con el teléfono, sirve la página de gestión local y emite el QR de vinculación. Es la pieza que hay que arrancar primero: sin él, Cemu, Android y Decky no tienen a quién conectarse.

**Cómo configurarlo.**

1. Descarga el AppImage y dale permisos de ejecución:
   ```bash
   chmod +x twinpad-server-<versión>-x86_64.AppImage
   ./twinpad-server-<versión>-x86_64.AppImage
   ```
2. La página de gestión queda solo en tu equipo, en <http://127.0.0.1:8430>. Ahí ves el **QR de vinculación** vigente, puedes **regenerarlo**, ver el estado de la sesión y **detener o arrancar** el servidor.
3. Si vuelves a abrir el AppImage con el servidor ya en marcha, no arranca un segundo proceso: solo abre la página de gestión.
4. La captura usa PipeWire en Linux, así que necesita una sesión gráfica Wayland/PipeWire funcionando.
5. El servidor se anuncia en la red local por mDNS (`_twinpad._tcp.local.`), de modo que el teléfono puede reencontrarlo si cambia la IP; si mDNS no está disponible, la vinculación por IP sigue funcionando.

El firewall del PC debe permitir el tráfico entrante del teléfono en la red local.

---

## Cemu

**Qué es.** Un fork de Cemu (el emulador de Wii U) con el soporte de Twinpad integrado: reenvía a la segunda pantalla lo que el juego dibuja en el GamePad y suma los canales de entrada, audio y micrófono del teléfono. Sirve para jugar títulos de Wii U con la pantalla secundaria real.

**Cómo configurarlo.**

1. Extrae el paquete:
   ```bash
   tar -xzf cemu-linux-x86_64-<tag>.tar.gz
   cd cemu
   ./Cemu
   ```
2. Arranca **primero El server** y deja la página de gestión abierta para vincular el teléfono. El fork se conecta a los canales locales del servidor cuando inicia una partida.
3. Es el Cemu de siempre: la configuración de gráficos, audio y perfiles de juego se hace en su propia interfaz. El paquete incluye `resources` y `gameProfiles`, así que no hace falta una instalación previa de Cemu.
4. Necesita las dependencias gráficas habituales de Cemu en Linux (Vulkan u OpenGL) y una GPU con driver actualizado.

---

## Android

**Qué es.** La app del teléfono. Muestra la segunda pantalla que le manda el servidor y funciona como gamepad HID: D-pad, botones A/B/X/Y y los controles táctiles del juego.

**Cómo configurarlo.**

1. Instala el APK en el teléfono (por ejemplo `adb install twinpad-android-<versión>-debug-signed.apk`, o abriéndolo desde el explorador de archivos con la instalación de orígenes desconocidos permitida).
2. Conecta el teléfono y el PC a **la misma red local** y arranca El server.
3. Abre la app, entra en **Nueva conexión** y escanea el QR que muestra la página de gestión del servidor (o abre la lista de conexiones si ya vinculaste ese equipo antes).
4. La vinculación queda guardada: en la siguiente sesión aparece en **Conexiones** y se puede reconectar sin volver a escanear.

> El APK está firmado con la clave de depuración: es para pruebas, no para distribución en tiendas.

---

## Decky

**Qué es.** El plugin de Decky Loader que lleva el panel de Twinpad al menú de acceso rápido de Steam (Steam Deck / SteamOS). Desde ahí ves el QR de vinculación con su cuenta atrás, lo regeneras, copias la URI de vinculación, consultas el estado de la sesión y arrancas o detienes el servidor sin salir del modo juego.

**Cómo configurarlo.**

1. Descarga `decky-twinpad-<versión>.zip` y extrae la carpeta `Twinpad`:
   ```bash
   unzip decky-twinpad-<versión>.zip
   cp -r Twinpad ~/homebrew/plugins/
   ```
2. Recarga Decky (`Ajustes → Decky → Recargar`). El plugin aparece como **Twinpad** en el menú de acceso rápido.
3. El servidor debe correr **en el mismo dispositivo** que Decky: la página de gestión es solo local (loopback) y no se publica a la red. Si el binario no está en el `PATH`, indica su ruta con `TWINPAD_SERVER_BIN`; el puerto local se cambia con `TWINPAD_LOCAL_PORT`.
4. El autoarranque del servidor funciona en modo escritorio; en modo juego SteamOS no ejecuta el autoarranque XDG, así que ahí hay que arrancarlo desde el propio panel.

---

## Requisitos comunes

- PC con Linux x86_64 para El server y para el fork de Cemu.
- Teléfono Android y PC en la misma red local.
- Puertos de la red local accesibles desde el teléfono.

Los binarios de esta release se compilan desde el código de la etiqueta publicada. Para reportar fallos o revisar el código, usa el repositorio.
