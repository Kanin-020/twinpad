# ADR-008 — IPC del SDK: TCP localhost con NDJSON

| | |
|---|---|
| ID | ADR-008 |
| Title | Canal IPC SDK ↔ Servidor: TCP localhost NDJSON |
| Version | 1.0.0 |
| Status | `Accepted` |
| Date | 2026-10-08 |
| Deciders | Owner del proyecto (usuario) |
| Affects | SPEC-006 §4/§17, DES-006, ARCH §4/§6 |

## 1. Context

SPEC-006 deja como decisión de DES-006 el mecanismo IPC por el que un juego con
SDK Twinpad anuncia su presencia al Servidor PC (opciones: named pipe de Windows,
socket localhost, pipe anónimo al proceso hijo). También quedó abierta la forma
del SDK v1 (lenguaje/runtimes). El server ya tiene un framing NDJSON probado
(SPEC-001 §4.2) y el workspace ya contiene el crate `twinpad-sdk`.

## 2. Problem

¿Cómo anuncia un juego con SDK su presencia al Servidor PC, y con qué forma de
SDK, de modo que la detección sea automática (R1), el liveness sea verificable
(R2) y el crash del juego no rompa la sesión (R3)?

## 3. Options considered

### Option A — TCP localhost (127.0.0.1:8421) con NDJSON — elegida

- **Pros:** multiplataforma (Linux hoy, Windows mañana); reutiliza el estilo de
  framing y el codec estricto ya probados; la conexión viva **es** el anuncio
  vivo: el crash/cierre del juego cae el socket y el server lo detecta al acto
  (R2/§9 sin mecanismos extra); testeable en loopback en CI.
- **Cons:** un puerto local más que gestionar (colisión → configurable);
  cualquier proceso local podría anunciar (mitigable en iteraciones futuras con
  handshake local del SDK, fuera de alcance v1).

### Option B — Named pipe de Windows

- **Pros:** estándar en PC gaming; sin gestión de puertos.
- **Cons:** no testeable en el banco actual (Linux); requiere implementación
  específica por SO; duplicaría el framing.

### Option C — Pipe anónimo / proceso hijo

- **Pros:** sin red local; acoplado al ciclo de vida del hijo.
- **Cons:** obliga a que el server lance el juego (cambia el modelo de uso);
  framing binario nuevo sin reutilizar nada.

## 4. Decision

El canal IPC SDK ↔ Servidor es un **socket TCP en 127.0.0.1:8421** (configurable)
con mensajes **NDJSON** de un campo `type` (`game_announce` v1), mismo estilo de
framing que SPEC-001 §4.2. La **conexión viva constituye el anuncio vivo**: al
caer, el juego deja de estar disponible (SPEC-006 §6/§9). El **SDK v1 es el
crate Rust `twinpad-sdk`** (cliente de anuncio + handle de ciclo de vida); el
wrapper C/C++ entra después como capa FFI.

## 5. Justification

- R1/R2/§9 se satisfacen con primitivas ya probadas (TCP + EOF), sin código
  nuevo de liveness ni por SO.
- El codec estricto de `core` (E4) se reutiliza para validar los anuncios.
- Decisión tomada por el owner (aprobación de SPEC-006, 2026-10-08).

## 6. Consequences

**Positive:**
- Detección automática verificable en CI por loopback (TEST-021).
- `game_id` duplicado mientras el primero vive → rechazo limpio (§9).

**Negative / trade-offs accepted:**
- Cualquier proceso local puede anunciar en v1 (autenticación local del SDK
  diferida; el canal no expone nada hacia la app sin sesión autenticada).
- Puerto local adicional (8421) con posible colisión → parametrizable.

**Neutral:**
- AC-2/AC-3 de REQ-006 (publicar frames / consumir inputs vía SDK) quedan en el
  SDK y entran en iteraciones posteriores sobre este mismo canal.

## 7. Compliance and validation

- Enforcement: TEST-021 (detección, liveness, duplicado, crash).
- Revisit trigger: si el modelo de amenaza local exige autenticar el anuncio, o
  si Windows requiere named pipes (nueva ADR).

## 8. Related

| Relation | ID |
|---|---|
| Supersedes | - |
| Superseded by | - |
| Relates to | SPEC-006, REQ-006, ADR-002 |
| Contextualized in | ARCH §4, §6 |
| Changed by CR | - |

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 1.0.0 | 2026-10-08 | Owner (usuario) | Accepted: TCP localhost NDJSON + SDK Rust |
