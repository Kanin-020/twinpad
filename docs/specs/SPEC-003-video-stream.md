# SPEC-003 — Stream de video de la Segunda pantalla (framebuffers)

| | |
|---|---|
| ID | SPEC-003 |
| Title | Stream de video de la Segunda pantalla (framebuffers) |
| Version | 1.1.0 |
| Status | `Approved` |
| Owner | Eng lead |
| Implements | REQ-004 (primario), REQ-005 (parcial: solo video) |
| Realized by | DES-XXX (`-` hasta asignar) |
| Verified by | TEST-015, TEST-016, TEST-017 |
| Last updated | 2026-10-08 |

## 1. Purpose

Define el comportamiento observable del transporte y la presentación del Stream de video de la Segunda pantalla: cómo los framebuffers que el PC Host captura (ADR-005) llegan al Android device por la sesión de SPEC-001, con qué orden, qué muestra el teléfono y cómo se comporta ante cortes y degradación de la red. Implementa REQ-004 (AC-1, AC-3) y la parte de video de REQ-005 (AC-2); la sincronía con audio (REQ-005 AC-3) es de un SPEC posterior.

## 2. Definitions

- **Framebuffer**: un cuadro completo de la Segunda pantalla, codificado como imagen (ADR-005: framebuffers/imágenes, no HTML ni datos estructurados).
- **Stream de video**: secuencia ordenada de Framebuffers transmitida PC → Android durante una sesión.
- **Frame number (`fn`)**: número secuencial monótono del Framebuffer dentro del stream.
- **Frame activo**: el último Framebuffer recibido y descodificable en el Android.
- **Degradación**: reducción acordada de fps o resolución ante pérdida de ancho de banda (REQ-004 §8).
- Los términos **Sesión autenticada**, **Pairing**, **Stream**, **Segunda pantalla** y **Servidor PC**: ver CTX §8 y SPEC-001 §2.

## 3. Preconditions

- Sesión autenticada establecida según SPEC-001 (estado U4/`Established`); el transporte de SPEC-003 no acepta conexiones fuera de sesión (NFR-004).
- El Server PC tiene un Framebuffer disponible o razonablemente reciente que transmitir (el origen del contenido es un Plugin, REQ-006/007; fuera de alcance aquí).

## 4. Inputs

| Field | Type | Constraints | Required |
|---|---|---|---|
| `type` | string | exactamente `"video_frame"` | yes |
| `fn` | integer (uint64) | monotónico creciente, empieza en 0 en cada stream; nunca se repite | yes |
| `ts` | integer (uint64) | milisegundos del reloj del server (monótono respecto de sí mismo) en el envío | yes |
| `w` | integer (uint16) | ancho del Framebuffer en píxeles, 16–3840 | yes |
| `h` | integer (uint16) | alto del Framebuffer en píxeles, 16–2160 | yes |
| `fmt` | string | `gray8` \| `rgb8` \| `jpeg` (v1.1, CR-001) | yes |
| `data` | string | `rgb8`/`gray8`: base64url del framebuffer crudo, `w×h×bytes(fmt)` bytes; `jpeg`: base64url del archivo JPEG completo (con cabecera), tamaño libre | yes |
| Control `stream_start` | mensaje | `type:"stream_start"`, `w`,`h`,`fmt`,`fps` (uint8, 1–60) — anuncia el stream y sus parámetros iniciales | yes antes del primer frame |
| Control `stream_stop` | mensaje | `type:"stream_stop"`, `reason`: `plugin_stopped` \| `server_shutdown` | yes al terminar |
| Control `stream_gap` | mensaje | `type:"stream_gap"`, `from_fn`,`to_fn` — notifica frames perdidos por transporte | opcional |

Canal: la conexión TCP de la sesión (mismo socket que el Handshake de SPEC-001, persistente desde esta iteración). Cada mensaje es una línea NDJSON terminada en `\n`.

## 5. Outputs

