# Change Request (CR) — CR-098 — Twinpad

| | |
|---|---|
| ID | CR-098 |
| Title | Revierte CR-096: el gamepad sale por LAN con respaldo Bluetooth HID |
| Type | `Behavior change` |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-12 |
| Decided | 2026-11-12 |
| Decided by | Owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | REQ-003 (§5, AC-1), SPEC-004 (§4, §8.3) |
| Sections | REQ-003 AC-1; SPEC-004 §4 y §8.3 |
| Current version → new version | pendiente de decisión (se fija al aplicar) |

## 2. Requested change

**Current (CR-096, Approved):**

```text
El gamepad (Signature HID, SPEC-004 §2) viaja únicamente por el canal
Bluetooth HID. La sesión LAN no transporta reportes del gamepad, ni como
respaldo. Si el canal BT no está operativo, el gamepad está en H0 y no envía.
```

**Proposed:**

```text
El gamepad viaja por la sesión LAN cuando está Linked (input_report por la
conexión del servidor). El canal Bluetooth HID es respaldo: si la sesión LAN
no está disponible y el canal BT está operativo, el gamepad sale por BT.
Si ninguna ruta está disponible, el gamepad está en H0 y no envía.
```

## 3. Motivation

El owner revierte CR-096. En la práctica el registro HID del sistema Android
queda retenido por la pila Bluetooth (`registerApp(): failed because another
app is registered`, tras reinicio del teléfono), así que el gamepad no puede
salir por BT y Cemu no recibe inputs.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003 | yes | AC-1 vuelve a LAN primaria con respaldo BT | update |
| SPEC-004 | yes | §4 y §8.3: LAN transporta el gamepad; BT como respaldo | update |
| ARCH | no | - | none |
| TEST | yes | HandoverPolicy: LAN, BT o NONE según prioridad | update |
| Code | yes | Android: `HandoverPolicy`, `GamepadBtSwitcher`, `PairingViewModel` (envío) | PR |
| Data / migrations | no | - | none |

## 5. Options considered

1. LAN primaria con respaldo BT — **elegida** (decisión del owner).
2. BT HID únicamente (CR-096) — descartada: el HID queda bloqueado en el dispositivo.
3. Do nothing — sin inputs en Cemu mientras el HID esté retenido.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** El owner pide revertir CR-096 y dejar la LAN como ruta principal del gamepad, con Bluetooth HID como respaldo.
- **Rollout:** misma release; el respaldo BT requiere emparejamiento y permiso BLUETOOTH_CONNECT.

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
| 0.1.0 | 2026-11-12 | Buffy (agente) | Opened |
