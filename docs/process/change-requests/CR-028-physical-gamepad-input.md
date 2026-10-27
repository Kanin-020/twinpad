# Change Request (CR) — CR-028 — Twinpad

| | |
|---|---|
| ID | CR-028 |
| Title | Mando físico conectado al teléfono: mapper y fusión con la superficie táctil |
| Type | `Behavior change` |
| Status | `Applied` |
| Author | Product owner + Twinpad team |
| Created | 2026-10-27 |
| Decided | 2026-10-27 |
| Decided by | Product owner (petición expresa en esta iteración) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-004 §6/§10/§11, `testing/traceability.md` |
| Sections | Orígenes de input del gamepad durante la sesión |
| Current version → new version | SPEC-004 `1.8.0 → 1.9.0` |

## 2. Requested change

**Current:**

```text
La Signature de SPEC-004 §4 solo tiene fuentes definidas para la superficie
táctil y los sensores del teléfono. Un mando físico (gamepad Bluetooth/USB
conectado al teléfono) no alimenta el InputReport.
```

**Proposed:**

```text
- En Linked (H1), PairingActivity intercepta KeyEvent y GenericMotionEvent
  de origen SOURCE_GAMEPAD/SOURCE_JOYSTICK y no durantre editing.
- Mapper puro (PhysicalGamepadAlgo, sin imports de Android) convierte:
  * keycodes de android.view.KeyEvent → bits de la Signature:
    A=96, B=97, X=99, Y=100, L1=102, R1=103, L3=110, R3=111,
    START=108, SELECT=109; L2=104/R2=105 fijan trigger 0…255; teclas
    D-pad 19–22 componen dirección (diagonales por OR; opuestas → neutro).
  * ejes: sticks X/Y (izquierdo) y Z/RZ (derecho) −1…1 con zona muerta
    0.1 sin reescalar; triggers BRAKE/LTRIGGER (L2) y GAS/RTRIGGER (R2);
  * hat AXIS_HAT_X/HAT_Y (−1/0/1) → dirección 0–7 u 8 neutro.
- Fusión por elemento (una sola fuente emission por ProfileSurface):
  el elemento accionado físicamente (stick ≠ 0 tras zona muerta, D-pad
  ≠ 8, trigger > 0) manda; en reposo conserva el valor táctil; buttons
  es OR de ambas; touch y motion viajan desde la superficie. El reporte
  combinado sigue por el camino único (GamepadStateMachine) que asigna
  seq y deduplica (SPEC-004 §6/R2); sin cambios de wire.
- Keycodes fuera de la Signature (p. ej. BACK=4) no se consumen.
- Sin sesión Linked los eventos no se consumen.
```

## 3. Motivation

El owner pidió soporte para mandos físicos tipo gamepad conectados al teléfono. La Signature HID de SPEC-004 ya define los elementos y rangos; falta el orígen de eventos del mando físico y su regla de fusión con la superficie táctil (REQ-003: el teléfono ES el gamepad virtual; ahora también acepta uno físico como fuente segunda).

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003 | no | El AC no fija el orígen de los eventos | none |
| SPEC-004 | yes | Nueva fuente de elementos y regla de fusión | R6 + edge case + changelog 1.9.0 |
| SPEC-002 | no | La ventana/sesión de Pairing no cambia | none |
| DES-002 | yes | Intercepción de eventos en PairingActivity y mapper puro | nota futura de changelog |
| Code | yes | `android/…/hid/PhysicalGamepadAlgo.kt` (nuevo), `ProfileSurface.kt`, `PairingActivity.kt` | hecho en esta iteración |
| Tests | yes | Nuevo test JVM TEST-087 (`PhysicalGamepadAlgoTest`) | traceability first table |
| Integrations / contracts | no | Wire NDJSON y Cemu sin cambios (la fusión es local de la app) | none |

## 5. Options considered

1. Mapper puro + fusión por elemento antes del reporte único — elegida: reutiliza la máquina de `seq`/dedup vigente y es testeable en JVM (ENG §5 P11).
2. Enviar reportes separados desde el mando físico — duplicaría GamepadStateMachine y rompería el snapshot único por sesión (SPEC-004 §4).

## 6. Decision

- **Outcome:** `Approved` (owner-requested)
- **Rationale:** la fusión por elemento mantiene un único InputReport por sesión con la Signature intacta; el mando físico es otra fuente, no otro gamepad.
- **Rollout:** same release (app android).

## 7. Execution checklist

- [x] Target document(s) updated (SPEC-004 1.9.0 con este CR)
- [x] Dependent documents evaluated (impacto evalúa no-cambio en SPEC-002)
- [x] Tests added (TEST-087)
- [x] `testing/traceability.md` rows updated (first table + change history)
- [x] Indexes (`_index.md`) updated
- [x] Code change applied (mapper + integración PairingActivity/ProfileSurface)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-27 | Product owner + Twinpad team | Opened (petición del owner) |
| 1.0.0 | 2026-10-27 | Product owner | Approved: mando físico como fuente con fusión por elemento |
| 1.1.0 | 2026-10-27 | Twinpad team | Applied: código Android + TEST-087 + SPEC-004 1.9.0 |