| Result | Value / shape | Condition |
|---|---|---|
| Pantalla de Segunda pantalla | vista dedicada que presenta el Frame activo a pantalla completa del área asignada, escalado a la orientación vigente | stream activo con frames recibidos |
| Estado "sin segunda pantalla activa" | mensaje en la vista, no pantalla congelada | sesión activa sin stream (REQ-004 AC-3) |
| Frame descartado | el Framebuffer corrupto/incompleto no se muestra; se mantiene el Frame activo previo | `data` inconsistente con `w`,`h`,`fmt` |
| Reanudación | el stream se recibe de nuevo en la misma conexión tras un corte transitorio de red del lado Android < 10 s | reconexión TCP (REQ-005 AC-2) |

## 6. States

Estados del stream (independientes del estado de sesión de SPEC-002):

- `V0 Inactive`: sin stream; la vista muestra "sin segunda pantalla activa".
- `V1 Streaming`: llegan frames con `fn` consecutivos.
- `V2 Interrupted`: la conexión/session cayó o no llegan frames en ≥ 2 s.

| From | Event | To | Side effects |
|---|---|---|---|
| `V0` | `stream_start` | `V1` | vista pasa a mostrar frames |
| `V1` | `video_frame` con `fn` = último+1 | `V1` | Frame activo actualizado |
| `V1` | `video_frame` con `fn` ≤ último | `V1` | frame tardío descartado (no se muestra) |
| `V1` | `stream_gap` | `V1` | los `fn` del hueco se marcan saltados |
| `V1` | `stream_stop` | `V0` | vista vuelve a "sin segunda pantalla activa" |
| `V1` | sin frames en ≥ 2 s o corte TCP | `V2` | la app intenta recuperación (§8.2) |
| `V2` | `stream_start` (o siguiente `video_frame`) tras reconectar | `V1` | reanudación sin re-pairing |
| `V2` | recuperación imposible en 10 s | `V0` | vista "sin segunda pantalla activa" + estado de sesión según SPEC-002 |

Invariant: en `V1` y `V2` la sesión sigue autenticada; salir de la sesión (SPEC-001/002) fuerza `V0` y libera recursos.

## 7. Main flow

1. Con la sesión establecida (SPEC-001), el Server PC envía `stream_start` con `w`, `h`, `fmt` y `fps` acordados.
2. El Server PC captura el Framebuffer actual del Plugin y envía `video_frame` con `fn` incremental y `ts` del envío; repite a `fps` mientras haya contenido.
3. El Android recibe cada `video_frame`, valida que `data` decodifique exactamente `w×h×bytes(fmt)` y actualiza el Frame activo.
4. La vista de Segunda pantalla presenta el Frame activo escalado a la orientación del teléfono, sin intervención del usuario (REQ-004 AC-1).
5. Al terminar, el Server envía `stream_stop` y la vista vuelve a "sin segunda pantalla activa".

## 8. Alternative flows

### 8.1 Cambio de resolución/formato en mitad de sesión

- **Trigger:** el Plugin cambia la resolución o el Server degrada (§8.3).
- **Flow:** el Server envía `stream_start` de nuevo con los parámetros nuevos y `fn` continúa donde estaba (no se reinicia).
- **Result:** el Android re-encuadra la vista sin reiniciar el stream ni la sesión.

### 8.2 Corte transitorio de red (REQ-005 AC-2)

- **Trigger:** caída de WiFi/roaming breve < 10 s.
- **Flow:** el Android entra en `V2`; al restablecerse la red reabre la conexión TCP a `server_addr:server_port` y envía `pair_request` con el **token de sesión retenido** (mismo mecanismo de autenticación de SPEC-001; sin QR). El server valida la sesión registrada y reanuda.
- **Result:** `V1` de nuevo; frames con `fn` mayor al último recibido; nunca se re-escanea el QR.

### 8.3 Degradación por red saturada

- **Trigger:** el server observa pérdida/acumulación que excede el ancho de banda negociado (señal: frames no confirmados o cola de envío creciente).
- **Flow:** el server reduce `fps` a la mitad (hasta un mínimo de 10) y, si persiste, reduce resolución a la mitad por eje, anunciándolo con `stream_start` nuevo (§8.1).
- **Result:** el stream continúa con calidad menor en lugar de congelarse o cortarse.

