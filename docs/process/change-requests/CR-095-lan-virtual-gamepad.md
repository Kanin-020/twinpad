# Change Request (CR) — CR-095 — Twinpad

| | |
|---|---|
| ID | CR-095 |
| Title | Los inputs del mando viajan por Bluetooth HID; la LAN queda para el resto |
| Type | `New behavior` |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-11 |
| Decided | 2026-11-11 |
| Decided by | Owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | REQ-003 (§5, AC-1), SPEC-004 (§4 Canal, §8.3 handover) |
| Sections | REQ-003 AC-1; SPEC-004 §4 y §8.3 |
| Current version → new version | pendiente de decisión (se fija al aplicar) |

## 2. Requested change

**Current:**

```text
Durante una sesión LAN autenticada, el Servidor PC reenvía el `input_report`
al adaptador Cemu y al SDK; el gamepad del sistema operativo sólo existe por
la ruta Bluetooth HID (CR-059, HandoverPolicy).
```

**Proposed:**

```text
Los inputs del gamepad (Signature HID, SPEC-004 §2) viajan por el canal
Bluetooth HID cuando está operativo, incluso con la sesión LAN activa. La LAN
(sesión autenticada de SPEC-001) transporta el resto: vídeo, audio, sesión y
metadatos. El Servidor PC no crea ningún gamepad virtual del sistema operativo.
```

## 3. Motivation

El usuario quiere que Steam reconozca Twinpad como mando cuando hay conexión.
Un mando virtual del sistema en el Servidor PC exigiría permisos de uinput
(root o grupo `input`) o un driver ViGEm en Windows. El canal Bluetooth HID ya
expone el gamepad a Windows y Steam como mando estándar, sin permisos extra.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003 | yes | AC-1: el mando del SO depende del canal BT, no de la LAN | update |
| SPEC-004 | yes | §4: los inputs del gamepad usan BT; §8.3: HandoverPolicy prioriza BT para el gamepad con sesión LAN | update |
| ARCH | no | Sin módulo nuevo en el Servidor | none |
| DES-002/DES-004 | probable | Ruta de inputs del gamepad | revisar |
| TEST | yes | Pruebas de HandoverPolicy con sesión LAN y BT activo | add |
| Code | yes | Android: HandoverPolicy y envío del `input_report` por BT durante Linked | PR |
| Data / migrations | no | - | none |
| Integrations / contracts | yes | Requiere emparejar el teléfono con el PC por Bluetooth | documentar |

## 5. Options considered

1. Mando virtual en el Servidor PC (uinput/ViGEm) — requiere permisos de sistema o driver externo. Descartada por el owner.
2. Inputs por Bluetooth HID, LAN para el resto — sin permisos extra; depende del emparejamiento BT. **Elegida.**
3. Do nothing — Steam sólo ve el mando por Bluetooth sin Wi-Fi.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** El owner pide que los inputs viajen por Bluetooth HID y la LAN se use para el resto. Así Steam ve Twinpad como mando sin permisos de uinput ni driver ViGEm.
- **Rollout:** misma release; sin migración de datos. Requiere emparejamiento BT previo.

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
| 0.2.0 | 2026-11-11 | Buffy (agente) | Reescrito: inputs por BT HID en lugar de mando virtual del servidor |
