# SPEC-003 — Stream de video de la Segunda pantalla (framebuffers)

| | |
|---|---|
| ID | SPEC-003 |
| Title | Stream de video de la Segunda pantalla (framebuffers) |
| Version | 1.3.0 |
| Status | `Approved` |
| Owner | Eng lead |
| Implements | REQ-004 (primario), REQ-005 (parcial: solo video) |
| Realized by | DES-003 (CR-002; Draft), DES-004 (CR-010; Draft) |
| Verified by | TEST-015, TEST-016, TEST-017, TEST-027, TEST-028, TEST-051, TEST-054 |
| Last updated | 2026-10-18 |

## 1. Purpose

Define el transporte y presentación del Stream de video de la Segunda pantalla: cómo los Framebuffers que publica el Plugin seleccionado llegan al Android device por la sesión autenticada, cómo se adaptan y se ordenan, y su comportamiento ante cortes/degradación. El Servidor no captura ni genera la fuente; implementa REQ-004 y la parte de video de REQ-005.

## 2. Definitions

- **Framebuffer**: imagen completa de un frame de la Segunda pantalla. En el wire puede llegar como keyframe completo o reconstruirse aplicando `video_patch` sobre el último Framebuffer válido (CR-002).
- **Stream de video**: secuencia ordenada de Framebuffers transmitida PC → Android durante una sesión.
- **Frame number (`fn`)**: número secuencial monótono del Framebuffer dentro del stream.
- **Frame activo**: el último Framebuffer recibido y descodificable en el Android.
- **Degradación**: reducción acordada de fps o resolución ante pérdida de ancho de banda (REQ-004 §8).
- Los términos **Sesión autenticada**, **Pairing**, **Stream**, **Segunda pantalla** y **Servidor PC**: ver CTX §8 y SPEC-001 §2.

## 3. Preconditions

- Sesión autenticada establecida según SPEC-001 (estado U4/`Established`); el transporte de SPEC-003 no acepta conexiones fuera de sesión (NFR-004).
- La sesión ha confirmado un Plugin vivo con `game_selected` (SPEC-006); solo los Framebuffers publicados por esa fuente pueden alimentar el stream.

## 4. Inputs

| Field | Type | Constraints | Required |
|---|---|---|---|
| `type` | string | `"video_frame"` para keyframes completos | yes |
| `fn` | integer (uint64) | monotónico creciente, empieza en 0 en cada stream; nunca se repite (en JSON se llama `fn`; en Rust es `fn_`) | yes |
| `ts` | integer (uint64) | milisegundos del reloj del server (monótono respecto de sí mismo) en el envío | yes |
| `w` | integer (uint16) | ancho del Framebuffer en píxeles, 16–3840 | yes |
| `h` | integer (uint16) | alto del Framebuffer en píxeles, 16–2160 | yes |
| `fmt` | string | `gray8` \| `rgb8` \| `jpeg` (CR-001) \| `jpeg_tiles` (solo `stream_start`, CR-002) | yes |
| `data` | string | `rgb8`/`gray8`: base64url del framebuffer crudo, `w×h×bytes(fmt)` bytes; `jpeg`: base64url del archivo JPEG completo (con cabecera), tamaño libre | yes para `video_frame` |
| Control `stream_start` | mensaje | `type:"stream_start"`, `w`,`h`,`fmt`,`fps` (uint8, 1–60) — anuncia el stream y sus parámetros iniciales; en el modo tiles `fmt:"jpeg_tiles"` | yes antes del primer frame/keyframe |
| `video_patch` | mensaje | `type:"video_patch"`, `fn`, `base_fn`, `ts`, `w`, `h`, `tile_size:64`, `patches` no vacío; cada patch tiene `x`,`y`,`w`,`h`,`fmt:"jpeg"`,`data` base64url de un JPEG del tile | solo en `jpeg_tiles`, tras keyframe |
| Control `stream_stop` | mensaje | `type:"stream_stop"`, `reason`: `plugin_stopped` \| `server_shutdown` | yes al terminar |
| Control `stream_gap` | mensaje | `type:"stream_gap"`, `from_fn`,`to_fn` — notifica frames perdidos por transporte | opcional |

