# ADR-004 — Vincular con QR mostrado por el servidor y autenticación mutua

| | |
|---|---|
| ID | ADR-004 |
| Title | Pairing por QR con autenticación mutua |
| Version | 1.0.0 |
| Status | `Accepted` |
| Date | 2026-10-07 |
| Deciders | Twinpad team (entrevista de definición) |
| Affects | ARCH §4, §7; REQ-002; NFR-004 |

## 1. Context

El flujo acordado en la entrevista (pregunta 4) es: la PC (con juego compatible corriendo) muestra un QR; el celular lo escanea, se vincula, detecta el juego compatible y comienza el flujo de datos. NFR-004 exige autenticación mutua con challenge criptográfico derivado del QR para que un atacante en la LAN no pueda conectarse solo con la dirección del servidor.

## 2. Problem

¿Cómo vincula el usuario su celular a la PC de forma simple (sin IPs/puertos manuales) y, a la vez, segura frente a terceros en la misma red?

## 3. Options considered

### Option A — QR con datos de conexión + token, challenge mutuo tras el escaneo

- **Pros:** un solo gesto del usuario; el token nunca viaja por la red en claro (solo dentro del QR); autenticación mutua barata (NFR-004); revocable (expiración del QR).
- **Cons:** requiere cámara operativa; QR en pantalla puede ser fotografiado (ventana de validez corta mitigante).

### Option B — PIN de 6 dígitos mostrado en la PC

- **Pros:** no requiere cámara.
- **Cons:** tipeo manual (más fricción, error humano); espacio de claves menor que un token QR.

### Option C — Autenticación por cuenta (servicio en la nube)

- **Pros:** revocación centralizada, multi-dispositivo.
- **Cons:** contradice el alcance v1 sin backend central (CTX-C2, out of scope "streaming por internet"); agrega dependencia externa al flujo local.

## 4. Decision

Usaremos un QR de vinculación generado por el Servidor PC que contiene datos de conexión y un token de sesión de un solo uso, seguido de un challenge criptográfico mutuo antes de aceptar la sesión (NFR-004).

## 5. Justification

- Es el flujo literalmente definido en la entrevista (pregunta 4) y REQ-002.
- NFR-004 exige rechazar conexiones sin el token; el QR es la única vía de obtención.
- Sin backend central, las alternativas de cuenta (Option C) no aplican a v1.
- La expiración del QR (SPEC del pairing) acota la ventana de robo del código.

## 6. Consequences

**Positive:**
- Pairing en un paso, sin configuración de red.
- Token de sesión de un solo uso: reuso o replay quedan fuera por diseño.

**Negative / trade-offs accepted:**
- Dispositivos sin cámara no pueden vincularse por esta vía.
- Un QR visible y no expirado es vulnerable a terceros presentes físicamente (ventana corta aceptada).

**Neutral:**
- Re-vinculación tras expiración genera un QR nuevo (sin cambio de UX relevante).

## 7. Compliance and validation

- Enforcement: tests de seguridad del SPEC de pairing (conexión sin token → rechazo; NFR-004).
- Revisit trigger: aparición de un requisito de multi-usuario o de vinculación sin cámara.

## 8. Related

| Relation | ID |
|---|---|
| Supersedes | - |
| Superseded by | - |
| Relates to | REQ-001, REQ-002, NFR-004 |
| Contextualized in | ARCH §4, §7 |
| Changed by CR | - |

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 1.0.0 | 2026-10-07 | Twinpad team | Accepted (decisión de la entrevista) |
