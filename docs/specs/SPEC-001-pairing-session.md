# SPEC-001 — Pairing por QR y Handshake de sesión autenticada

> **What this is:** the precise, testable behavior the system must exhibit for one capability. It is the contract that both tests and implementation must satisfy, and the reference an AI agent implements against.
> **What it is NOT:** a description of code structure (see `design/`) or of why the capability exists (see the REQ it implements).

| | |
|---|---|
| ID | SPEC-001 |
| Title | Pairing por QR y Handshake de sesión autenticada |
| Version | 1.4.0 |
| Status | `Approved` |
| Owner | Eng lead |
| Implements | REQ-002 |
| Realized by | DES-001 (núcleo puro implementado en `core/`; adaptadores TCP/servidor pendientes) |
| Verified by | TEST-001 … TEST-009, TEST-0120 … TEST-0130 (registrados en `testing/traceability.md`) |
| Last updated | 2026-11-12 |

## 1. Purpose

Precisa el comportamiento de la vinculación (Pairing) del Android device con el Servidor PC mediante el QR de vinculación, y del Handshake de sesión autenticada que lo sigue. Implementa REQ-002 (y cubre en su frontera REQ-001 AC-3) bajo las decisiones de ADR-004 y la seguridad de NFR-004.

## 2. Definitions

Los términos de dominio (Pairing, QR de vinculación, Framebuffer, Signature HID, Stream, Sesión) usan la definición canónica del glosario en `product/context.md` §8 — ver CTX §8. Términos locales de este SPEC:

- **Token de sesión**: valor de un solo uso contenido en el QR de vinculación (32 bytes de entropía, ADR-004); quien lo conoce puede autenticar una sesión.
- **Session id**: identificador opaco (16 bytes aleatorios codificados base64url) creado por el servidor en el Handshake y devuelto vía `pair_ack`.
- **Session key**: clave simétrica derivada del token (HKDF-SHA256, §7 paso 8); nunca viaja por la red.
- **Handshake**: el intercambio acotado de mensajes de §7 sobre la conexión TCP abierta tras el escaneo del QR. El término no designa aquí ninguna otra secuencia.
- **Nonce**: cadena aleatoria de 32 bytes codificada base64url (`nonce_s` del servidor, `nonce_c` del cliente).
- **Heartbeat**: intercambio periódico `ping`/`pong` de §4.3 durante S3 que mantiene viva la ligadura y hace observable un corte silencioso (sin EOF) en cualquier extremo.
- **Sesión retenida**: estado de la ligadura tras la caída del socket de control (S4), durante `RETENTION_MS` = 60 000 ms, en el que el servidor conserva `session_id` + `session_key` y admite la reanudación de §8.2 con `pair_resume`.

## 3. Preconditions

- Servidor PC activo y escuchando en la LAN (checked: kernel TCP en el endpoint publicado en el QR; su ausencia produce E1 y cubre REQ-001 AC-3).
- Dispositivo Android 12+ (CTX-C1) con cámara operativa; (la UI de escaneo se define en otro SPEC).
- PC y celular en la misma LAN (CTX-C2, CTX-A1). El Handshake no se define en este SPEC sobre ningún otro transporte (ADR-002).

## 4. Inputs

### 4.1 Payload del QR de vinculación (contrato canónico, versión `twinpad.v1`)

El QR contiene una única cadena URI con el formato:
`twinpad://v1#s=<server_addr>&p=<server_port>&t=<token>`

| Field | Type | Constraints | Required |
|---|---|---|---|
| `s` | server_addr | string: dirección IPv4/IPv6 del Servidor PC en la LAN (o `twinpad.local.` vía mDNS), 1–253 chars | yes |
| `p` | server_port | integer 1024–65535 | yes |
| `t` | token | base64url (RFC 4648 §5, sin relleno), decodifica exactamente 32 bytes (43 chars) | yes |

El nombre `twinpad.local.` de `s` se resuelve vía mDNS/DNS-SD (SPEC-009): el Servidor PC anuncia `_twinpad._tcp.local.` mientras su socket de control escucha; sin mDNS operativo el payload requiere IP literal y el anuncio es best-effort (sin efecto en el Handshake).

Reglas del payload: esquema `twinpad://v1` obligatorio; pares `clave=valor` tras `#`; el orden es libre; duplicar una clave o incluir una clave extra es inválido (→ E5).

