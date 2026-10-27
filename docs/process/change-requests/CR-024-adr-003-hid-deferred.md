# Change Request (CR) — CR-024 — Twinpad

| | |
|---|---|
| ID | CR-024 |
| Title | Corrección de ADR-003: Bluetooth HID nativo queda como meta diferida, sin giroscopio comprometido |
| Type | Correction |
| Status | `Applied` |
| Author | Buffy (agente) |
| Created | 2026-10-27 |
| Decided | 2026-10-27 |
| Decided by | Product owner (instrucción directa en la sesión) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `ADR-003` (estado de implementación y consecuencias), `decisions/_index.md` (nota de estado) |
| Sections | ADR-003 §4 (Decision), §6 (Consequences), Changelog; `decisions/_index.md` fila ADR-003 |
| Current version → new version | ADR-003 1.0.0 → 1.1.0 |

## 2. Requested change

**Current (ADR-003 §4):**

```text
Usaremos emulación HID Bluetooth nativa (Opción A): el celular actúa como dispositivo HID periférico y Windows lo reconoce como gamepad estándar, sin software adicional en la PC.
```

**Proposed (ADR-003 §4, añadir al final):**

```text
Estado de implementación: diferido. La decisión de arquitectura (Opción A) se mantiene como meta final, pero no es alcance de la iteración actual. El giroscopio para terceros se entrega por el SDK Twinpad (CR-023, SPEC-006). El descriptor HID, incluido el campo de giroscopio de CTX-C5, se definirá en un CR posterior antes de implementarse.
```

**Current (ADR-003 §6, Negative):**

```text
- Signature HID fija por conexión (2 sticks, 10–12 botones, triggers L/R, gyro — CTX-C5): cambios futuros requieren re-emparejamiento.
```

**Proposed (ADR-003 §6, Negative):**

```text
- Signature HID fija por conexión (2 sticks, 10–12 botones, triggers L/R, gyro — CTX-C5): cambios futuros requieren re-emparejamiento. El formato de giroscopio de un descriptor HID no está especificado todavía; no se considera garantizado para terceros hasta su CR.
```

**Current (ADR-003 Changelog):**

```text
| 1.0.0 | 2026-10-07 | Twinpad team | Accepted (decisión de la entrevista) |
```

**Proposed (ADR-003 Changelog, añadir fila):**

```text
| 1.1.0 | 2026-10-27 | Buffy (agente) | Implementación diferida; giroscopio de terceros por SDK (CR-023, CR-024) |
```

## 3. Motivation

ADR-003 declara una decisión de implementación inmediata, pero el proyecto no tiene una especificación de descriptor HID con giroscopio. SPEC-004 §4 y el TODO.md ya la tratan como meta posterior (`ADR-007` resolvió el transporte inicial por sesión LAN). El análisis de CR-023 mostró que el HID genérico no transporta giroscopio, mientras que el SDK sí. Mantener ADR-003 sin matiz podría llevar a asumir compatibilidad de giroscopio para terceros que no está especificada.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| ADR-003 | yes | Estado de implementación y nota de giroscopio | update (1.1.0) |
| decisions/_index.md | yes | Nota de estado de ADR-003 | update |
| REQ-003 | no | La decisión de HID sigue siendo meta; REQ-003 AC-1 se mantiene pendiente | none |
| SPEC-004 | no | Ya dice que HID es objetivo posterior | none (ver CR-023) |
| ARCH §2, §6 | no | La decisión no cambia; solo su estado | none |
| TEST-XXX | no | Sin tests de descriptor HID | none |
| Code | no | Sin cambio de código | none |
| Integrations / contracts | no | Sin cambio de wire | none |

## 5. Options considered

1. Diferir ADR-003 y dejar el giroscopio para terceros en el SDK — **elegida**.
2. Mantener ADR-003 sin cambio — descartada: sugiere giroscopio HID no especificado.
3. Rechazar ADR-003 — descartada: reemplazaría la meta de producto (CTX-C3) sin decisión del owner.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** Decisión del product owner en la sesión del 2026-10-27, siguiendo la recomendación de CR-023.
- **Rollout:** same release (solo documentación).

## 7. Execution checklist

- [x] Target document(s) updated (version bumped, changelog entry references this CR ID)
- [x] Dependent documents updated (from impact table)
- [x] Tests added/updated (TEST IDs) — no aplica
- [x] `testing/traceability.md` rows updated (including CR history table)
- [x] Indexes (`_index.md`) updated
- [x] Code change merged (if any) — no aplica

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-27 | Buffy (agente) | Opened |
| 0.2.0 | 2026-10-27 | Buffy (agente) | Aprobado por el product owner; corrige el estado de ADR-003 |