En `video_patch`, `fn` es creciente y mayor que `base_fn`; `base_fn` identifica el último keyframe o patch emitido y aplicado antes del mensaje. `x` e `y` deben ser múltiplos de 64; `w`/`h` son 64, salvo tiles de borde, y el rectángulo debe quedar dentro de `w×h`. Cada JPEG debe decodificar a las dimensiones declaradas. El servidor compara tiles RGB de 64×64 contra el frame fuente anterior; no emite mensaje cuando no hay tiles modificados. Android aplica el mensaje completo de forma atómica solo si `base_fn` coincide. Un mismatch o tile inválido descarta el patch y espera un keyframe. `stream_start fmt:"jpeg_tiles"` exige actualización conjunta de servidor y Android; las versiones antiguas no son compatibles. Los keyframes son `video_frame fmt:"jpeg"` y se envían al iniciar/cambiar resolución y al menos cada 1 s. Si el tamaño serializado total de patches no es menor que el keyframe completo JPEG, se envía el keyframe completo en su lugar.

Canal: la conexión TCP de la sesión (mismo socket que el Handshake de SPEC-001, persistente desde esta iteración). Cada mensaje es una línea NDJSON terminada en `\n`.

## 5. Outputs

| Result | Value / shape | Condition |
|---|---|---|
| Pantalla de Segunda pantalla | vista dedicada que presenta el Frame activo completo a pantalla completa del área asignada, escalado a la orientación vigente | stream activo con keyframe o patches aplicados sobre una base válida |
| Estado "sin segunda pantalla activa" | mensaje en la vista, no pantalla congelada | sesión activa sin stream (REQ-004 AC-3) |
| Frame/patch descartado | un Framebuffer o patch corrupto/incompleto no se muestra; se mantiene el Frame activo previo; tras patch inválido se espera el siguiente keyframe | `data` inconsistente con `w`,`h`,`fmt` o `base_fn` distinto de la base aplicada |
| Reanudación | el stream se recibe de nuevo en la misma conexión tras un corte transitorio de red del lado Android < 10 s | reconexión TCP (REQ-005 AC-2) |

## 6. States

Estados del stream (independientes del estado de sesión de SPEC-002):

- `V0 Inactive`: sin stream; la vista muestra "sin segunda pantalla activa".
- `V1 Streaming`: llegan frames con `fn` consecutivos.
- `V2 Interrupted`: la conexión/session cayó o no llegan frames en ≥ 2 s.

| From | Event | To | Side effects |
|---|---|---|---|
| `V0` | `stream_start` | `V1` | vista pasa a mostrar frames |
| `V1` | `video_frame` válido | `V1` | Keyframe completo aplicado; reemplaza la base y limpia espera de keyframe |
| `V1` | `video_patch` con `base_fn` igual al último frame aplicado y `fn` mayor | `V1` | Todos los tiles se componen atómicamente; actualiza `fn` aplicado |
| `V1` | `video_patch` con `base_fn` distinto o tile inválido | `V1` | Se descarta todo el patch y se espera keyframe; no se altera el Frame activo |
| `V1` | `video_frame` con `fn` ≤ último | `V1` | frame tardío descartado (no se muestra) |
| `V1` | `stream_gap` | `V1` | los `fn` del hueco se marcan saltados |
| `V1` | `stream_stop` | `V0` | vista vuelve a "sin segunda pantalla activa" |
| `V1` | sin frames en ≥ 2 s o corte TCP | `V2` | la app intenta recuperación (§8.2) |
| `V2` | `stream_start` (o siguiente `video_frame`) tras reconectar | `V1` | reanudación sin re-pairing |
| `V2` | recuperación imposible en 10 s | `V0` | vista "sin segunda pantalla activa" + estado de sesión según SPEC-002 |

Invariant: en `V1` y `V2` la sesión sigue autenticada; salir de la sesión (SPEC-001/002) fuerza `V0` y libera recursos.

## 7. Main flow