### 4.2 Mensajes del Handshake

Transporte: conexión TCP hacia `server_addr:server_port`. Cada mensaje es una línea UTF-8 JSON única terminada en `\n` (NDJSON, sin BOM). Campos extra no definidos → E4; `type` desconocido → E4.

| Message | Direction | Field | Type | Constraints | Required |
|---|---|---|---|---|---|
| `pair_request` | app → servidor | `type` | string | fijo `pair_request` | yes |
| | | `schema` | string | fijo `twinpad.v1` | yes |
| | | `token` | string | igual que §4.1 `t` | yes |
| `pair_challenge` | servidor → app | `type` | string | fijo `pair_challenge` | yes |
| | | `nonce_s` | string | base64url, decodifica 32 bytes | yes |
| `pair_proof` | app → servidor | `type` | string | fijo `pair_proof` | yes |
| | | `nonce_c` | string | base64url, decodifica 32 bytes | yes |
| | | `proof` | string | base64url, decodifica 32 bytes | yes |
| `pair_ack` | servidor → app | `type` | string | fijo `pair_ack` | yes |
| | | `session_id` | string | base64url, decodifica 16 bytes | yes |
| `pair_error` | servidor → app | `type` | string | fijo `pair_error` | yes |
| | | `code` | string | uno de E1–E5 (§9) | yes |
| | | `message` | string | texto legible ≤ 200 chars | yes |
| `pair_resume` | app → servidor | `type` | string | fijo `pair_resume` | yes |
| | | `session_id` | string | base64url, decodifica 16 bytes; ligadura existente no expirada | yes |
| | | `nonce_c` | string | base64url, decodifica 32 bytes, fresco | yes |
| | | `proof` | string | base64url, decodifica 32 bytes | yes |

Un mensaje recibido con `type` desconocido, campo faltante o campo con longitud/valor fuera de constraints se rechaza por E4 o E5 según la tabla §9.

El `proof` del `pair_resume` (CR-056) se computa: `proof = HMAC-SHA256(key = session_key, data = "twinpad.v1 resume" ‖ session_id ‖ nonce_c)` (§8.2).

### 4.3 Heartbeat de sesión (S3)

El Servidor envía `ping` por el socket de control cada 5 000 ms y la app responde `pong`. Ambos mensajes son líneas UTF-8 JSON + LF, como §4.2.

| Message | Direction | Field | Type | Constraints | Required |
|---|---|---|---|---|---|
| `ping` | servidor → app | `type` | string | fijo `ping` | yes |
| | | `t` | integer (uint64) | contador de pings del Servidor; inicia en 0 y aumenta en uno por cada `ping` | yes |
| `pong` | app → servidor | `type` | string | fijo `pong` | yes |
| | | `t` | integer (uint64) | eco exacto del `t` del `ping` respondido | yes |

Reglas del heartbeat:

- **HB1:** el Servidor emite `ping` solo en S3, nunca durante el Handshake (§7).
- **HB2:** por cada `ping` recibido la app envía exactamente un `pong` (eco de `t`).
- **HB3:** un `pong` aceptado requiere `t` estrictamente mayor que el último `pong` aceptado y menor o igual que el último `ping` enviado. Un `pong` fuera de ese rango se descarta sin cerrar la sesión y no refresca la vida.
- **HB4:** si el Servidor no acepta ningún `pong` válido durante 15 000 ms, declara la sesión caída: transición S3→S1 (§6) con los mismos side effects que un corte de conexión, incluido el cierre de su canal media asociado (invariante «Pairing activo» de SPEC-003).
- **HB5:** si la app no recibe ninguna línea del Servidor durante 15 000 ms estando en S3, declara la sesión caída: transición S3→S1 (§6), idéntica a un corte de conexión.
- **HB6:** la expiración del heartbeat no emite `pair_error` ni ningún código E (§9): su comportamiento observable es el de un corte de conexión.
- **HB7 (CR-099):** el heartbeat es estado POR SOCKET. Tras un `pair_resume` aceptado (§8.2) el Servidor reinicia el contador de `ping` en 0, descarta el último pong aceptado y rearma el watchdog HB4 desde cero sobre el socket nuevo; la app hace lo mismo con HB5. Ningún valor de `t` ni de pong del socket anterior se acepta en el nuevo.

