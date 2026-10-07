# ADR-003 — Emular el celular como Gamepad HID Bluetooth nativo (opción A)

| | |
|---|---|
| ID | ADR-003 |
| Title | Gamepad HID Bluetooth nativo |
| Version | 1.0.0 |
| Status | `Accepted` |
| Date | 2026-10-07 |
| Deciders | Twinpad team (entrevista de definición) |
| Affects | ARCH §2, §6; REQ-003 |

## 1. Context

La entrevista preguntó cómo el celular debe "aparecer como gamepad" en la PC: (A) servidor HID por Bluetooth nativo, (B) protocolo propio + mini-app en PC que traduce a inputs de Windows (vJoy/driver virtual), o (C) híbrido. El usuario eligió la Opción A: "Bluetooth HID nativo directo al PC" (respuesta a la pregunta 4), codificada en CTX-C3.

## 2. Problem

¿Cómo recibe Windows los inputs del celular: como dispositivo HID Bluetooth nativo, o traducidos por software adicional en la PC?

## 3. Options considered

### Option A — Servidor HID vía Bluetooth nativo

- **Pros:** cero software extra en la PC; Windows lo ve como un control estándar ("Twinpad Gamepad" en game controllers); funciona con cualquier juego que acepte gamepads incluso sin plugin.
- **Cons:** soporte de HID device role variable según fabricante (CTX-A2); el descriptor (Signature HID, CTX-C5) es fijo por conexión; canal BT con ancho de banda limitado.

### Option B — Protocolo propio + traductor en PC (vJoy/driver virtual)

- **Pros:** descriptor flexible y ampliable; no depende del HID device role del teléfono.
- **Cons:** exige instalar driver/app adicional en la PC (fricción que el proyecto busca eliminar); firma de driver en Windows; contradice CTX-C3.

### Option C — Híbrido (A + B)

- **Pros:** cobertura máxima.
- **Cons:** doble mantenimiento en v1; decisión explícita de la entrevista fue A.

## 4. Decision

Usaremos emulación HID Bluetooth nativa (Opción A): el celular actúa como dispositivo HID periférico y Windows lo reconoce como gamepad estándar, sin software adicional en la PC.

## 5. Justification

- CTX-C3 (decisión del usuario) lo fija explícitamente.
- Elimina la fricción de instalación en PC, pieza central del problema (CTX §1).
- El piso Android 12+ (CTX-C1) da soporte estable del perfil HID (fundamento de NFR de la entrevista).
- Riesgo asumido y monitoreado: variabilidad de soporte por fabricante (CTX-A2), con fallback futuro documentable vía nueva ADR.

## 6. Consequences

**Positive:**
- Compatibilidad inmediata con juegos sin integración (complementa REQ-007).
- Sin drivers ni traductores que firmar/mantener en la PC.

**Negative / trade-offs accepted:**
- Signature HID fija por conexión (2 sticks, 10–12 botones, triggers L/R, gyro — CTX-C5): cambios futuros requieren re-emparejamiento.
- Dispositivos sin HID device role quedan fuera (CTX-A2).

**Neutral:**
- La vibración (rumble) viaja por el canal HID output; su latencia no está en NFR-002.

## 7. Compliance and validation

- Enforcement: revisión del descriptor HID en code review + test de registro del dispositivo (TEST del SPEC HID).
- Revisit trigger: si la cobertura de dispositivos (CTX-A2) resulta insuficiente en pruebas de campo.

## 8. Related

| Relation | ID |
|---|---|
| Supersedes | - |
| Superseded by | - |
| Relates to | REQ-003, CTX-C1, CTX-C3, CTX-C5 |
| Contextualized in | ARCH §2, §6 |
| Changed by CR | - |

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 1.0.0 | 2026-10-07 | Twinpad team | Accepted (decisión de la entrevista) |
