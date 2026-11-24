# Change Request (CR) — CR-111 — Twinpad

| | |
|---|---|
| ID | CR-111 |
| Title | Los inputs del gamepad salen por BT por defecto y LAN como respaldo; gyro y touch siempre por LAN |
| Type | Behavior change |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-24 |
| Decided | 2026-11-24 |
| Decided by | Owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-004 (§8.3), CR-098 (queda revertido) |
| Sections | SPEC-004 §8.3 |
| Current version → new version | SPEC-004 1.11.0 → 1.12.0 (pendiente de aplicar) |

## 2. Requested change

**Current (CR-098):**

```text
Ruta del gamepad: LAN si la sesión está Linked; si no, canal BT HID si está
operativo; sin ninguna, H0.
```

**Proposed:**

```text
Ruta de inputs (botones, sticks, gatillos, dpad):
- BT HID si el canal BT está operativo (por defecto).
- LAN si el canal BT no está operativo (respaldo).
- Sin ninguna, H0.
Gyro y touch: siempre por LAN (no viajan por BT).
Vibración: por BT.
Audio, video y micrófono: siempre por LAN.
```

## 3. Motivation

El owner revierte la prioridad de CR-098. El descriptor BT HID ya no
incluye gyro ni touch (solo report 1 y salida de vibración report 3), por lo
que esos datos deben seguir por LAN.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-XXX | no | — | none |
| SPEC-004 | yes | §8.3 invierte la prioridad y fija gyro/touch por LAN | update (v1.12.0) |
| TEST-0127 | yes | `HandoverPolicyTest` cambia de orden | update |
| TEST-0126 | yes | descriptor sin report 2 | update |
| Code | yes | `HandoverPolicy`, `PairingViewModel.onGamepadInput` | PR scope |

## 5. Options considered

1. Invertir la prioridad (BT primero, LAN respaldo) — lo pide el owner.
2. Mantener CR-098 (LAN primero) — descartado por el owner.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** Decisión del owner en el chat del 2026-11-24.
- **Rollout:** same release

## 7. Execution checklist

- [ ] Target document(s) updated (SPEC-004 v1.12.0)
- [ ] Dependent documents updated (from impact table)
- [ ] Tests added/updated (TEST-0126, TEST-0127)
- [ ] `testing/traceability.md` rows updated
- [ ] Code change merged (if any)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-24 | Buffy | Opened and approved by owner |
