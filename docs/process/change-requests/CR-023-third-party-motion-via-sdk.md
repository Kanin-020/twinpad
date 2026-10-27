# Change Request (CR) — CR-023 — Twinpad

| | |
|---|---|
| ID | CR-023 |
| Title | Vía de giroscopio para terceros: SDK Twinpad como principal, Config externa sin movimiento y Bluetooth HID diferido |
| Type | Scope change |
| Status | `Applied` |
| Author | Buffy (agente) |
| Created | 2026-10-27 |
| Decided | 2026-10-27 |
| Decided by | Product owner (instrucción directa en la sesión) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `REQ-007` (limitación de movimiento), `SPEC-004` (§4 Canal: estado de HID), `SPEC-006` (§1 Purpose, nota de movimiento), `context.md` (CTX-A3/CTX-A5, alcance de movimiento) |
| Sections | REQ-007 §2; SPEC-004 §4; SPEC-006 §1; CTX-A3, CTX-A5 |
| Current version → new version | REQ-007 0.1.0 → 0.2.0; SPEC-004 1.7.0 → 1.8.0; SPEC-006 1.13.0 → 1.14.0 |

## 2. Requested change

**Current (SPEC-004 §4):**

```text
Bluetooth HID nativo sigue siendo el objetivo posterior de ADR-003.
```

**Proposed (SPEC-004 §4):**

```text
Bluetooth HID nativo queda diferido. Su alcance y su formato de giroscopio se definirán en un CR posterior; hasta entonces no forma parte del contrato de movimiento. La referencia ADR-003 queda sujeta a CR-024.
```

**Current (REQ-007 §2):**

```text
Twinpad debe poder operar sobre un Juego / Aplicación huesped que NO integra el SDK, a partir de una Config externa provista por la comunidad o el usuario. La Config externa declara, como mínimo, los controles a activar y, cuando el juego lo permite, el origen del contenido de Segunda pantalla.
```

**Proposed (REQ-007 §2, añadir al final):**

```text
La Config externa no transporta datos de movimiento (acelerómetro ni giroscopio). El giroscopio para terceros se entrega únicamente mediante la integración con SDK Twinpad (SPEC-006, modelo B).
```

**Current (SPEC-006 §1):**

```text
Define el registro de Plugins vivos, su selección por sesión Android y el relay de audio/video e inputs entre el Plugin y el Android device.
```

**Proposed (SPEC-006 §1, añadir al final):**

```text
Para terceros, el SDK Twinpad es la vía principal del giroscopio: el snapshot `input_report` con `motion` llega al Plugin mediante `poll_input()`. Las Config externas y el gamepad estándar no transportan giroscopio.
```

## 3. Motivation

Se analizaron las tres vías de integración (SDK modelo B, Config externa modelo C y gamepad estándar por sistema operativo). Solo el SDK transporta el snapshot `motion` definido en SPEC-004. HID genérico no tiene giroscopio, y el proyecto no tiene una especificación de HID con giroscopio. El objetivo declarado es que cualquier proyecto nuevo pueda recibir giroscopio, por lo que la vía principal debe ser la que ya lo transporta. Evidencia: SPEC-004 §4 (campo `motion`), SPEC-006 §4 (`poll_input`), REQ-007 y CTX-A3/CTX-A5.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-007 | yes | Declara límite de movimiento en Config externa | update (0.2.0) |
| SPEC-004 | yes | Estado de HID pasa a diferido | update (1.8.0) |
| SPEC-006 | yes | Nota de vía principal de giroscopio para terceros | update (1.14.0) |
| ARCH | no | Sin cambio de componentes | none |
| ENG | no | Sin cambio de reglas de código | none |
| DES-002 | no | El snapshot Android ya es el de SPEC-004 | none |
| DES-004 | no | Cemu sigue en su ruta actual | none |
| TEST-XXX | no | Ningún test cubre HID ni Config externa con movimiento | none; traceability sin cambio funcional |
| Code | no | Sin cambio de código en esta iteración | none |
| Data / migrations | no | — | none |
| Integrations / contracts | yes | Terceros deben usar SDK para giroscopio | version bump de SPEC-004/006 |

## 5. Options considered

1. SDK Twinpad como vía principal; Config externa sin movimiento; HID diferido — **elegida**: es la única vía que ya transporta giroscopio en el contrato.
2. HID prioritario para juegos sin SDK — descartada en esta iteración: HID genérico no tiene giroscopio y no hay formato definido.
3. Do nothing — la documentación seguiría sugiriendo que la Config externa puede llevar movimiento, y la compatibilidad de giroscopio quedaría ambigua.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** Decisión del product owner en la sesión del 2026-10-27, siguiendo la recomendación de la vía SDK principal.
- **Rollout:** same release (solo documentación de contrato; sin cambio de código).

## 7. Execution checklist

- [x] Target document(s) updated (version bumped, changelog entry references this CR ID)
- [x] Dependent documents updated (from impact table)
- [x] Tests added/updated (TEST IDs) — no aplica en esta iteración
- [x] `testing/traceability.md` rows updated (including CR history table)
- [x] Indexes (`_index.md`) updated
- [x] Code change merged (if any) — no aplica

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-27 | Buffy (agente) | Opened |
| 0.2.0 | 2026-10-27 | Buffy (agente) | Aprobado por el product owner; decide la vía de giroscopio para terceros |
