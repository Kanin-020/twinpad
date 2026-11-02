# SPEC-010 — Interfaz local de gestión del Servidor PC (tray + página loopback)

> ⚠️ TEMPLATE — replaced; this SPEC was written new under CR-061.

> **What this is:** the precise, testable behavior of the Servidor PC's local control surface: StatusNotifier tray, loopback-only HTTP page, autostart toggle and graceful stop.
> **What it is NOT:** how it is built (see DES-005) or why (see REQ-001).

| | |
|---|---|
| ID | SPEC-010 |
| Title | Interfaz local de gestión del Servidor PC |
| Version | 0.1.0 |
| Status | `Approved` |
| Owner | Eng lead |
| Implements | REQ-001 (AC-1, AC-2) y operación/detención de CR-061 |
| Realized by | DES-005 |
| Verified by | TEST-0135, TEST-0136, TEST-0137 |
| Last updated | 2026-11-01 |

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
| Menú del tray | usuario local | {Abrir interfaz, Iniciar al arrancar el equipo, Detener servidor} | §5 W1–W4 |
| Toggle de autoarranque | usuario local | off/on | instala/elimina entrada XDG autostart (§5 W5) |

## 5. Behavior (rules)

- **W1 — Tray persistente:** mientras el Servidor PC corre, existe un
  icono de bandeja. Su tooltip indica el estado «Twinpad — activo» y el
  URI vigente queda accesible vía el menú (W3) y la página (W2).
- **W2 — Página muestra estado:** `GET /` desde loopback responde 200 con
  contenido HTML que incluye: (a) el QR de vinculación vigente renderizado
  como imagen; (b) el URI en una casilla de texto copiable; (c) el estado
  del servidor: «activo», número de sesiones activas y lista de juegos
  compatibles; (d) la resolución y el bitrate de video configurados
  actualmente para el encoder del Plugin.
- **W3 — Abrir interfaz:** la acción de menú del tray abre la página de
  gestión (solicitud HTTP GET al puerto local, desde el propio PC).
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
- **W7 — Fail-soft del tray:** si el entorno no ofrece un host de
  StatusNotifier/bandeja, el servidor arranca igual, imprime el URI por
  stdout (comportamiento previo intacto) y la página de gestión sigue
  operativa.
- **W8 — QR siempre fresco:** la página (y el URI del tooltip) muestran
  el URI vigente del momento de la consulta; si el token de 300 s (R7)
  venció, la consulta fuerza la emisión de uno nuevo. El QR en pantalla y
  el URI copiable pertenecen AL MISMO token de la respuesta.

## 6. Outputs

| Output | When | Observable |
|---|---|---|
| Página HTML | `GET /` (200) | W2 |
| Respuesta de parada | `GET /stop` | 200 al cliente y proceso en fin de gracia |
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

## 8. Errors

*(No se introducen códigos E nuevos de SPEC-001: la pagina es una
superficie de presentación. El fallo del puerto local queda registrado
en el log del server (E1/W7) y no afecta al pairing.)*

| Condition | Mechanism |
|---|---|
| Puerto local ocupado | log explicativo, servidor continúa (E1) |
| Sin host de bandeja | servidor continúa con stdout (E4) |
| Directorio autostart no escribible | error propagado al usuario en la acción (E5) |

## 9. Acceptance criteria (testable)

- AC-1: dado el server activo, `GET /` en el puerto local responde 200 con el URI vigente presente en la respuesta, y el mismo URI sirve al QR renderizado (W2, W8).
- AC-2: dado el server activo, la página muestra el número de sesiones activas y los juegos compatibles (W2).
- AC-3: dada la acción de parada (tray o botón), el proceso del server termina y no se aceptan nuevas sesiones (W4).
- AC-4: dado el toggle de autoarranque activado, la entrada de autoarranque existe; desactivado, ausente; repetir no duplica (W5).

## 10. Non-functional concerns

- NFR-003 (bajo consumo): la página es estática; sin polling del server
  por la página; el tray no sondea.
- NFR-004: loopback-only (W6).

## 11. Related docs

- DES-005 (realización), ADR-010 (decisión), CR-061 (origen).
- REQ-001 (requisito implementado).

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-01 | Buffy (agente) | Initial (CR-061) |
