# Change Request (CR) — CR-096 — Twinpad

| | |
|---|---|
| ID | CR-096 |
| Title | El gamepad sale sólo por Bluetooth HID; la LAN no lo transporta |
| Type | `New behavior` |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-11 |
| Decided | 2026-11-11 |
| Decided by | Owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | REQ-003 (§5, AC-1), SPEC-004 (§4, §8.3) |
| Sections | REQ-003 AC-1; SPEC-004 §4 y §8.3 |
| Current version → new version | pendiente de decisión (se fija al aplicar) |

## 2. Requested change

**Current (CR-095 v0.2.0):**

```text
Los inputs del gamepad viajan por Bluetooth HID cuando está operativo, con la
sesión LAN activa; si el canal BT cae, la LAN toma el gamepad (HandoverPolicy).
```

**Proposed:**

```text
El gamepad (Signature HID, SPEC-004 §2) viaja únicamente por el canal
Bluetooth HID. La sesión LAN no transporta reportes del gamepad, ni como
respaldo. Si el canal BT no está operativo, el gamepad está en H0 y no envía.
La LAN transporta el resto (vídeo, audio, sesión y metadatos).
```

## 3. Motivation

El owner indica que el mando debe enviarse por Bluetooth HID y no por la LAN.
El canal BT ya está emparejado y con permiso concedido, pero la app solo lo
arranca en failover, así que con la sesión LAN activa el gamepad no sale.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003 | yes | AC-1: el gamepad depende sólo de BT | update |
| SPEC-004 | yes | §4 y §8.3: la LAN no transporta el gamepad; H0 si no hay BT | update |
| ARCH | no | - | none |
| TEST | yes | HandoverPolicy: BT o NONE, nunca LAN para el gamepad | update |
| Code | yes | Android: `HandoverPolicy`, `GamepadBtSwitcher` (arranque del canal con LAN Linked), `PairingViewModel` (envío) | PR |
| Data / migrations | no | - | none |

## 5. Options considered

1. BT con respaldo LAN (CR-095 v0.2.0) — descartada por el owner.
2. BT HID únicamente, sin respaldo LAN — **elegida**.
3. Do nothing — el gamepad no sale con la sesión LAN activa.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** El owner pide que el gamepad se envíe sólo por Bluetooth HID, sin fallback a la LAN.
- **Rollout:** misma release; requiere emparejamiento BT y permiso BLUETOOTH_CONNECT.

## 7. Execution checklist

- [ ] Target document(s) updated (version bumped, changelog entry references this CR ID)
- [ ] Dependent documents updated (from impact table)
- [ ] Tests added/updated (TEST IDs)
- [ ] `testing/traceability.md` rows updated (including CR history table)
- [ ] Indexes (`_index.md`) updated
- [ ] Code change merged (if any)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-11 | Buffy (agente) | Opened |