## 5. Outputs

| Result | Value / shape | Condition |
|---|---|---|
| QR de vinculación visible | imagen en pantalla del Servidor PC con el payload §4.1 | el Jugador lo genera desde la PC; se regenera automáticamente al expirar (R7) |
| `pair_ack` con `session_id` | mensaje §4.2 | Handshake exitoso |
| Estado Sesión autenticada | app y servidor comparten `session_id` + `session_key` | tras `pair_ack` |
| Token consumido | registro interno del servidor | exactamente un Handshake exitoso por token (R2) |
| `pair_error` | mensaje §4.2 con código E1–E5 | trigger según §9 |

## 6. States

Estados (lado app; el servidor mantiene un registro espejo por conexión): `S1 Desconectado`, `S2 Handshake`, `S3 Sesión autenticada`, `S4 Sesión retenida` (lado servidor: ligadura viva sin socket de control; lado app: Vinculado degradado, «Reconectando»).

| From | Event | To | Side effects |
|---|---|---|---|
| S1 | escaneo de QR válido | S2 | app abre TCP a `server_addr:server_port` |
| S2 | `pair_ack` recibido | S3 | app guarda `session_id` y `session_key` (§8.2) |
| S2 | `pair_error` o fallo de conexión | S1 | app muestra el mensaje del §9 correspondiente |
| S3 | corte de conexión (cualquier extremo) | S4 | la ligadura entra en retención (§8.2); la app pasa a «Reconectando» e intenta `pair_resume` |
| S4 | `pair_resume` válido (§8.2) | S3 | mismo `session_id` y misma `session_key`; el canal media anclado sigue usable |
| S4 | retención > 60 000 ms | S1 | el servidor destruye la ligadura; la app muestra "Sesión terminada" (requiere QR nuevo) |
| S4 | desconexión simultánea sin `pair_resume` | S1 | según expire la ventana; sin nuevo `pair_ack` de Handshake |
| S1 | revocación del emparejamiento | S1 | el servidor destruye la credencial (§8.3) |

Invariantes del ciclo: de S2 solo se sale a S3 vía `pair_ack` o a S1 por error; un token expira (R3) o se consume (R2), nunca ambos.

En S3 el heartbeat de §4.3 (HB1–HB6) está activo en ambos extremos; su expiración produce S3→S1 con los side effects de «corte de conexión», incluido el cierre del canal media asociado (SPEC-003). En S4 no hay heartbeat: el canal media anclado al `session_id` conservado sigue aceptando tráfico mientras la retención viva (SPEC-003).

## 7. Main flow (Handshake exitoso)

1. El Jugador pide el QR en la PC: el Servidor PC genera un token de 32 bytes con su RNG criptográfico, registra la hora local de emisión y muestra el QR (§4.1).
2. El Jugador escanea el QR con la app Twinpad.
3. La app decodifica el payload; si no cumple §4.1 → E5 (local) y el flujo termina.
4. La app abre TCP a `server_addr:server_port`; si el TCP falla o no hay respuesta en 5 s → E1.
5. La app envía `pair_request` con el token.
6. El servidor valida el mensaje y el token: (a) `schema` = `twinpad.v1`; (b) token bien formado; (c) no expirado (≤ 300 s desde su emisión según el reloj del servidor, R3); (d) no consumido (R2). Fallo → E2 o E3.
7. El servidor genera `nonce_s` (32 bytes, RNG criptográfico) y envía `pair_challenge`.
8. La app genera `nonce_c` (32 bytes, RNG criptográfico) y computa:
   `session_key = HKDF-SHA256(ikm = token, salt = nonce_s, info = "twinpad.v1 session")`
   `proof = HMAC-SHA256(key = session_key, data = pair_request ‖ pair_challenge ‖ nonce_s ‖ nonce_c)`
   y envía `pair_proof`. (Primitivas exactas sujetas a revisión — §17.)
9. El servidor reconstruye `session_key` con su token y verifica `proof` de forma exacta recorriendo todos los bytes (comparación sin salida temprana, defensiva frente a timing).
10. Éxito: el servidor marca el token consumido, genera `session_id` (16 bytes aleatorios) y envía `pair_ack` (`session_id`). Ambos quedan en S3.
11. La app muestra "Vinculado" (parte del flujo; UI final definida en su SPEC).
12. Con ambos extremos en S3, el heartbeat de §4.3 queda activo: el Servidor envía `ping` (`t=0`) y la app responde `pong`.

