# Change Request (CR) — CR-130 — Twinpad

| | |
|---|---|
| ID | CR-130 |
| Title | Coordinar el mando virtual del Servidor con la ruta LAN de Android |
| Type | `Behavior change` |
| Status | `Approved` |
| Author | Buffy (agente) |
| Created | 2026-11-26 |
| Decided | 2026-11-26 |
| Decided by | Product owner (aprobación explícita en conversación) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-001, SPEC-004, SPEC-006 |
| Sections | SPEC-001 §12; SPEC-004 §8.3/§8.4; SPEC-006 §4/§12 |
| Current version → new version | SPEC-001 1.5.1 → 1.5.2; SPEC-004 1.19.0 → 1.20.0; SPEC-006 1.30.0 → 1.31.0 |

## 2. Requested change

**Current:**

```text
SPEC-004 §8.4: Con otros juegos, mientras el transporte BT HID esté operativo, esos reportes no llegan al Servidor y el mando virtual queda en reposo (forzarlo exigiría otro cambio de la app, fuera del alcance de CR-113).
SPEC-006 §4: Mensajes desconocidos/malformados violan el contrato estricto de la sesión (E4, SPEC-001).
```

**Proposed:**

```text
SPEC-001 §12 y SPEC-006 §4/§12: añadir el mensaje server→Android de sesión `virtual_pad_state`, con `on:boolean` exactamente. El servidor emite el estado vigente después de vincular/reanudar la sesión y en cada transición efectiva del mando virtual.
SPEC-004 §8.3/§8.4: mientras `virtual_pad_state.on=true`, Android suspende su registro Bluetooth HID y usa exclusivamente LAN para los `input_report`; al recibir `on=false` o terminar la sesión, restaura el canal BT conforme a la política existente. El DualSense virtual del servidor recibe así los inputs por LAN. Con el modo apagado, se conserva la selección de ruta actual.
```

## 3. Motivation

El comportamiento descrito por CR-113/CR-114 deja sin inputs el DualSense para los receptores que usan BT HID, y el PC enumera simultáneamente el mando Bluetooth del teléfono y el DualSense del servidor. El owner solicita que con el mando virtual activado haya un solo dispositivo (el DualSense) con inputs funcionales.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003 | no | El resultado sigue siendo un gamepad HID con inputs | none |
| SPEC-001 | yes | Define el control de estado adicional en la sesión autenticada | actualizar §12 |
| SPEC-004 | yes | Suspensión/restauración BT, ruta LAN durante modo virtual y flujo DualSense | actualizar §8.3/§8.4 |
| SPEC-006 | yes | Define tipo/dirección/estado inicial del mensaje de sesión y su relay | actualizar §4/§12 |
| DES-002 | no | La ruta existente permite delegar selección al switcher Android | none |
| TEST-0226 | yes | Mensaje estricto y ruta LAN exclusiva durante modo activo | añadir/registrar |
| Code | yes | core/server notifica el estado; Android suspende/rearma HID y envía por LAN | code + focused tests |
| Data / migrations | no | Sin persistencia nueva | none |
| Integrations / contracts | yes | Extensión NDJSON compatible de sesión autenticada | CR aplicado a ambos extremos |

## 5. Options considered

1. Añadir `virtual_pad_state` y suspender el HID Android durante el modo virtual — satisface ambos síntomas y restaura el comportamiento al apagarlo.
2. Do nothing — el DualSense sigue inactivo en sesiones donde Android enruta por BT y Steam continúa mostrando ambos dispositivos.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** Aprobación explícita del owner para que el servidor avise a Android, Android suspenda/restaure su registro HID y utilice LAN mientras el DualSense virtual esté activo.
- **Rollout:** cambio coordinado de los extremos del protocolo NDJSON de sesión; sin migración persistente.

## 7. Execution checklist

- [x] Target document(s) updated (version bumped, changelog entry references CR-130)
- [x] Dependent documents updated (from impact table)
- [x] Tests added/updated (TEST-0226 core, TEST-0227 Android)
- [x] `testing/traceability.md` rows updated (including CR history table)
- [x] Indexes (`_index.md`) updated
- [x] Code change applied in the worktree (commit pending)

> Physical validation with Steam and the server DualSense (single listed pad, inputs working, BT restored when the mode ends) remains pending on a device/bench.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-26 | Buffy (agente) | CR-130 aprobado por el owner para coordinación del modo mando virtual |