1. Android selecciona un Plugin conforme a SPEC-006. Sin selección confirmada no existe Stream de video y la vista indica “sin segunda pantalla activa”.
2. El Servidor consume solo los Framebuffers publicados por el Plugin confirmado, los adapta al formato/tasa configurados y envía `stream_start` antes de la primera imagen. Nunca captura escritorio ni genera frames de relleno.
3. Antes de cualquier patch, el Servidor envía un keyframe JPEG completo con `fn=0`. Para cada framebuffer nuevo compara tiles RGB de 64×64 con la fuente anterior y envía los tiles modificados o keyframe si el patch no reduce tamaño; keyframes completos llegan al inicio/cambio de resolución y cada ≤1 s.
4. Android aplica keyframes y patches solo si sus bases y geometrías son válidas; compone los patches atómicamente y descarta los inválidos sin cambiar el Frame activo.
5. Al cambiar de Plugin, el Servidor envía `stream_stop` de la fuente anterior antes de iniciar la nueva, y el stream nuevo comienza con `fn=0`. Si la fuente desaparece, termina el stream y la selección/UI se actualiza según SPEC-006.
6. La vista presenta el Frame activo escalado a la orientación del teléfono (REQ-004 AC-1).

## 8. Alternative flows

### 8.1 Cambio de resolución/formato en mitad de sesión

- **Trigger:** el Plugin cambia la resolución o el Server degrada (§8.3).
- **Flow:** el Server envía `stream_start` de nuevo con los parámetros nuevos y `fn` continúa donde estaba (no se reinicia).
- **Result:** el Android re-encuadra la vista sin reiniciar el stream ni la sesión.

### 8.2 Corte transitorio de red (REQ-005 AC-2)

- **Trigger:** caída de WiFi/roaming breve < 10 s.
- **Flow:** el Android entra en `V2`; al restablecerse la red reabre la conexión TCP a `server_addr:server_port` y envía `pair_request` con el **token de sesión retenido** (mismo mecanismo de autenticación de SPEC-001; sin QR). El server valida la sesión registrada y reanuda.
- **Result:** `V1` de nuevo; frames con `fn` mayor al último recibido; nunca se re-escanea el QR.

### 8.3 Cambio de Plugin seleccionado

- **Trigger:** Android selecciona otro Plugin y recibe `game_selected`.
- **Flow:** el Servidor termina el stream anterior con `stream_stop`, descarta su base de tiles y comienza el stream de la nueva fuente desde `fn=0`.
- **Result:** Android no mezcla ni muestra frames de la fuente anterior con la nueva.

### 8.4 Degradación por red saturada

- **Trigger:** el Servidor observa pérdida/acumulación que excede el ancho de banda negociado (señal: frames no confirmados o cola de envío creciente).
- **Flow:** el Servidor adapta `fps`/resolución dentro de los límites vigentes y anuncia los parámetros con `stream_start` nuevo (§8.1).
- **Result:** el stream continúa con calidad adaptada; el Plugin sigue siendo el origen de los medios.

## 9. Errors

| Condition | Observable behavior | Error code / message |
|---|---|---|
| `video_frame` inválido o `video_patch` con base/tiles inválidos | se descarta el mensaje completo y se mantiene el Frame activo; tras patch inválido el Android ignora patches hasta el próximo keyframe | sin mensaje al usuario |
| `video_frame` con `fn` ≤ último visto | frame tardío descartado silenciosamente | — |
| Mensaje de stream con `type` desconocido o campos faltantes | se trata como violación de protocolo: la sesión termina con el mecanismo de SPEC-002 | `E4` (SPEC-001 §9) |
| `stream_start`/frame recibidos **sin sesión autenticada** | conexión rechazada en el server | `E3` |
| Corte de red ≥ 10 s | la app vuelve a U1 de SPEC-002; requiere nuevo Pairing | `E1` (SPEC-002 §9) |
| Sin frames en `V1` durante ≥ 2 s (server lento pero vivo) | estado `V2` con indicador de reconexión en la vista; no mensaje de error final hasta §8.2 | indicador, no error |

## 10. Edge cases