## 8. Alternative flows

### 8.1 Atacante en la LAN sin token

- **Trigger:** un dispositivo de la LAN que nunca escaneó el QR abre la conexión y envía `pair_request` (sin token, con token inventado, o directamente `pair_proof`).
- **Flow:** no puede derivar `session_key` (HKDF-SHA256 con el token que no conoce), por lo que su `proof` es inválido salvo una colisión SHA-2 práctica.
- **Result:** la conexión recibe `pair_error` E3 y no crea ninguna sesión — NFR-004 y REQ-002 AC-2.

### 8.2 Reanudación del canal de control (CR-056)

- **Trigger:** se pierde el socket de control (S3→S4): corte de red, app suspendida o router; SST sin revocación del emparejamiento.
- **Flow (lado servidor):** al caer el socket el servidor entra la ligadura (`session_id` + `session_key`) en retención por `RETENTION_MS` = 60 000 ms. Durante la ventana admite `pair_resume` (§4.2): verifica en tiempo constante `proof = HMAC-SHA256(session_key, "twinpad.v1 resume" ‖ session_id ‖ nonce_c)`; aceptado → re-admite la sesión y responde `pair_ack` con el MISMO `session_id` (el canal media ya anclado a ese `session_id` sigue usable). Reintentos ilimitados dentro de la ventana; cada `pair_resume` aceptado conserva el `session_id` retenido sin reemplazarlo.
- **Flow (lado app):** la app conserva `session_id` y `session_key` en memoria mientras la ventana viva y reintenta `pair_resume` con `nonce_c` fresco (delay 2 000 ms entre reintentos, primer intento inmediato). Ventana vencida → "Sesión terminada" (requiere re-scan del QR).
- **Primera línea del socket (CR-099):** en un socket nuevo, la primera línea SOLO puede ser `pair_request`, `pair_proof` o `pair_resume`. Cualquier otro `type` como primera línea → `pair_error` E4 y cierre.
- **Estado tras reanudar (CR-099):** tras `pair_ack` de reanudación el heartbeat arranca de cero (HB7). El canal media UDP se re-enlaza con un nuevo JOIN (SPEC-003 §4.4) antes de contar el silencio de medios; hasta entonces el silencio UDP no dispara el fallback de §8.5 de SPEC-003 ni cierra la sesión.
- **Result:** reanudación sin re-escanear el QR ni re-derivar credenciales media; `pair_error` E3 ante ligadura desconocida, retención expirada o `proof` inválido, sin desglosar la causa al cliente.

### 8.3 Revocación del emparejamiento

- **Trigger:** el Jugador revoca el emparejamiento desde la app o desde la PC.
- **Flow:** el propietario de la credencial borra `session_id`, la `session_key` y el binding del almacén de sesiones.
- **Result:** la sesión finaliza; un intento posterior con esas credenciales recibe E3. La credencial solo reside en memoria del servidor o en el almacén cifrado local (ARCH §5, owner: Servidor PC).

## 9. Errors

| Code | Condition | Observable behavior |
|---|---|---|
| E1 | conexión TCP rechazada o sin respuesta en 5 s (paso 4) | la app muestra "Servidor no disponible" y vuelve a S1 (REQ-001 AC-3) |
| E2 | `pair_request` con token emitido hace más de 300 s según el servidor | `pair_error` E2; la app muestra "QR expirado, generar uno nuevo" y vuelve a S1 (REQ-002 AC-3) |
| E3 | token ya consumido (otro dispositivo completó el Handshake), o `proof` HMAC inválido, o `pair_resume` (§8.2) con `session_id` desconocido, retención expirada o `proof` inválido, o mensajes fuera de secuencia en el flujo | `pair_error` E3; la app muestra "Vinculación rechazada" y cierra la conexión |
| E4 | mensaje NDJSON no parseable como JSON, `type` desconocido, campo faltante o valor fuera de constraints (§4.2) | `pair_error` E4 (servidor) o rechazo silencioso del mensaje (app) |
| E5 | payload del QR no conforme a §4.1 (esquema equivocado, token con longitud de bytes distinta de 32, clave duplicada o extra) | la app muestra "QR no válido" sin abrir conexión (paso 3) |

