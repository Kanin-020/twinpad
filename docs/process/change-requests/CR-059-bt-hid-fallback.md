# Change Request (CR) — CR-059 — Twinpad

| | |
|---|---|
| ID | CR-059 |
| Title | Canal de inputs redundante por Bluetooth HID (fallback de red) |
| Type | Scope change |
| Status | `Open` |
| Author | Buffy (agente) |
| Created | 2026-11-01 |
| Decided | — |
| Decided by | — |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-004, ADR-007, ADR-003 (nota), nuevo ADR (reservar número) |
| Sections | SPEC-004: §4, §12, Changelog · ADR-007: trigger de revisión · ADR-003: nota de reactivación |
| Current version → new version | SPEC-004 `1.10.0 → 1.11.0` · ADR-007 → |Aceptado → Aceptado con revisión abierta| · nuevo ADR `Proposed` |

## 2. Requested change

**Current:**

```text
SPEC-004 v1.0.0 §4: el canal de inputs es la sesión LAN autenticada de
SPEC-001 sobre NDJSON; ADR-007 decidió BT HID diferido (meta en el roadmap)
y ADR-003 lo suspendió vía CR-024. Hoy el único medio de inputs es la LAN:
un router saturado, un AP que reinicia o NFR-002 fuera de umbral no tienen
ningún fallback y el juego queda sin control sobre Wi-Fi (video puede
tolerar picos, pero los inputs se quedan pegados).
```

**Proposed:**

```text
SPEC-004 §4 — doble transporte de inputs:

- Primario: LAN autenticada (hoy), sin cambios.
- Respaldo: BT HID nativo (teléfono expone descriptores de Signature HID §2
  a Windows por Bluetooth classic; el PC los recibe como gamepad HCI/BLE
  estándar — requiere driver/virtual gamepad en Windows: decisión de ADR).
  Entra en el estado H2 «Failover» de SPEC-004 cuando el heartbeat de la
  sesión (SPEC-001 §4.3, CR-055) declara la sesión caída, o cuando NFR-002
  supera umbral sostenido; regresa al primario cuando la LAN es sana
  (histéresis de 5 s). Handover sin re-pairing: la identidad HID es la
  misma en ambos caminos; en BT elικόstates de firma/seguridad live par de
  SPEC-004 §3 no aplican (el PC no firma nada: el BT no viaja por la app).

SPEC-004 §12 — nueva fila: transporte BT HID de respaldo → ADR de detalle.

ADR-007 — trigger de revisión: ADR actualizado a «revisión abierta» con
  nota de que CR-059 propuso reactivar BT HID como canal de respaldo.

ADR-003 — nota: CR-024 suspendió el BT HID; CR-059 lo propone reactivar
  solo como failover, con la decisión de detalle en un nuevo ADR.

Nuevo ADR «Canal de respaldo BT HID» (Proposed):
- Decisor: propuesto por este CR; pendiente del owner.
- Alternativas que debe resolver: perfil BT (BLE-HOGP vs classic SPP/HID),
  receptor en Windows (ViGEm/virtual HID driver vs stack propio), política
  de handover (automática vs manual), momento de suspensión definitiva si
  el failover no aporta en pruebas reales.
- Abrir solo después de validar en banco físico el rendimiento BT del
  teléfono (NFR-002) y del driver en Windows.
```

## 3. Motivation

Quinto cuello de botella: la LAN es un punto único de fallo para todo el
producto (video + audio + inputs). CR-055 (heartbeat) detecta el corte con
fiabilidad, y CR-056 reanuda el canal sobre la misma ruta; pero si la ruta
LAN entera sigue degradada, no hay por dónde reanudar. ADR-002 ya contempla
BT para HID como uso en el roadmap: la única pieza que falta es el
receptor en Windows y la política de handover.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003 (HID) | no | ya contempla el transporte BT como alternativa viable (lectura del intento original de ADR-003); el SPEC realiza esa parte | none |
| SPEC-001 | no | usa el heartbeat de CR-055 como trigger (dependencia en tiempo de implementación, no de documento) | none |
| SPEC-004 | yes | §4 doble transporte, §12 fila | update (1.11.0) |
| SPEC-002 | no | — | none |
| ARCH | no | la frontera de hid está definida; el failover no cruza módulos | none |
| DES-003 (android HID) | yes | descriptor/UI de activación del excelente failover | al implementar |
| ADR-007 | yes | nota de revisión | update |
| ADR-003 | yes | nota de reactivación | update |
| Nuevo ADR | new | perfil BT, receptor Windows, handover | crear |
| Tests | yes | reservados TEST-0128+ (failover, handover) | al aplicar |
| Code | yes | `hid/` y/o `server/` receptor Windows + `android/` servicio BT | al implementar |
| Data / migrations | no | — | none |
| Integrations / contracts | yes | nueva dependencia en Windows (driver de gamepad virtual) — riesgo de instalación/permiso | decidir en ADR |

## 5. Options considered

1. **Failover BT HID (propuesto)** — mismo descriptor ya construido (SPEC-004
   §2), independiza los críticos inputs del estado de la Wi-Fi.
2. Mantener LAN como único camino — sin work, los inputs permanecen pegados
   durante cualquier degradación de red: el mayor daño de runtime posible.
3. USB OTG (phone cable) — fuera de alcance v1: no ad-hoc, exige cable
   dedicado y rompe la portabilidad del teléfono mientras juega.

## 6. Decision

- **Outcome:** (pendiente) — aprobado el objetivo, pendiente nuevo ADR para
  perfil BT y receptor Windows (decisión con riesgo: dependencia de
  terceros/drivers).
- **Rationale:** es el único fallback físico disponible sin hardware extra;
  el descriptor ya existe y el Heartbeat de CR-055 provee el trigger.
- **Rollout:** release separado del CR-055/056/057; requiere banco físico.

## 7. Execution checklist

- [ ] Target document(s) updated
- [ ] Dependent documents updated (ADR-007, ADR-003)
- [ ] New ADR (perfil, receptor, handover)
- [ ] Tests added/updated
- [ ] `testing/traceability.md` updated
- [ ] Indexes updated
- [ ] Code change merged

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-01 | Buffy (agente) | Opened en lote de cuellos de botella de conexión |
