# SPEC-002 — App Android: escaneo del QR y pantalla de Pairing

> **What this is:** the precise, testable behavior the system must exhibit for one capability. It is the contract that both tests and implementation must satisfy, and the reference an AI agent implements against.
> **What it is NOT:** a description of code structure (see `design/`) or of why the capability exists (see the REQ it implements).

| | |
|---|---|
| ID | SPEC-002 |
| Title | App Android: escaneo del QR y pantalla de Pairing |
| Version | 0.1.0 |
| Status | `Draft` |
| Owner | Eng lead |
| Implements | REQ-002 |
| Realized by | `-` (hasta asignar DES) |
| Verified by | TEST-010 … TEST-014 (registrados en `testing/traceability.md`, aún sin código) |
| Last updated | 2026-10-07 |

## 1. Purpose

Define el comportamiento observable de la app Twinpad en el Android device para la vinculación: pantalla de escaneo del QR de vinculación, ejecución del Handshake del lado cliente y mensajes al usuario. Implementa el lado dispositivo de REQ-002 reutilizando sin modificar el contrato de SPEC-001 (§4.1, §4.2, §7, §9). No define wire format nuevo ni interfaz con el Motor HID.

## 2. Definitions

- **Pairing**, **QR de vinculación**, **Handshake** (del lado app), **Sesión autenticada**: ver CTX §8 y SPEC-001 §2. El contrato wire es exclusivamente el de SPEC-001 §12 — este SPEC no lo duplica ni modifica.
- **Pantalla de escaneo**: vista de la app que activa la cámara y decodifica QRs.
- **Pantalla de Pairing**: vista que muestra el estado de la vinculación (mensajes y transiciones de §6).
- **Intento de vinculación**: desde el escaneo válido de un QR hasta la salida a S1 (éxito o error).

## 3. Preconditions

- App instalada en Android 12+ (CTX-C1) con cámara operativa.
- PC y celular en la misma LAN (CTX-C2); Servidor PC activo para el flujo exitoso (REQ-001).
- Ningún permiso concedido previamente: la cámara se solicita según §11-R2.

## 4. Inputs

| Input | Tipo | Constraints | Origen |
|---|---|---|---|
| Contenido del QR escaneado | string | debe cumplir SPEC-001 §4.1 (`twinpad://v1#…`) | cámara |
| Acción "escanear QR" | evento de usuario | — | Pantalla de Pairing |
| Acción "cancelar" | evento de usuario | solo en Escaneando o Handshake | Pantalla de Pairing |
| Acción "reintentar" | evento de usuario | solo tras error E1–E5 | Pantalla de Pairing |
| Permiso de cámara | evento del sistema | concedido / denegado | Android SO |
| App pasa a segundo plano | evento del sistema | durante Escaneando o Handshake | Android SO |
| Mensajes del servidor | SPEC-001 §4.2 (`pair_challenge`, `pair_ack`, `pair_error`) | sin campos nuevos | TCP |

## 5. Outputs

| Result | Value / shape | Condition |
|---|---|---|
| Estado de pantalla | uno de §6 (Inicio, Escaneando, Handshake, Vinculado, Error) | siempre visible en la Pantalla de Pairing |
| Solicitud de permiso de cámara | diálogo del SO | al entrar a Escaneando sin permiso (§11-R2) |
| Conexión TCP + Handshake cliente | mensajes exactos de SPEC-001 §4.2 lado app | tras escaneo válido (§7) |
| Mensaje de error | tabla exacta de §9 | según código del error |
| Estado Vinculado | sesión activa en memoria con `session_id` y clave de sesión (SPEC-001 §5, §8.2) | tras `pair_ack` |

## 6. States

Estados de la Pantalla de Pairing (mapean a SPEC-001 §6): `U1 Inicio`, `U2 Escaneando`, `U3 Handshake`, `U4 Vinculado`, `U5 Error`.

| From | Event | To | Side effects |
|---|---|---|---|
| U1 | Acción "escanear QR" | U2 | solicita permiso de cámara si no está concedido (§11-R2) |
| U1 | Error previo + "reintentar" | U2 (permiso ok) o U1 (denegado) | limpia el mensaje de error |
| U2 | QR válido decodificado (§4.1) | U3 | abre TCP a `server_addr:server_port` (SPEC-001 §7 paso 4) |
| U2 | QR inválido (E5) | U5 | mensaje "QR no válido" |
| U2 | "cancelar" | U1 | libera la cámara |
| U3 | `pair_ack` (SPEC-001 §7 paso 10–11) | U4 | guarda `session_id` y clave de sesión en memoria (§8.2 de SPEC-001) |
| U3 | `pair_error` E1–E4 o fallo de conexión | U5 | mensaje según §9 |
| U3 | "cancelar" | U1 | cierra la conexión |
| U4 | corte de conexión (SPEC-001 §8.2 aplica) | U1 | mensaje "Sesión terminada" |
| U5 | "reintentar" | U1 | limpia el error; el usuario re-escanea |
| U2/U3 | app a segundo plano | U1 | cancela el intento y libera recursos (§10) |