Sin respuesta a `pair_challenge` en 10 s: el servidor cierra la conexión sin `pair_ack`; el token no queda consumido (§14).

El heartbeat (§4.3 HB4–HB6) no genera códigos E: su expiración equivale a un corte de conexión en S3 (§6).

## 10. Edge cases

| Case | Expected behavior |
|---|---|
| Dos dispositivos escanean el mismo QR simultáneamente | el primero que completa el paso 9 gana; el otro recibe E3 (R2, un solo uso) |
| QR escaneado tras 300 s de emitido | error E2 al presentar `pair_request` |
| QR expirado sin que ningún dispositivo lo haya escaneado | el Servidor PC genera un token nuevo y muestra el QR nuevo automáticamente, sin intervención del Jugador (R7); el token expirado sigue respondiendo E2 si se presenta |
| Cámara o decodificación produce un payload parcial | E5 sin abrir conexión (§9) |
| Dos celulares con distintas sesiones activas a la vez | permitido (NFR-006); cada ligadura tiene su propia `session_key` y `session_id` |
| QR fotografiado por un observador y reutilizándose dentro de los 300 s | puede autenticar una sesión válidamente (R2 + R3: la ventana de 5 min es el límite aceptado en ADR-004 §6) |
| Corte de red en mitad del Handshake (S2) | la app vuelve a S1 con E1 o a S3 según el resultado del paso 9; no hay estado nuevo |
| Reinicio del servidor en mitad del Handshake | la conexión cae; la app recibe E1 y puede reintentar con un QR nuevo |
| Nombres de campo case-sensitive | `type` y demás claves JSON son case-sensitive; un `"Type"` distinto es E4 |
| Retención expirada justo en el intento de `pair_resume` (CR-056) | E3 sin desglosar causa (§9): la app vuelve a U1/S1 y exige re-scan |
| `pair_resume` con `session_id` de otra ligadura (CR-056) | E3 sin desglosar causa (§9) |
| Reinicio del servidor durante la retención (CR-056) | todas las ligaduras se pierden: cualquier `pair_resume` posterior es E3 y exige QR nuevo (§14) |
| Doble `pair_resume` simultáneo (CR-056) | el primero consuma la ligadura y gane; el segundo recibe E3 |

## 11. Business rules

- **R1:** El payload del QR usa el esquema `twinpad://v1`; un esquema distinto es E5.
- **R2:** El token del QR es de un solo uso: exactamente un Handshake exitoso por token; los intentos fallidos no lo consumen (ADR-004).
- **R3:** El QR expira 300 s (5 min) después de su emisión, medida con el reloj del servidor (R3: 5 min definidos en la decisión del dueño — 2026-10-07).
- **R4:** La autenticación usa HKDF-SHA256 para derivar la `session_key` del token, y HMAC-SHA256 para el challenge mutuo; las primitives exactas están sujetas a revisión criptográfica (§17).
- **R5:** El servidor no registra tokens, `session_key` ni `proof` en logs (ENG §7); los eventos de sesión citan `session_id`.
- **R6:** El token y la credencial de sesión residen solo en memoria del servidor o en el almacén cifrado local (ARCH §5); owner: Servidor PC.
- **R7:** Al expirar el QR vigente (300 s, R3) sin haber sido consumido, el Servidor PC genera automáticamente un token nuevo y muestra el QR nuevo, sin intervención del Jugador (decisión del dueño — 2026-10-07). El token expirado no se reutiliza y responde E2.

## 12. Contracts

| Contract | Canonical source | Notes |
|---|---|---|
| Payload del QR (`twinpad://v1#…`) | este SPEC §4.1 | la versión va dentro del esquema URI; cambio de versión → CR |
| Mensajes del Handshake (NDJSON) | este SPEC §4.2 | añadir/quitar campos o tipos exige CR |
| Primitivas criptográficas | este SPEC §7 pasos 8–9 y R4 | el DES precisa la revisión criptográfica del Eng lead |
| Mensajes de heartbeat (`ping`/`pong`) | este SPEC §4.3 | añadir/quitar tipos exige CR |

## 13. Examples (Given / When / Then)