## 9. Errors

| Condition | Observable behavior | Error code / message |
|---|---|---|
| `video_frame` con `data` de longitud inconsistente | el frame se descarta; se mantiene el Frame activo previo; el stream continúa | sin mensaje al usuario |
| `video_frame` con `fn` ≤ último visto | frame tardío descartado silenciosamente | — |
| Mensaje de stream con `type` desconocido o campos faltantes | se trata como violación de protocolo: la sesión termina con el mecanismo de SPEC-002 | `E4` (SPEC-001 §9) |
| `stream_start`/frame recibidos **sin sesión autenticada** | conexión rechazada en el server | `E3` |
| Corte de red ≥ 10 s | la app vuelve a U1 de SPEC-002; requiere nuevo Pairing | `E1` (SPEC-002 §9) |
| Sin frames en `V1` durante ≥ 2 s (server lento pero vivo) | estado `V2` con indicador de reconexión en la vista; no mensaje de error final hasta §8.2 | indicador, no error |

## 10. Edge cases

| Case | Expected behavior |
|---|---|
| Primer frame con `fn` ≠ 0 o sin `stream_start` previo | el frame se descarta; el stream no arranca hasta recibir `stream_start` |
| Dos `video_frame` con el mismo `fn` | el segundo se descarta (idempotencia por `fn`) |
| Frames con resolución distinta a la del último `stream_start` | frame descartado (inconsistente con parámetros vigentes) |
| Orientación del teléfono cambia en `V1` | la vista re-escala el Frame activo; sin cortes ni re-negociación |
| `stream_stop` durante `V2` | al reconectar se entrega `V0` ("sin segunda pantalla activa") |
| Dos Android en sesión (NFR-006) | cada uno recibe su propio stream por su conexión; el server no intercala frames de uno en otro |
| `stream_gap` que abarca todo lo recibido | la vista mantiene el último Frame activo válido |
| Reloj del server retrocede (`ts` no monótono) | `ts` se usa solo para diagnóstico; la ordenación es por `fn` y jamás por `ts` |

## 11. Business rules

- **R1:** el stream solo existe sobre una sesión autenticada de SPEC-001; sin sesión no hay transmisión (NFR-004).
- **R2:** el orden de presentación lo determina exclusivamente `fn`; ningún otro campo altera el orden.
- **R3:** la latencia objetivo es la de NFR-001 (p95 ≤ 60 ms captura→visible); para cumplirla el stream no retransmite frames: los perdidos se saltan (`stream_gap`) y el render avanza al más reciente.
- **R4:** ante corte < 10 s, la reanudación reutiliza la sesión autenticada y jamás exige re-pairing (REQ-005 AC-2).
- **R5:** la degradación la decide siempre el Server (fps primero, resolución después); el Android nunca la solicita en v1.
- **R6:** si no hay stream, la vista lo indica explícitamente; jamás muestra un Frame congelado como si estuviera vivo (REQ-004 AC-3).

## 12. Contracts

| Contract | Canonical source | Notes |
|---|---|---|
| Mensajes de stream (`stream_start`/`video_frame`/`stream_stop`/`stream_gap`) | este documento §4; NDJSON según SPEC-001 §4.2 (misma conexión y framing) | v1; extensible con nuevos `type` por CR |
| Autenticación de la sesión | SPEC-001 §7/§8 | sin duplicación: se referencia |
| Formato de Framebuffer | ADR-005 + §4 `fmt` de este SPEC | `gray8` (1 B/px), `rgb8` (3 B/px), fila mayor |

## 13. Examples (Given / When / Then)