Invariante: U4 es el único estado con sesión activa; todo error devuelve a U5 y de ahí a U1/U2 por acción del usuario.

## 7. Main flow

1. El usuario pulsa "escanear QR": la app solicita permiso de cámara si no está concedido y muestra la Pantalla de escaneo (U2).
2. La app decodifica el QR; si el payload no cumple SPEC-001 §4.1 → E5 (§9) y termina el intento.
3. La app abre conexión TCP a `server_addr:server_port`; sin respuesta en 5 s → E1 (§9).
4. La app envía `pair_request` (SPEC-001 §7 paso 5) y espera `pair_challenge`.
5. La app deriva la clave de sesión y computa el proof exactamente según SPEC-001 §7 paso 8 (HKDF-SHA256 + HMAC-SHA256, transcript idéntico) y envía `pair_proof`.
6. La app recibe `pair_ack` con `session_id` → U4 "Vinculado", sesión activa en memoria (SPEC-001 §5).
7. La app muestra "Vinculado".

## 8. Alternative flows

### 8.1 Permiso de cámara denegado

- **Trigger:** el SO reporta permiso denegado al entrar a U2.
- **Flow:** la app muestra el mensaje de §9 (PERMISO) y permanece en U1; "reintentar" vuelve a solicitar el permiso.
- **Result:** no se accede a la cámara ni se intenta conexión.

### 8.2 Cancelación

- **Trigger:** el usuario pulsa "cancelar" en U2 o U3.
- **Flow:** la app libera cámara/conexión y vuelve a U1 sin mensaje de error.
- **Result:** el intento queda abortado; el servidor verá la conexión caer (su comportamiento es el de SPEC-001 §9/§10, no de este SPEC).

### 8.3 App a segundo plano

- **Trigger:** el SO manda la app a segundo plano durante U2 o U3.
- **Flow:** la app cancela el intento (cámara y socket liberados) y vuelve a U1; al volver a primer plano muestra U1 sin error.
- **Result:** intento abortado limpio; re-escanear inicia uno nuevo.

## 9. Errors

| Code | Condition | Mensaje exacto en pantalla |
|---|---|---|
| E1 | conexión TCP rechazada o sin respuesta en 5 s (SPEC-001 §9, lado app) | "Servidor no disponible" |
| E2 | `pair_error` con código E2 (SPEC-001 §9) | "QR expirado, generar uno nuevo" (REQ-002 AC-3) |
| E3 | `pair_error` con código E3 | "Vinculación rechazada" |
| E4 | mensaje del servidor no parseable o fuera de constraints (SPEC-001 §4.2) | "Respuesta no válida" |
| E5 | payload del QR no conforme a SPEC-001 §4.1 | "QR no válido" |
| PERMISO | permiso de cámara denegado (§8.1) | "Se necesita la cámara para escanear el QR" |
| SESIÓN | corte de conexión estando en U4 | "Sesión terminada" |

La app muestra exactamente estas cadenas, no el campo `message` del `pair_error` (ese campo queda para diagnóstico del servidor; SPEC-001 §4.2).

## 10. Edge cases

| Case | Expected behavior |
|---|---|
| Se escanea un QR de otra app (esquema distinto a `twinpad://v1`) | E5: "QR no válido" |
| Se re-escanea el mismo QR tras una vinculación exitosa | el servidor responde E3 (token consumido, R2 de SPEC-001): "Vinculación rechazada" |
| Dos escaneos seguidos con QRs distintos | cada intento es independiente: el segundo parte de U1 tras cerrar el primero |
| Rotación o cambio de configuración durante U2/U3 | el intento continúa sin reiniciarse (el estado del intento sobrevive a la re-creación de la vista) |
| `pair_error` con código desconocido (≠ E1–E5) | la app lo trata como E4: "Respuesta no válida" (SPEC-001 §4.2) |
| Servidor cierra la conexión a mitad del Handshake | E1: "Servidor no disponible" |
| QR válido pero del `token` de longitud incorrecta | E5 (falla la validación de §4.1 antes de abrir conexión) |

## 11. Business rules