```gherkin
Scenario: Pairing exitoso
  Given un Servidor PC activo y un QR de vinculación válido (token no consumido, no expirado)
  When la app escanea el QR y envía pair_request, y luego un pair_proof válido
  Then recibe pair_ack con un session_id y ambos extremos quedan en Sesión autenticada

Scenario: QR expirado
  Given un QR cuyo token fue emitido hace más de 300 s según el reloj del servidor
  When la app envía pair_request con ese token
  Then recibe pair_error E2 "QR expirado, generar uno nuevo" y queda en S1

Scenario: Conexión sin token (atacante en la LAN — NFR-004)
  Given un dispositivo en la LAN que nunca escaneó el QR
  When envía pair_request con un token inventado
  Then recibe pair_error E3 y el servidor no crea ninguna sesión (REQ-002 AC-2)

Scenario: Segundo dispositivo con el mismo QR
  Given el token del QR ya fue consumido por otro dispositivo
  When un segundo dispositivo envía pair_request con ese token
  Then recibe pair_error E3 y no crea sesión (REQ-002 AC-2)

Scenario: Reanudación de sesión sin re-escaneo (CR-056)
  Given una sesión S3 previa cuya ligadura está en retención (S4, ventana de 60 000 ms vivo) y no revocada
  When la app reconecta y envía pair_resume (session_id + nonce_c + proof HMAC "twinpad.v1 resume")
  Then el servidor verifica con la misma session_key y responde pair_ack con el MISMO session_id (el canal media anclado sigue usable; REQ-002 §2)\n\nScenario: Retención vencida
  Given una ligadura en S4 cuya retención supera los 60 000 ms
  When la app intenta pair_resume
  Then recibe pair_error E3 y la app queda en U1/S1, exigiendo un QR nuevo

Scenario: Servidor no disponible
  Given el Servidor PC inactivo
  When la app intenta conectar al endpoint (REQ-001 AC-3)
  Then la app muestra "Servidor no disponible" E1 sin crear sesión

Scenario: Regeneración automática del QR
  Given un QR mostrado cuyo token alcanza los 300 s sin consumirse
  When el token expira
  Then el Servidor PC muestra automáticamente un QR nuevo con un token nuevo, sin intervención del Jugador

Scenario: Heartbeat mantiene viva la sesión ociosa
  Given una sesión S3 sin tráfico de medios ni inputs
  When el Servidor envía pings cada 5 s y la app responde pong
  Then la sesión permanece en S3 (HB1–HB2)

Scenario: Teléfono desaparece sin EOF (sesión zombi)
  Given una sesión S3 activa
  When el celular pierde la red sin cerrar el TCP (sin EOF hacia el servidor)
  Then el Servidor deja de aceptar pongs y a los 15 000 ms declara la sesión caída (HB4), cerrando la ligadura y su canal media
```

## 14. Invariants

- Un token expira (R3) o se consume (R2), nunca ambos; ningún consumo parcial.
- El QR mostrado en pantalla del Servidor PC es siempre vigente: al expirar se regenera automáticamente (R7).
- En S2 el token sigue de un solo uso: un intento abortado no lo consume; solo el paso 10 lo hace.
- Dentro de S3, cada `session_id` identifica exactamente una ligadura activa con su propia `session_key`; un reinicio del servidor elimina todas las ligaduras (la reanudación §8.2 ya no es posible): la retención es solo memoria de proceso, alineada con el almacenamiento de la `session_key` (R6).
- Cada `pair_resume` aceptado reutiliza el `session_id` retenido; el servidor no lo reemplaza durante la retención.
- `session_key` nunca sale de la memoria del proceso y no se firma en ningún mensaje (solo derivados como `proof`).
- El heartbeat de §4.3 no consume ni renueva tokens (R2/R3 intactos) y no modifica `session_id` ni `session_key`; solo mantiene viva la ligadura de S3.

## 15. What "complies" means

An implementation complies with this SPEC if and only if: all preconditions are honored, all flows produce the defined outputs, every error row is reproduced by its trigger, every edge case behaves as defined, all invariants hold, and all verification tests (§16) pass. Anything not defined here is NOT required — do not invent requirements.

## 16. Verification

