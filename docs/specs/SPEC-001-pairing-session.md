# SPEC-001 — Pairing por QR y Handshake de sesión autenticada

> **What this is:** the precise, testable behavior the system must exhibit for one capability. It is the contract that both tests and implementation must satisfy, and the reference an AI agent implements against.
> **What it is NOT:** a description of code structure (see `design/`) or of why the capability exists (see the REQ it implements).

| | |
|---|---|
| ID | SPEC-001 |
| Title | Pairing por QR y Handshake de sesión autenticada |
| Version | 0.2.0 |
| Status | `Draft` |
| Owner | Eng lead |
| Implements | REQ-002 |
| Realized by | `-` (hasta asignar DES) |
| Verified by | TEST-001 … TEST-008 (registrados en `testing/traceability.md`, aún sin código) |
| Last updated | 2026-10-07 |

## 1. Purpose

Precisa el comportamiento de la vinculación (Pairing) del Android device con el Servidor PC mediante el QR de vinculación, y del Handshake de sesión autenticada que lo sigue. Implementa REQ-002 (y cubre en su frontera REQ-001 AC-3) bajo las decisiones de ADR-004 y la seguridad de NFR-004.

## 2. Definitions

Los términos de dominio (Pairing, QR de vinculación, Framebuffer, Signature HID, Stream, Sesión) usan la definición canónica del glosario en `product/context.md` §8 — ver CTX §8. Términos locales de este SPEC:

- **Token de sesión**: valor de un solo uso contenido en el QR de vinculación (32 bytes de entropía, ADR-004); quien lo conoce puede autenticar una sesión.
- **Session id**: identificador opaco (16 bytes aleatorios codificados base64url) creado por el servidor en el Handshake y devuelto vía `pair_ack`.
- **Session key**: clave simétrica derivada del token (HKDF-SHA256, §7 paso 8); nunca viaja por la red.
- **Handshake**: el intercambio acotado de mensajes de §7 sobre la conexión TCP abierta tras el escaneo del QR. El término no designa aquí ninguna otra secuencia.
- **Nonce**: cadena aleatoria de 32 bytes codificada base64url (`nonce_s` del servidor, `nonce_c` del cliente).

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

Un mensaje recibido con `type` desconocido, campo faltante o campo con longitud/valor fuera de constraints se rechaza por E4 o E5 según la tabla §9.

## 5. Outputs

| Result | Value / shape | Condition |
|---|---|---|
| QR de vinculación visible | imagen en pantalla del Servidor PC con el payload §4.1 | el Jugador lo genera desde la PC; se regenera automáticamente al expirar (R7) |
| `pair_ack` con `session_id` | mensaje §4.2 | Handshake exitoso |
| Estado Sesión autenticada | app y servidor comparten `session_id` + `session_key` | tras `pair_ack` |
| Token consumido | registro interno del servidor | exactamente un Handshake exitoso por token (R2) |
| `pair_error` | mensaje §4.2 con código E1–E5 | trigger según §9 |

## 6. States

Estados (lado app; el servidor mantiene un registro espejo por conexión): `S1 Desconectado`, `S2 Handshake`, `S3 Sesión autenticada`.

| From | Event | To | Side effects |
|---|---|---|---|
| S1 | escaneo de QR válido | S2 | app abre TCP a `server_addr:server_port` |
| S2 | `pair_ack` recibido | S3 | app guarda `session_id` y `session_key` (§8.2) |
| S2 | `pair_error` o fallo de conexión | S1 | app muestra el mensaje del §9 correspondiente |
| S3 | corte de conexión (cualquier extremo) | S1 | app muestra "Sesión terminada"; §8.2 permite reanudar |
| S1 | revocación del emparejamiento | S1 | el servidor destruye la credencial (§8.3) |

Invariantes del ciclo: de S2 solo se sale a S3 vía `pair_ack` o a S1 por error; un token expira (R3) o se consume (R2), nunca ambos.

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

## 8. Alternative flows

### 8.1 Atacante en la LAN sin token

- **Trigger:** un dispositivo de la LAN que nunca escaneó el QR abre la conexión y envía `pair_request` (sin token, con token inventado, o directamente `pair_proof`).
- **Flow:** no puede derivar `session_key` (HKDF-SHA256 con el token que no conoce), por lo que su `proof` es inválido salvo una colisión SHA-2 práctica.
- **Result:** la conexión recibe `pair_error` E3 y no crea ninguna sesión — NFR-004 y REQ-002 AC-2.

### 8.2 Reanudación de sesión (REQ-002 §2)

- **Trigger:** se pierde la conexión TCP (corte de red, app suspendida, router) sin que se haya revocado el emparejamiento.
- **Flow:** la app conserva `session_id` y `session_key` en memoria; reconecta y envía `pair_proof` (formato §4.2) con `nonce_c` fresco y `session_id` conocido; el servidor busca la ligadura con ese `session_id` y verifica la prueba con la misma `session_key`.
- **Result:** `pair_ack` con un `session_id` nuevo (cada conexión TCP recibe su propio `session_id`), pero el servidor reanuda la misma sesión; sin re-escanear el QR.

