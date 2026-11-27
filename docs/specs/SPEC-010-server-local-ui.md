# SPEC-010 — Interfaz local de gestión del Servidor PC (tray + página loopback)

> ⚠️ TEMPLATE — replaced; this SPEC was written new under CR-061.

> **What this is:** the precise, testable behavior of the Servidor PC's local control surface: StatusNotifier tray, loopback-only HTTP page, autostart toggle and graceful stop.
> **What it is NOT:** how it is built (see DES-005) or why (see REQ-001).

| | |
|---|---|
| ID | SPEC-010 |
| Title | Interfaz local de gestión del Servidor PC |
| Version | 0.6.0 |
| Status | `Approved` |
| Owner | Eng lead |
| Implements | REQ-001 (AC-1, AC-2) y operación/detención de CR-061 |
| Realized by | DES-005 |
| Verified by | TEST-0135, TEST-0136, TEST-0137, TEST-0146, TEST-0147, TEST-0148, TEST-0204, TEST-0205 |
| Last updated | 2026-11-26 |

## 1. Purpose

Define el comportamiento de la superficie de control local del Servidor
PC: bandeja del sistema persistente, página HTTP restringida a loopback
con el QR copiable y las configs vigentes, autoarranque conmutado por el
usuario y parada con gracia (CR-061).

## 2. Definitions

- **Tray:** icono en la bandeja del sistema (StatusNotifierItem) que
  persiste mientras el Servidor PC corre. See CTX glossary for Servidor PC.
- **Página de gestión:** página HTTP servida exclusivamente en
  `127.0.0.1:<puerto_local>` que muestra el estado del servidor.
- **Puerto_local:** puerto TCP en 127.0.0.1 distinto de los puertos de
  sesión (8420) y de los IPC del SDK (8421–8423, 8426); valor por defecto
  8430, configurable por argumento.
- **URI de vinculación:** ver SPEC-001 §4.1 (`twinpad://v1#…`).
- **Ruta /qr:** subruta HTTP del puerto_local (loopback only, W6) que
  devuelve el vencimiento del QR vigente en JSON (`{uri, ttl_ms, svg}`);
  el `svg` es el QR del MISMO token que el `uri` (W8, CR-073).
- **Ruta /qr/rotate:** subruta del mismo puerto (CR-073 0.2.0) que ROTA
  el token: invalida el vigente NO consumido y emite uno nuevo de
  inmediato (reutiliza §8.3 de SPEC-001); respuesta idéntica en formato.

## 3. Preconditions

- El Servidor PC está en ejecución (checked where: proceso principal).
- Existe un QR vigente en el `TokenStore` (SPEC-001 §7; el store lo
  regenera solo al vencer los 300 s de R7).

## 4. Inputs

| Input | Type | Domain | Semantics |
|---|---|---|---|
| Puerto local de la página | CLI | 1024–65535 | fallback 8430 si se omite o es inválido (E8) |
| `GET /` | HTTP request | desde 127.0.0.1 | render de la página (§5) |
| `GET /stop` (botón) acción stop | HTTP request | desde 127.0.0.1 | activa la parada con gracia (§5 W6) |
| `GET /qr` (contador/regeneración) | HTTP request | desde 127.0.0.1 | vencimiento JSON del QR vigente (§5 W9) |
| `GET /qr/rotate` (botón «Regenerar QR») | HTTP request | desde 127.0.0.1 | rotación del QR vigente (§5 W9 manual, CR-073 0.2.0) |
| Menú del tray | usuario local | {Abrir interfaz de gestión (sin icono), Iniciar al arrancar el equipo, Mando virtual (DualSense), Detener servidor} | §5 W1–W4, W10 |
| Toggle de autoarranque | usuario local | off/on | instala/elimina entrada XDG autostart (§5 W5) |
| Toggle del mando virtual | usuario local | off/on | crea/destruye el HID DualSense del servidor y persiste la elección (§5 W10) |

## 5. Behavior (rules)