| Case | Expected behavior |
|---|---|
| Primer keyframe con `fn` ≠ 0 o sin `stream_start` previo | el keyframe se descarta; el stream no arranca hasta recibir `stream_start` |
| `video_frame`/`video_patch` con `fn` ≤ último aplicado | el mensaje se descarta silenciosamente; la composición no retrocede |
| Keyframe/patch con resolución distinta a `stream_start` o patches superpuestos/inválidos | se descarta mensaje; un patch inválido obliga a esperar keyframe |
| Orientación del teléfono cambia en `V1` | la vista re-escala el Frame activo; sin cortes ni re-negociación |
| `stream_stop` durante `V2` | al reconectar se entrega `V0` ("sin segunda pantalla activa") |
| Dos Android en sesión (NFR-006) | cada uno recibe su propio stream por su conexión; el server no intercala frames de uno en otro |
| `stream_gap` o patch perdido/malformado | se mantiene el último Frame válido; cualquier patch con `base_fn` incorrecta se descarta y el siguiente keyframe recupera sincronía en ≤1 s |
| Reloj del server retrocede (`ts` no monótono) | `ts` se usa solo para diagnóstico; la ordenación es por `fn` y jamás por `ts` |

## 11. Business rules

- **R1:** el stream solo existe sobre una sesión autenticada de SPEC-001; sin sesión no hay transmisión (NFR-004).
- **R2:** el orden de presentación lo determina exclusivamente `fn`; ningún otro campo altera el orden.
- **R3:** la latencia objetivo es la de NFR-001 (p95 ≤ 60 ms captura→visible); el cliente aplica patches en orden, descarta los que no coinciden con `base_fn` y recupera la base con un keyframe en ≤1 s. El emisor no acumula ni retransmite imágenes antiguas.
- **R4:** ante corte < 10 s, la reanudación reutiliza la sesión autenticada y jamás exige re-pairing (REQ-005 AC-2).
- **R5:** la degradación la decide siempre el Server (fps primero, resolución después); el Android nunca la solicita en v1.
- **R6:** si no hay stream, la vista lo indica explícitamente; jamás muestra un Frame congelado como si estuviera vivo (REQ-004 AC-3).

## 12. Contracts

| Contract | Canonical source | Notes |
|---|---|---|
| Mensajes de stream (`stream_start`/`video_frame`/`video_patch`/`stream_stop`/`stream_gap`) | este documento §4; NDJSON según SPEC-001 §4.2 | v1.3.0; solo medios del Plugin seleccionado (SPEC-006); cambio de fuente reinicia el stream |
| Autenticación de la sesión | SPEC-001 §7/§8 | sin duplicación: se referencia |
| Formato de Framebuffer | ADR-005 + §4 `fmt` de este SPEC | `gray8` (1 B/px), `rgb8` (3 B/px), fila mayor |

## 13. Examples (Given / When / Then)

```gherkin
Scenario: Flujo principal (REQ-004 AC-1)
  Given sesión autenticada y plugin transmitiendo
  When el server envía stream_start(w=1280,h=720,fmt=rgb8,fps=30)
  And luego video_frame(fn=0..n) consecutivos
  Then la vista del Android muestra cada frame sin intervención del usuario

Scenario: Patch atómico y recuperación por keyframe
  Given stream jpeg_tiles con keyframe aplicado fn=42
  When llega video_patch(fn=43, base_fn=42) con todos sus tiles JPEG válidos
  Then Android compone todos los tiles y publica la imagen como fn=43
  When llega video_patch(fn=44, base_fn=42)
  Then descarta todo el patch sin alterar fn=43 y espera un keyframe
  And el siguiente keyframe completo se aplica y recupera la sincronía

Scenario: Frame corrupto no congela la vista
  Given stream activo con Frame activo fn=42
  When llega video_frame(fn=43) con data inconsistente
  Then el frame 43 se descarta y la vista sigue mostrando fn=42
  And el siguiente video_frame válido se muestra

Scenario: Corte breve de red (REQ-005 AC-2)
  Given stream en V1 y la red del teléfono cae 5 s
  When la red se restablece
  Then la app reconecta, re-autentica con el token retenido y vuelve a V1
  And no se muestra QR ni se pide re-pairing

Scenario: Sin juego compatible (REQ-004 AC-3)
  Given sesión activa y ningún stream en curso
  When el usuario abre la vista de Segunda pantalla
  Then ve el estado "sin segunda pantalla activa", no una imagen congelada
```

## 14. Invariants

