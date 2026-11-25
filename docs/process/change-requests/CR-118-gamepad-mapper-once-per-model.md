# Change Request (CR) — CR-118 — Twinpad

| | |
|---|---|
| ID | CR-118 |
| Title | El mapeador se abre solo la primera vez que se detecta un modelo de mando |
| Type | Clarification |
| Status | Approved |
| Author | Buffy (agente) |
| Created | 2026-11-25 |
| Decided | 2026-11-25 |
| Decided by | Product owner (chat: «solo debe salir la primera vez que se detecta el mando físico») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-011 §6 (M1), §7.1, §9 (AC-1), §10 |
| Sections | §6, §7, §9, §10 |
| Current version → new version | `0.2.2 → 0.2.3` |

## 2. Requested change

**Current:** el mapeador se abre «una vez por conexión»; al reabrir la app o
reconectar el mismo mando vuelve a aparecer si no hay perfiles.

**Proposed:** la autoapertura ocurre **una sola vez en la vida de la app para
cada identidad de modelo**. Se persiste una marca al mostrar el mapeador; las
detecciones posteriores (reinicio, reconexión, cambio de sesión) no lo abren
automáticamente. La acción «Mando físico» del drawer sigue abriéndolo a mano.

## 3. Motivation

El owner reportó que el mapeador se abría «cada vez que se abre la app». El
aviso debe ser una ayuda de primer uso, no un bloqueo recurrente de la sesión.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-011 | yes | M1, §7.1, AC-1 y §10 | update vía este CR |
| Code | yes | `GamepadProfileStore.claimAutoOpen` + `PhysicalGamepadController` | implementado |
| Tests | yes | TEST-0212 cubre la marca única por modelo y su persistencia | añadido |
| Datos | yes | nueva preferencia `auto_open_shown:<modelo>` en el mismo fichero de perfiles | sin migración |

## 5. Decision

- **Outcome:** una única autoapertura por modelo, persistida.
- **Rollout:** local; sin wire.

## 6. Execution checklist

- [x] Código aplicado
- [x] TEST-0212 añadido y en trazabilidad
- [x] SPEC-011 0.2.3
- [x] Índices actualizados

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-25 | Buffy (agente) | Abierto y decidido con la petición del owner |
