# Twinpad — guía de la release

Twinpad convierte un teléfono Android en la segunda pantalla y el gamepad de tu PC: el servidor captura la pantalla del juego, el celular la muestra y envía los controles por la red local, y la vinculación se hace con un QR.

Esta release trae dos piezas del repositorio principal, con la misma etiqueta: El server y Android. Cada una se instala por separado; abajo se explica qué es y cómo configurarla. Las integraciones (Cemu, Dusklight y Decky) se compilan y publican en sus propios repositorios, no en esta release.

| Pieza | Archivo | Dónde corre |
|---|---|---|
| El server | `twinpad-server-<versión>-x86_64.AppImage` | PC con Linux x86_64 |
| Android | `twinpad-android-<versión>-debug-signed.apk` | Teléfono Android |

---

## Integraciones compatibles

El servidor y Android de esta release comparten la etiqueta y el contrato `twinpad.v1`. Las integraciones se publican por separado y deben hablar ese mismo contrato; descarga siempre la última release de su repositorio.

| Integración | Qué aporta | Última release |
|---|---|---|
| Cemu | Fork de Cemu con segunda pantalla, entrada, audio y micrófono | <https://github.com/Kanin-020/Cemu/releases/latest> |
| Dusklight | Fork de Dusklight para Android con pantalla dual y entrada Twinpad | <https://github.com/Kanin-020/dusklight/releases/latest> |
| Decky | Panel de Twinpad en el Quick Access Menu de Steam Deck | <https://github.com/Kanin-020/decky-twinpad/releases/latest> |

El archivo `compatibility.json` adjunto a esta release registra la etiqueta del servidor y Android, el contrato común y el repositorio de cada integración. Si una integración publica un contrato distinto, no es compatible con esta release hasta que se actualice.

---

## El server

**Qué es.** El proceso que corre en el PC. Captura el escritorio o la ventana del juego, atiende la sesión con el teléfono, sirve la página de gestión local y emite el QR de vinculación. Es la pieza que hay que arrancar primero: sin él, Cemu, Dusklight, Android y Decky no tienen a quién conectarse.

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

**Qué es.** Un fork de Cemu (el emulador de Wii U) con el soporte de Twinpad integrado: reenvía a la segunda pantalla lo que el juego dibuja en el GamePad y suma los canales de entrada, audio y micrófono del teléfono.

**Dónde descargarlo.** El fork de Cemu se compila y publica en su propio repositorio: <https://github.com/Kanin-020/Cemu/releases/latest>. Descarga el paquete para Linux x86_64 de ahí.

Arranca **primero El server** y deja la página de gestión abierta para vincular el teléfono. El fork se conecta a los canales locales del servidor cuando inicia una partida.

---

## Dusklight

**Qué es.** Un fork de Dusklight con pantalla dual para Android y soporte de Twinpad como dispositivo de entrada. El teléfono muestra la segunda pantalla y sus controles se aplican al juego.

**Dónde descargarlo.** El fork se compila y publica en su propio repositorio: <https://github.com/Kanin-020/dusklight/releases/latest>. Descarga el APK de ahí.

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

1. Descarga la última release desde <https://github.com/Kanin-020/decky-twinpad/releases/latest> y sigue las instrucciones de instalación de ese repositorio.
2. Recarga Decky (`Ajustes → Decky → Recargar`). El plugin aparece como **Twinpad** en el menú de acceso rápido.
3. El servidor debe correr **en el mismo dispositivo** que Decky: la página de gestión es solo local (loopback) y no se publica a la red. Si el binario no está en el `PATH`, indica su ruta con `TWINPAD_SERVER_BIN`; el puerto local se cambia con `TWINPAD_LOCAL_PORT`.
4. El autoarranque del servidor funciona en modo escritorio; en modo juego SteamOS no ejecuta el autoarranque XDG, así que ahí hay que arrancarlo desde el propio panel.

---

## Requisitos comunes

- PC con Linux x86_64 para El server y para las integraciones de Cemu y Dusklight.
- Teléfono Android y PC en la misma red local.
- Puertos de la red local accesibles desde el teléfono.

El servidor y el APK se compilan desde el código de la etiqueta publicada. Las integraciones se publican desde sus propios repositorios. Para reportar fallos o revisar el código, usa el repositorio correspondiente.