```gherkin
Scenario: Flujo principal (REQ-004 AC-1)
  Given sesión autenticada y plugin transmitiendo
  When el server envía stream_start(w=1280,h=720,fmt=rgb8,fps=30)
  And luego video_frame(fn=0..n) consecutivos
  Then la vista del Android muestra cada frame sin intervención del usuario

Scenario: Frame corrupto no congela la vista
  Given stream activo con Frame activo fn=42
  When llega video_frame(fn=43) con data inconsistente
  Then el frame 43 se descarta y la vista sigue mostrando fn=42
  And el siguiente video_frame(fn=44) válido se muestra

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

- `fn` es estrictamente creciente en los frames aceptados.
- Ningún frame se muestra antes de un `stream_start` vigente.
- La sesión de SPEC-001 precede siempre a cualquier tráfico de stream.
- Tras cada operación (frame, gap, corte), la vista muestra el Frame activo válido más reciente o el estado "sin segunda pantalla activa"; nunca basura decodificada.

## 15. What "complies" means

Una implementación cumple este SPEC si y solo si: honra las precondiciones (§3), produce los outputs de §5 en cada condición, reproduce cada error de §9 con su trigger, se comporta como define cada edge case de §10, mantiene los invariants de §14 y pasa las verificaciones de §16. Todo lo no definido aquí NO es requerido — no inventar requisitos.

## 16. Verification

| Aspect | Verified by |
|---|---|
| Flujo principal (§7) y AC-1 de REQ-004 | TEST-015 |
| Frame corrupto/tardío y edge cases de §10 | TEST-016 |
| Reanudación tras corte breve (§8.2, REQ-005 AC-2) | TEST-017 |
| Latencia NFR-001 y CPU NFR-005 | pruebas de performance en banco físico (fuera de CI) |

## 17. Open questions

- ¿Formato v1 sin compresión o con compresión desde el día 1? — owner: Eng lead — 2026-10-07 — **resuelta: sin compresión en v1; `rgb8` es el único formato requerido en la primera implementación (`gray8` queda definido pero no exigido). Si la medición de NFR-001 fallara, la compresión entra por CR.**
- **Ampliada por CR-001 (2026-10-08, owner): la medición de ancho de banda demuestra que rgb8 crudo no alcanza 720p/1080p a 30/60 fps en WiFi LAN (663 Mbps–3 Gbps teóricos), por lo que v1.1 añade `fmt=jpeg` como formato requerido para esos objetivos; `rgb8`/`gray8` siguen definidos. H.264 queda como evolución si el banco físico demuestra que JPEG no alcanza NFR-001.**
- ¿Umbral de degradación (§8.3): qué métrica exacta dispara la reducción (cola de envío en frames, % de pérdida)? — owner: Eng lead — 2026-10-07 — abierta (la implementación puede dejarla parametrizada; no bloquea AC-1/AC-3)
- ¿Qué transmite el stream mientras no existe el Plugin (SPEC-006)? — owner: usuario — 2026-10-08 — **resuelta: duplicado del escritorio del PC capturado con `xcap` (`twinpad-capture::DesktopGrabber`, escalado a 640×360 rgb8); si la captura no está disponible, fallback al patrón de demostración animado. El contenido real del juego entra con SPEC-006.**

## Definition of Done

- [x] Deterministic: two readers cannot reasonably interpret any statement differently.
- [x] All flows, errors and edge cases enumerated — no "etc.".
- [x] Every error and edge case has exact expected behavior.
- [x] Inputs/outputs fully enumerated with types and constraints.
- [x] No implementation detail beyond the boundary contract (el canal es el de SPEC-001; el códec de compresión queda deliberado fuera de v1 salvo CR).
- [x] Every aspect has a TEST row; rows mirrored in traceability.md.
- [x] No contradictions with the implementing REQ.
- [x] No placeholders; Status: Draft.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Eng lead + agent | Draft inicial: transporte de framebuffers sobre la sesión de SPEC-001 |
| 1.0.0 | 2026-10-07 | Eng lead | Approved: OQ-1 resuelta (rgb8 sin compresión en v1) |
| 1.1.0 | 2026-10-08 | Owner (usuario, CR-001) | `fmt=jpeg`: compresión del stream para 720p/1080p a 30/60 fps |
