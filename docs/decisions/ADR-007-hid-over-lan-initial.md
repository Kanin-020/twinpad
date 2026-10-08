# ADR-007 — Transporte inicial de inputs: sesión LAN de SPEC-001 (BT HID nativo como meta)

| | |
|---|---|
| ID | ADR-007 |
| Title | Transporte inicial de inputs por sesión LAN (BT HID como meta) |
| Version | 1.0.0 |
| Status | `Accepted` |
| Date | 2026-10-07 |
| Deciders | Owner del proyecto (usuario) |
| Affects | SPEC-004 §17, DES-004, ARCH §4/§6 |

## 1. Context

SPEC-004 (Signature HID) define el contrato de inputs pero deja abierta la pregunta
de transporte (§17): ¿`input_report` viaja por el canal HID Bluetooth de ADR-003 o
por la sesión LAN de SPEC-001? ADR-003 (Accepted) fija la meta final — el celular
debe aparecer en Windows como gamepad HID nativo sin software adicional — pero el
HID device role de Android (CTX-A2) tiene cobertura variable por fabricante y su
implementación (descriptor HID + perfil BT) es una pieza grande e independiente.

En paralelo, la sesión persistente de SPEC-001 ya existe, está probada de punta a
punta (pairing + video funcionando en banco físico) y ofrece entrega fiable y
ordenada por TCP.

## 2. Problem

¿Por qué canal envía la app los `input_report` de la primera implementación?

## 3. Options considered

### Option A — Sesión LAN de SPEC-001 (mismo canal NDJSON que SPEC-003)

- **Pros:** reutiliza la conexión persistente ya operativa; entrega fiable y
  ordenada; permitirá probar el flujo táctil → PC de punta a punta de inmediato;
  el codec `input_report` vive en `core` y es compartido.
- **Cons:** no hace visible el gamepad en el SO de la PC por sí solo (REQ-003 AC-1
  queda pendiente hasta la vía BT); requiere un receptor en el server que consuma
  los reportes (en v1: log/validación; la inyección real al SO llega con la vía HID).

### Option B — Bluetooth HID nativo (ADR-003) desde el día 1

- **Pros:** cumple AC-1 (gamepad visible en el SO) de una vez.
- **Cons:** depende del HID device role (CTX-A2) con cobertura incierta; descriptor
  HID + perfil BT es una pieza grande que bloquearía toda validación del flujo de
  inputs; no reutiliza nada de lo ya probado.

### Option C — Ambos canales en paralelo

- **Cons:** doble mantenimiento en v1; contradice la construcción incremental del
  workflow del proyecto.

## 4. Decision

La primera implementación de SPEC-004 envía `input_report` por la **sesión LAN
persistente de SPEC-001** (mismo socket NDJSON que el stream de video de SPEC-003),
con prioridad sobre video/audio según ARCH §8. La vía Bluetooth HID nativa (ADR-003)
permanece como **meta final** de REQ-003 y entrará en una iteración posterior con su
propia ADR de detalle (descriptor, perfil BT).

## 5. Justification

- Permite validar AC-2 (el input llega a la PC) y la semántica de `seq` de SPEC-004
  en banco físico de inmediato, sin bloquear por CTX-A2.
- El codec de `input_report` es idéntico en ambos transportes: migrar a BT HID más
  adelante no desecha trabajo.
- Decisión tomada por el owner del proyecto (aprobación de SPEC-004, 2026-10-07).

## 6. Consequences

**Positive:**
- Flujo táctil → PC probabile en la próxima prueba de banco físico.
- Codec compartido Rust/Kotlin reutilizable por la vía BT futura.

**Negative / trade-offs accepted:**
- REQ-003 AC-1 (gamepad listado por el SO) sigue pendiente hasta la vía BT HID.
- El server v1 recibe los reportes pero no los inyecta al SO (log/validación;
  la inyección requiere la vía HID o un driver virtual — fuera de alcance aquí).

**Neutral:**
- R3 de SPEC-004 (prioridad inputs > video/audio) se mantiene: los inputs van por
  el mismo socket pero se procesan primero en el loop de la app.

## 7. Compliance and validation

- Enforcement: TEST-018 verifica el flujo táctil → PC por el canal LAN.
- Revisit trigger: cuando se implemente la vía BT HID nativa (nueva ADR) o si la
  latencia p95 de NFR-002 no se cumple por el canal LAN.

## 8. Related

| Relation | ID |
|---|---|
| Supersedes | - |
| Superseded by | - |
| Relates to | SPEC-004, ADR-003, REQ-003 |
| Contextualized in | ARCH §4, §6 |
| Changed by CR | - |

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 1.0.0 | 2026-10-07 | Owner (usuario) | Accepted: transporte inicial por sesión LAN de SPEC-001 |