- **W1 — Tray persistente:** mientras el Servidor PC corre, existe un
  icono de bandeja. Su tooltip indica el estado «Twinpad — activo» y el
  URI vigente queda accesible en la página (W2). El icono
  puede aparecer DESPUÉS que el proceso (el host de bandeja aún no existe) y
  debe reaparecer si el host se reinicia: el servicio de bandeja no se rinde
  ante un `StatusNotifierWatcher` ausente (CR-114; ver W7).
- **W2 — Página muestra estado:** `GET /` desde loopback responde 200 con
  contenido HTML que incluye: (a) el QR de vinculación vigente renderizado
  como imagen; (b) el URI en una casilla de texto copiable; (c) el estado
  del servidor: «activo», número de sesiones activas y lista de juegos
  compatibles; (d) la resolución y el bitrate de video configurados
  actualmente para el encoder del Plugin.
- **W3 — Abrir interfaz:** la acción «Abrir interfaz de gestión» del menú
  del tray, sin icono, abre la página de gestión (solicitud HTTP GET al
  puerto local, desde el propio PC) en una
  ventana del sistema: por defecto el WEBVIEW DEL SISTEMA del escritorio
  del host (WebKitGTK vía `flatpak-spawn --host` + PyGObject en el host —
  CR-063 0.2.0; ventana propia sin barra de direcciones ni pestañas); el
  navegador del PC queda SOLO como fallback (modo app Blink →
  predeterminado normal). Al arrancar, el Servidor PC abre la ventana una
  vez por sí mismo (fail-soft sin entorno de escritorio); la URL queda
  impresa en stdout como vía alternativa (CR-063 §2 0.1.1).
- **W4 — Detener servidor:** la acción de menú del tray y el botón de la
  página detienen el servidor con gracia: no se aceptan nuevas sesiones
  y el  proceso termina. Los clientes vivos de la app observan el cierre por su
  canal de control, exactamente como un EOF de línea (SPEC-001 §4.3 HB6:
  mismo fin observable).
- **W5 — Iniciar al arrancar el equipo (off/on):** al activar el toggle,
  el servidor instala una entrada de autoarranque de escritorio (desktop
  entry) que lanza el Servidor PC al inicio de sesión del usuario; al
  desactivarla, la elimina. Operación idempotente: activar dos veces no
  duplica la entrada; desactivar sin entrada previa es un no-op.
- **W6 — Solo loopback:** la página de gestión SOLO se sirve en el
  interfaz loopback (`127.0.0.1`). Cualquier consulta desde otro interfaz
  es rechazada (conexión no establecida) — la página no expone tokens ni
  configs a la LAN (NFR-004).
- **W7 — Fail-soft del tray:** si el entorno no ofrece (todavía) un host de
  StatusNotifier/bandeja, el servidor arranca igual, imprime el URI por
  stdout (comportamiento previo intacto) y la página de gestión sigue
  operativa. La ausencia del host NO es un fallo definitivo: el servicio de
  bandeja espera y registra el icono en cuanto el host aparece, y vuelve a
  registrarlo si el host se reinicia (CR-114). Los fallos duros (sin bus de
  sesión, p. ej. dentro de un sandbox) se reintentan unas pocas veces y, si
  persisten, el log lo dice junto con la URL local de la página.
- **W8 — QR siempre fresco:** la página (y el URI del tooltip) muestran
  el URI vigente del momento de la consulta; si el token de 300 s (R7)
  venció, la consulta fuerza la emisión de uno nuevo. El QR en pantalla y
  el URI copiable pertenecen AL MISMO token de la respuesta.