| Aspect | Verified by |
|---|---|
| Main flow (Handshake exitoso) | TEST-001 |
| E1 Servidor no disponible | TEST-002 |
| E2 QR expirado | TEST-003 |
| E3 sin token / token consumido / proof inválido | TEST-004 |
| E3 segundo dispositivo con el mismo QR | TEST-005 |
| E4 mensaje malformado / schema desconocido | TEST-006 |
| §8.2 reanudación (CR-056: `pair_resume` + retención) | TEST-0128, TEST-0129 (TEST-007: fluj §8.2/registro, ya realizada por CR-056) |
| §4.1 resolución mDNS de `twinpad.local.` (CR-057) | TEST-0130, TEST-0133, TEST-0134 |
| §8.3 revocación | TEST-008 |
| R7 regeneración automática del QR al expirar | TEST-009 |
| HB1–HB3 (emisión, eco de `t` y validación del rango) | TEST-0120, TEST-0122 |
| HB4 expiración server-side (sesión zombi sin EOF) | TEST-0120, TEST-0121 |
| HB5 silencio del Servidor percibido por la app | TEST-0122 |
| CR-056: retención y destrucción de la ligadura a los 60 000 ms | TEST-0128, TEST-0129 |
| CR-056: `pair_resume` válido y rechazado (proof, session_id, expiración) | TEST-0128, TEST-0129 |
| CR-056: app — corte de control en S3 → reintento automático ≤ 60 s sin QR | TEST-0130 |
| CR-099 HB7: tras `pair_resume` el heartbeat reinicia en el socket nuevo (contador, último pong, HB4/HB5) | TEST-0189 |
| CR-099 §8.2: primera línea de socket nueva limitada a `pair_request`/`pair_proof`/`pair_resume`; otra → E4 | TEST-0190 |

## 17. Open questions

- ¿Regeneración automática del QR al expirar, o solo a petición del Jugador? — Eng lead — 2026-10-07 — resuelta: regeneración automática (decisión del dueño, 2026-10-07; R7)
- ¿Las primitivas HKDF-SHA256 + HMAC-SHA256 del §7 son definitivas (requieren revisión criptográfica del Eng lead antes de `Approved`)? — Eng lead — 2026-10-07 — resuelta: sí, definitivas (confirmación del dueño, 2026-10-07)

## Definition of Done

- [x] Deterministic: two readers cannot reasonably interpret any statement differently.
- [x] All flows, errors and edge cases enumerated — no "etc.", "and so on", "as appropriate".
- [x] Every error and edge case has exact expected behavior.
- [x] Inputs/outputs fully enumerated with types and constraints.
- [x] No implementation detail beyond the boundary contract.
- [x] Every aspect has a TEST row; rows mirrored in traceability.md.
- [x] No contradictions with the implementing REQ.
- [x] No placeholders; Status updated.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Twinpad team | Initial draft: QR de vinculación + Handshake autenticado; validez QR 5 min (decisión del dueño) |
| 0.2.0 | 2026-10-07 | Twinpad team | OQ-1 resuelta: regeneración automática del QR al expirar (R7, TEST-009) |
| 0.3.0 | 2026-10-07 | Twinpad team | OQ-2 resuelta: primitivas HKDF-SHA256 + HMAC-SHA256 definitivas; Status → `In review` |
| 1.1.0 | 2026-11-01 | Buffy (agente) | CR-055: heartbeat `ping`/`pong` en S3 (§4.3, HB1–HB6) con expiración de sesión zombi; TEST-0120/0121/0122 |
| 1.2.0 | 2026-11-01 | Buffy (agente) | CR-056: reanudación del canal de control — estado S4, mensaje `pair_resume` (§4.2) con proof sobre "twinpad.v1 resume", retención 60 000 ms y `session_id` preservado; TEST-0128/0129/0130 |
| 1.4.0 | 2026-11-12 | Buffy (agente) | CR-099: HB7 (heartbeat por socket, reinicio tras `pair_resume`); §8.2 primera línea de socket nueva limitada y estado tras reanudar; TEST-0189/0190 |
| 1.3.0 | 2026-11-01 | Buffy (agente) | CR-057: §4.1 nota de resolución mDNS de `twinpad.local.` (SPEC-009) |
| 1.0.0 | 2026-10-07 | Twinpad team | `Approved` (decisión del dueño, 2026-10-07); DoD verificado |
