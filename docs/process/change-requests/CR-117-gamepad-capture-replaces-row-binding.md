# Change Request (CR) — CR-117 — Twinpad

| | |
|---|---|
| ID | CR-117 |
| Title | La captura de una fila reemplaza su entrada física (aclaración de SPEC-011) |
| Type | Clarification |
| Status | Approved |
| Author | Buffy (agente) |
| Created | 2026-11-25 |
| Decided | 2026-11-25 |
| Decided by | Product owner (chat: «presiona el botón que lo reemplaza») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-011 §6 (M3), §7.3 |
| Sections | §6, §7 |
| Current version → new version | `0.2.1 → 0.2.2` |

## 2. Requested change

**Current (contradictorio):**

```text
§6 M3: «la entrada física se añade al mapeo de la entrada canónica»
§7.3: «Una entrada puede tener varias entradas físicas (duplicados permitidos)»
AC-2: «pulsar un botón reemplaza esa entrada»
```

**Proposed:**

```text
§6 M3: la entrada física capturada REEMPLAZA las entradas físicas de esa
entrada canónica; se aplica y se guarda al instante.
§7.3: los duplicados permitidos son entre entradas canónicas: una misma
entrada física puede alimentar varias filas. Una fila conserva la última
entrada capturada.
```

## 3. Motivation

La frase del owner en la petición de implementación («hace click en la
entrada y luego presiona el botón que lo reemplaza») y AC-2 fijan reemplazo.
§6/§7.3 describían acumulación, que dejaría activa la equivalencia anterior y
haría que «reemplazar» no surtiera efecto.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-011 | yes | M3 y §7.3 alineados con AC-2 | update vía este CR |
| Code | yes | `GamepadMapping.with` sustituye la lista de la fila | implementado |
| Tests | yes | TEST-0209 cubre el reemplazo y un duplicado entre filas | actualizado |
| Codec | no | sigue admitiendo listas (datos antiguos con varias entradas siguen leyéndose) | none |

## 5. Decision

- **Outcome:** reemplazo por fila y duplicados entre filas.
- **Rollout:** local; sin wire.

## 6. Execution checklist

- [x] Code applied (`hid/GamepadMapping.kt`)
- [x] TEST-0209 actualizado
- [x] SPEC-011 0.2.2
- [x] Traceability actualizada

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-25 | Buffy (agente) | Abierto y decidido con la frase del owner en la petición |