- **W9 — Continuidad del QR sin recargar (CR-073):** la página muestra
  un contador (mm:ss) con los ms restantes de validez del QR vigente; al
  vencer, la página consulta `GET /qr` (UNA consulta por ciclo del
  token) y sustituye EL QR Y LA CASILLA DEL URI JUNTOS con el MISMO
  token nuevo (W8), sin recargar la página. El vencimiento automático
  NO rota: únicamente reconsulta `GET /qr` (R7 intacto). El botón
  «Regenerar QR» ROTA el token (CR-073 0.2.0): consulta `GET /qr/rotate`,
  que invalida el token vigente NO consumido y emite uno nuevo de
  inmediato — los reintentos con el URI anterior quedan fuera (E2/E3).
  Fail-soft (W7): sin respuesta
  válida (incluido 503 cuando el SVG no es renderizable), la página
  conserva el QR y el URI vigentes y reintenta con cadencia fija (15 s;
  el reintento SOLO consulta, no rota).
- **W10 — Mando virtual on/off desde la página (CR-114):** la página muestra
  «Mando virtual (DualSense): Activado|Desactivado» y un botón que conmuta el
  HID del servidor por `POST /virtual-pad` con cuerpo
  `enabled=1|0` y cabecera `X-Twinpad-Action` (misma regla S1 que W5).
  Encender crea el DualSense de SPEC-004 §8.4; apagar lo destruye de inmediato.
  El menú del tray ofrece el mismo interruptor con marca de verificación,
  leyendo siempre el estado vigente del servidor.
  La elección se persiste en la carpeta de datos (fichero `virtual-pad` con
  `on`/`off`) y manda sobre la variable de entorno en el siguiente arranque;
  sin fichero, `TWINPAD_VIRTUAL_PAD` decide. Si el HID no se puede crear
  (`/dev/uhid` ausente o sin permiso), la respuesta es 500 con el motivo, el
  estado no cambia y la nota del panel muestra el error (E9). El mismo
  interruptor, con su marca de verificación, está en el menú del tray
  («Mando virtual (DualSense)») y comparte estado y preferencia con la
  página.

## 6. Outputs

| Output | When | Observable |
|---|---|---|
| Página HTML | `GET /` (200) | W2 |
| Vencimiento JSON | `GET /qr` (200) | W9 |
| QR rotado JSON | `GET /qr/rotate` (200) | W9 manual |
| Respuesta de parada | `GET /stop` | 200 al cliente y proceso en fin de gracia |
| Respuesta del mando virtual | `POST /virtual-pad` | 204 al conmutar; 500 con motivo si el HID no está disponible (W10) |
| Entrada de autoarranque | W5 on | archivo de desktop entry presente bajo la configuración de usuario |
| Ausencia de entrada | W5 off | archivo ausente |
| URI por stdout | W7 (sin bandeja) | línea con `twinpad://v1#…` en el terminal |

## 7. Edge cases and limits

- **E1 — Puerto local ocupado → (E8):** no se puede abrir el puerto local;
  el servidor continúa (tray + stdout intactos) y registra el fallo.
- **E2 — QR vencido en consulta:** la página emite un QR nuevo antes de
  responder (W8); nunca sirve un URI consumido.
- **E3 — Consulta no-local (W6):** rechazo; sin contenido.
- **E4 — Sin entorno de bandeja (W7):** tray ausente, servidor vivo.
- **E5 — Autostart sin directorio de config de usuario:** se crea el
  directorio necesario; si el SO lo impide, el toggle responde con el
  error y el estado del servidor no cambia.
- **E6 — Varías sesiones activas en el stop:** el proceso termina tras
  activar la bandera de parada; los loops de sesión salen por su propio
  fin de stream (W4).
- **E7 — SVG no renderizable en `GET /qr`:** 503; la página conserva el
  QR y el URI vigentes en pantalla (W7) hasta la próxima consulta.
- **E9 — Mando virtual no disponible (W10):** `/dev/uhid` ausente, sin
  permiso o fuera de Linux; el toggle (página o menú del tray) informa del
  motivo, el estado del mando NO cambia y el servidor sigue vivo.

## 8. Errors

*(No se introducen códigos E nuevos de SPEC-001: la pagina es una
superficie de presentación. El fallo del puerto local queda registrado
en el log del server (E1/W7) y no afecta al pairing.)*