- `fn` es estrictamente creciente en keyframes y patches aplicados; cada `video_patch.base_fn` identifica el último mensaje de imagen aplicado.
- Los patches se aplican completamente o no se aplican; nunca se presenta una mezcla parcial.
- Desde cualquier patch inválido/pérdida de base, se espera un keyframe completo que llega en ≤1 s.
- Ningún frame se muestra antes de un `stream_start` vigente.
- La sesión de SPEC-001 precede siempre a cualquier tráfico de stream.
- Tras cada operación (frame, gap, corte), la vista muestra el Frame activo válido más reciente o el estado "sin segunda pantalla activa"; nunca basura decodificada.

## 15. What "complies" means

Una implementación cumple este SPEC si y solo si: honra las precondiciones (§3), produce los outputs de §5 en cada condición, reproduce cada error de §9 con su trigger, se comporta como define cada edge case de §10, mantiene los invariants de §14 y pasa las verificaciones de §16. Todo lo no definido aquí NO es requerido — no inventar requisitos.

## 16. Verification

| Aspect | Verified by |
|---|---|
| Flujo principal (§7) y AC-1 de REQ-004 | TEST-015 |
| Cambio de fuente confirmada y relay exclusivo | TEST-051 |
| Ausencia de medios generados/capturados por Server | TEST-054 |
| Frame corrupto/tardío y edge cases de §10 | TEST-016 |
| Reanudación tras corte breve (§8.2, REQ-005 AC-2) | TEST-017 |
| Codec, diff por tiles, base mismatch, composición atómica y keyframe periódico | TEST-027, TEST-028 |
| Latencia NFR-001 y CPU NFR-005 | pruebas de performance en banco físico (fuera de CI) |

## 17. Open questions

- ¿Formato v1 sin compresión o con compresión desde el día 1? — owner: Eng lead — 2026-10-07 — **resuelta: sin compresión en v1; `rgb8` es el único formato requerido en la primera implementación (`gray8` queda definido pero no exigido). Si la medición de NFR-001 fallara, la compresión entra por CR.**
- **Ampliada por CR-001 (2026-10-08, owner): la medición de ancho de banda demuestra que rgb8 crudo no alcanza 720p/1080p a 30/60 fps en WiFi LAN (663 Mbps–3 Gbps teóricos), por lo que v1.1 añade `fmt=jpeg` como formato requerido para esos objetivos; `rgb8`/`gray8` siguen definidos. H.264 queda como evolución si el banco físico demuestra que JPEG no alcanza NFR-001.**
- ¿Umbral de degradación (§8.3): qué métrica exacta dispara la reducción (cola de envío en frames, % de pérdida)? — owner: Eng lead — 2026-10-07 — abierta (la implementación puede dejarla parametrizada; no bloquea AC-1/AC-3)
- ¿Qué transmite el stream mientras no existe un Plugin seleccionado? — owner: usuario — 2026-10-18 — **resuelta por CR-010: no transmite video; no se captura escritorio ni se genera demo.**

## Definition of Done

- [x] Deterministic: two readers cannot reasonably interpret any statement differently.
- [x] All flows, errors and edge cases enumerated — no "etc.".
- [x] Every error and edge case has exact expected behavior.
- [x] Inputs/outputs fully enumerated with types and constraints.
- [x] No implementation detail beyond the boundary contract (el canal es el de SPEC-001; el códec de compresión queda deliberado fuera de v1 salvo CR).
- [x] Every aspect has a TEST row; rows mirrored in traceability.md.
- [x] No contradictions with the implementing REQ.
- [x] No placeholders; Status: Approved (CR-002).

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Eng lead + agent | Draft inicial: transporte de framebuffers sobre la sesión de SPEC-001 |
| 1.0.0 | 2026-10-07 | Eng lead | Approved: OQ-1 resuelta (rgb8 sin compresión en v1) |
| 1.1.0 | 2026-10-08 | Owner (usuario, CR-001) | `fmt=jpeg`: compresión del stream para 720p/1080p a 30/60 fps |
| 1.2.0 | 2026-10-16 | Owner (usuario, CR-002) | `jpeg_tiles`, patches JPEG de 64×64, base_fn y keyframes ≤1 s; despliegue coordinado sin compatibilidad hacia atrás |
| 1.3.0 | 2026-10-18 | Product owner + Twinpad team (CR-010) | Fuente exclusiva Plugin seleccionado; sin captura/demo Server y reinicio limpio al cambiar fuente |
