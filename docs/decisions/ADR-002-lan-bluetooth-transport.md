# ADR-002 — Usar WiFi LAN y Bluetooth como únicos transportes de la v1

| | |
|---|---|
| ID | ADR-002 |
| Title | Conectividad v1: WiFi LAN + Bluetooth |
| Version | 1.0.0 |
| Status | `Accepted` |
| Date | 2026-10-07 |
| Deciders | Twinpad team (entrevista de definición) |
| Affects | ARCH §1, §4, §6 |

## 1. Context

La entrevista evaluó cuatro medios de conexión: WiFi LAN, USB, WebSocket por internet y Bluetooth. El usuario priorizó "Bluetooth + WiFi LAN" (respuesta a la pregunta 3) y la v1 fija must-have "servidor PC + pairing + HID + audio/video". La decisión se codifica en CTX-C2 y queda excluido el streaming remoto (CTX §4 out of scope).

## 2. Problem

¿Qué transportes soporta Twinpad v1 entre el Servidor PC y el Android device?

## 3. Options considered

### Option A — WiFi LAN + Bluetooth

- **Pros:** sin cables; Bluetooth porta el HID nativo (ADR-003); WiFi LAN da el ancho de banda para video (NFR-001).
- **Cons:** variabilidad de routers y de pilas BT por fabricante; CTX-A1/A2 se vuelven críticas.

### Option B — USB (cable)

- **Pros:** máxima estabilidad y latencia mínima; ideal para gaming (reconocido en la entrevista).
- **Cons:** contradice el caso de uso "celular en las manos como segunda pantalla + gamepad"; requiere drivers ADB/usb; se pospone.

### Option C — WebSocket sobre internet (remote play)

- **Pros:** jugar fuera de casa.
- **Cons:** latencia y cifrado fuera de control; relay/infraestructura centralizada que la v1 no quiere (CTX §4 lo excluye explícitamente).

## 4. Decision

Usaremos WiFi LAN para los streams de video/audio y Bluetooth para el canal HID, y no soportaremos internet ni USB en la v1, porque cubren el flujo principal con la menor superficie técnica nueva.

## 5. Justification

- CTX-C2 ya lo fija como restricción de alcance.
- NFR-001 (p95 ≤ 60 ms en 802.11ac) es alcanzable en LAN; por internet no es garantizable.
- ADR-003 requiere Bluetooth para el HID device role; los streams de audio/video viajan por LAN para no saturar el enlace BT.
- USB queda como evolución futura natural (no excluido conceptualmente, solo fuera de v1).

## 6. Consequences

**Positive:**
- Sin infraestructura central; todo corre en la red del usuario.
- Latencias acotadas por el medio local.

**Negative / trade-offs accepted:**
- No funciona fuera de la LAN (limitación conocida de v1).
- Dependemos de la calidad del router/BT del usuario (CTX-A1).

**Neutral:**
- La app debe detectar y guiar al usuario cuando PC y celular no comparten red.

## 7. Compliance and validation

- Enforcement: revisión de arquitectura; tests de integración solo consideran medios LAN/BT.
- Revisit trigger: demanda real de remote play o de modo USB (nueva ADR que supersedan esta).

## 8. Related

| Relation | ID |
|---|---|
| Supersedes | - |
| Superseded by | - |
| Relates to | REQ-002, REQ-005, NFR-001, CTX-C2 |
| Contextualized in | ARCH §1, §4 |
| Changed by CR | - |

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 1.0.0 | 2026-10-07 | Twinpad team | Accepted (decisión de la entrevista) |