| Condition | Mechanism |
|---|---|
| Puerto local ocupado | log explicativo, servidor continúa (E1) |
| Sin host de bandeja | servidor continúa con stdout (E4) |
| Directorio autostart no escribible | error propagado al usuario en la acción (E5) |
| `/dev/uhid` no disponible | 500 con motivo en la página y aviso en el log desde el tray; mando apagado (E9) |

## 9. Acceptance criteria (testable)

- AC-1: dado el server activo, `GET /` en el puerto local responde 200 con el URI vigente presente en la respuesta, y el mismo URI sirve al QR renderizado (W2, W8).
- AC-2: dado el server activo, la página muestra el número de sesiones activas y los juegos compatibles (W2).
- AC-3: dada la acción de parada (tray o botón), el proceso del server termina y no se aceptan nuevas sesiones (W4).
- AC-7: dado el servidor activo con `/dev/uhid` disponible, el toggle del mando virtual responde 204 y el dispositivo HID existe o deja de existir según el estado; apagado y con el fichero de preferencia `off`, un arranque posterior no lo crea (W10).
- AC-4: dado el toggle de autoarranque activado, la entrada de autoarranque existe; desactivado, ausente; repetir no duplica (W5).
- AC-5: dado el server activo, `GET /qr` desde loopback responde 200 con JSON que contiene el uri vigente, ttl_ms ∈ (0; 300 000] y el svg del MISMO token (W8); desincronizar QR y casilla es imposible por diseño (misma respuesta, única consulta). TEST-0146. La cadencia del contador y el swap visual sin recarga son verificación manual (owner).
- AC-6: dado el server activo, `GET /qr/rotate` desde loopback invalida el token vigente no consumido, emite uno nuevo (distinto por llamada) y es idempotente en la condescendencia (sin vigente: emite); el URI anterior queda fuera (E2/E3 via `lookup`). TEST-0147/0148.

## 10. Non-functional concerns

- NFR-003 (bajo consumo): la página es estática en su carga; el sondeo
  queda limitado al ciclo de vida del token: UNA consulta `GET /qr` por
  vencimiento (o por regeneración manual — W9). Sin polling de estado de
  sesiones ni del server; el tray no sondea.
- NFR-004: loopback-only (W6).

## 11. Related docs

- DES-005 (realización), ADR-010 (decisión), CR-061 (origen).
- REQ-001 (requisito implementado).

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-01 | Buffy (agente) | Initial (CR-061) |
| 0.2.0 | 2026-11-02 | Buffy (agente) | `Approved` — W3 abre en ventana web del sistema (modo `--app` de navegador Blink, fallback navegador normal); CR-063; TEST-0141 |
| 0.3.0 | 2026-11-02 | Buffy (agente) | `Approved` — W3 abre en el WEBVIEW del sistema (WebKitGTK del host vía flatpak-spawn+PyGObject); navegadores bajan a fallback (0.2.0 CR-063); TEST-0141/0142 |
| 0.4.0 | 2026-11-04 | Buffy (agente) | W9: continuidad del QR sin recargar (contador + regeneración automática/manual, `GET /qr`, E7/AC-5; NFR-003 reescrito); CR-073; TEST-0146 |
| 0.4.1 | 2026-11-04 | Buffy (agente) | W9 manual corregido por decisión del owner: «Regenerar QR» ROTA el token (`GET /qr/rotate`; §8.3 reutilizada; el vencimiento automático no rota); AC-6; CR-073 0.2.0; TEST-0147/0148 |
| 0.5.0 | 2026-11-25 | Owner + Buffy (agente) (CR-114) | W10: mando virtual DualSense on/off desde la página y el menú del tray (`POST /virtual-pad`, preferencia persistida, E9/AC-7); W1/W7: la bandeja espera al host SNI y se re-registra si reaparece |
| 0.6.0 | 2026-11-26 | Owner + Buffy (agente) (CR-129) | Menú del tray: «Abrir interfaz de gestión» sin icono y eliminación de «¿Dónde está el URI?»; la página local y el URI de arranque no cambian |