- **R1:** La app habla exactamente el contrato wire de SPEC-001 §4.1/§4.2/§12; no define campos, códigos ni mensajes nuevos en la red.
- **R2:** El permiso de cámara se solicita únicamente al entrar a U2 sin permiso previo (ENG §8: permisos mínimos; sin almacenamiento amplio ni otros permisos).
- **R3:** Token, clave de sesión y proof jamás se muestran en UI ni se registran en logs (SPEC-001 R5/R6, ENG §7).
- **R4:** Las cadenas de §9 viven en recursos de Android (i18n-ready, ENG §13 P13); no hay strings de UI hardcodeadas.
- **R5:** Un intento de vinculación = un escaneo; no hay reintentos automáticos silenciosos. Reintentar es siempre acción explícita del usuario.
- **R6:** La clave de sesión y `session_id` residen solo en memoria del proceso (SPEC-001 §8.2/§8.3); morir el proceso, muere la sesión.

## 12. Contracts

| Contract | Canonical source | Notes |
|---|---|---|
| Payload del QR | SPEC-001 §4.1 | referencia; ninguna copia local |
| Mensajes del Handshake | SPEC-001 §4.2 | referencia; el cliente es interoperable con cualquier implementación conforme |
| Tabla de mensajes de UI | este SPEC §9 | canónica para la app; cambio de texto → CR |

## 13. Examples (Given / When / Then)

```gherkin
Scenario: Vinculación exitosa desde la app
  Given permiso de cámara concedido y Servidor PC activo con QR vigente
  When el usuario escanea el QR y el Handshake de SPEC-001 §7 se completa
  Then la app muestra "Vinculado" y queda en U4 con la sesión activa en memoria

Scenario: QR de otra aplicación
  Given la cámara apuntando a un QR cuyo contenido no empieza con twinpad://v1
  When la app decodifica el QR
  Then muestra "QR no válido" (E5) y no abre ninguna conexión

Scenario: Servidor no disponible
  Given un QR válido cuyo server_addr:server_port no responde en 5 s
  When la app intenta la conexión TCP
  Then muestra "Servidor no disponible" (E1) y vuelve a U5 — REQ-001 AC-3

Scenario: QR expirado reportado por el servidor
  Given un QR escaneado más de 300 s después de emitido según el servidor
  When el servidor responde pair_error E2
  Then la app muestra "QR expirado, generar uno nuevo" (E2) — REQ-002 AC-3

Scenario: Permiso de cámara denegado
  Given el permiso de cámara no concedido
  When el usuario pulsa "escanear QR" y deniega el permiso
  Then la app muestra "Se necesita la cámara para escanear el QR" y permanece en U1
```

## 14. Invariants

- U4 solo se alcanza con un `pair_ack` recibido y verificado (SPEC-001 §7); jamás por timeout ni por escaneo solo.
- Ninguna cadena de §9 contiene token, clave de sesión ni proof (R3).
- Tras cualquier error, la app queda en U5 con el mensaje exacto de la tabla §9 y sin recursos de cámara/socket abiertos.
- El campo `message` del `pair_error` nunca se muestra en UI (§9).

## 15. What "complies" means

An implementation complies with this SPEC if and only if: all preconditions are honored, all flows produce the defined outputs, every error row is reproduced by its trigger, every edge case behaves as defined, all invariants hold, and all verification tests (§16) pass. Anything not defined here is NOT required — do not invent requirements.

## 16. Verification

| Aspect | Verified by |
|---|---|
| Main flow (escaneo → Vinculado) | TEST-010 |
| E1 Servidor no disponible | TEST-011 |
| E5 QR inválido + PERMISO denegado | TEST-012 |
| E2/E3 mensajes desde `pair_error` | TEST-013 |
| Edge cases: cancelar, segundo plano, código desconocido, rotación | TEST-014 |

## 17. Open questions

- Ninguna abierta. Fuera de alcance declarado: reutilización de sesión en segundo plano (SPEC-001 §8.2 la define en el protocolo; su UX específica llegará con su propio SPEC si se requiere), Motor HID (REQ-003) y Segunda pantalla (REQ-004).

## Definition of Done

- [ ] Deterministic: two readers cannot reasonably interpret any statement differently.
- [ ] All flows, errors and edge cases enumerated — no "etc.", "and so on", "as appropriate".
- [ ] Every error and edge case has exact expected behavior.
- [ ] Inputs/outputs fully enumerated with types and constraints.
- [ ] No implementation detail (frameworks, file names, SQL, endpoints' internals) beyond the boundary contract.
- [ ] Every aspect has a TEST row; rows mirrored in traceability.md.
- [ ] No contradictions with the implementing REQ.
- [ ] No placeholders; Status updated.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Twinpad team | Initial draft: lado app de REQ-002 (escaneo + Pairing UI) sobre el contrato de SPEC-001 |