### 8.3 Revocación del emparejamiento

- **Trigger:** el Jugador revoca el emparejamiento desde la app o desde la PC.
- **Flow:** el propietario de la credencial borra `session_id`, la `session_key` y el binding del almacén de sesiones.
- **Result:** la sesión finaliza; un intento posterior con esas credenciales recibe E3. La credencial solo reside en memoria del servidor o en el almacén cifrado local (ARCH §5, owner: Servidor PC).

## 9. Errors

| Code | Condition | Observable behavior |
|---|---|---|
| E1 | conexión TCP rechazada o sin respuesta en 5 s (paso 4) | la app muestra "Servidor no disponible" y vuelve a S1 (REQ-001 AC-3) |
| E2 | `pair_request` con token emitido hace más de 300 s según el servidor | `pair_error` E2; la app muestra "QR expirado, generar uno nuevo" y vuelve a S1 (REQ-002 AC-3) |
| E3 | token ya consumido (otro dispositivo completó el Handshake), o `proof` HMAC inválido, o `session_id` de §8.2 no reconocida, o mensajes fuera de secuencia en el flujo | `pair_error` E3; la app muestra "Vinculación rechazada" y cierra la conexión |
| E4 | mensaje NDJSON no parseable como JSON, `type` desconocido, campo faltante o valor fuera de constraints (§4.2) | `pair_error` E4 (servidor) o rechazo silencioso del mensaje (app) |
| E5 | payload del QR no conforme a §4.1 (esquema equivocado, token con longitud de bytes distinta de 32, clave duplicada o extra) | la app muestra "QR no válido" sin abrir conexión (paso 3) |

Sin respuesta a `pair_challenge` en 10 s: el servidor cierra la conexión sin `pair_ack`; el token no queda consumido (§14).

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

Scenario: Reanudación de sesión sin re-escaneo
  Given una Sesión autenticada previa con session_id vigente y no revocada
  When la conexión cae y la app reconecta presentando pair_proof con session_id conocido
  Then el servidor verifica con la misma session_key y responde pair_ack (REQ-002 §2)

Scenario: Servidor no disponible
  Given el Servidor PC inactivo
  When la app intenta conectar al endpoint (REQ-001 AC-3)
  Then la app muestra "Servidor no disponible" E1 sin crear sesión

Scenario: Regeneración automática del QR
  Given un QR mostrado cuyo token alcanza los 300 s sin consumirse
  When el token expira
  Then el Servidor PC muestra automáticamente un QR nuevo con un token nuevo, sin intervención del Jugador
```

## 14. Invariants

- Un token expira (R3) o se consume (R2), nunca ambos; ningún consumo parcial.
- El QR mostrado en pantalla del Servidor PC es siempre vigente: al expirar se regenera automáticamente (R7).
- En S2 el token sigue de un solo uso: un intento abortado no lo consume; solo el paso 10 lo hace.
- Dentro de S3, cada `session_id` identifica exactamente una ligadura activa con su propia `session_key`; un reinicio del servidor elimina todas las ligaduras (la reanudación §8.2 ya no es posible).
- `session_key` nunca sale de la memoria del proceso y no se firma en ningún mensaje (solo derivados como `proof`).

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
| §8.2 reanudación | TEST-007 |
| §8.3 revocación | TEST-008 |
| R7 regeneración automática del QR al expirar | TEST-009 |

## 17. Open questions

- ¿Regeneración automática del QR al expirar, o solo a petición del Jugador? — Eng lead — 2026-10-07 — resuelta: regeneración automática (decisión del dueño, 2026-10-07; R7)
- ¿Las primitivas HKDF-SHA256 + HMAC-SHA256 del §7 son definitivas (requieren revisión criptográfica del Eng lead antes de `Approved`)? — Eng lead — 2026-10-07 — abierta

## Definition of Done

- [ ] Deterministic: two readers cannot reasonably interpret any statement differently.
- [ ] All flows, errors and edge cases enumerated — no "etc.", "and so on", "as appropriate".
- [ ] Every error and edge case has exact expected behavior.
- [ ] Inputs/outputs fully enumerated with types and constraints.
- [ ] No implementation detail beyond the boundary contract.
- [ ] Every aspect has a TEST row; rows mirrored in traceability.md.
- [ ] No contradictions with the implementing REQ.
- [ ] No placeholders; Status updated.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Twinpad team | Initial draft: QR de vinculación + Handshake autenticado; validez QR 5 min (decisión del dueño) |
| 0.2.0 | 2026-10-07 | Twinpad team | OQ-1 resuelta: regeneración automática del QR al expirar (R7, TEST-009) |
