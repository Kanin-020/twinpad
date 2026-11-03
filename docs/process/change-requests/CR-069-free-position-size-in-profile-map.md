# Change Request (CR) — CR-069 — Twinpad

> **What this is:** a controlled, reviewable change to an approved artifact. One CR per logical change.

| | |
|---|---|
| ID | CR-069 |
| Title | Posiciones y tamaños LIBRES en `profile_map.xml`: la paridad de TEST-0143 pasa a estructural (ids/kind/label/hid) |
| Type | `Scope change (ergonomía de desarrollo)` |
| Status | `Applied (2026-11-03)` |
| Author | Buffy (agente), instrucción directa del owner («En profile_map quiero poder cambiar los tamaños») |
| Created | 2026-11-03 |
| Decided | 2026-11-03 |
| Decided by | Owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | ninguno (TEST-0143 es un test de app, no un artefacto aprobado) |
| Code | `android/.../surface/ProfileMapLayoutTest.kt` |

## 2. Requested change

**Current:**

```text
TEST-0143 exigía paridad EXACTA (x/y/w/h con tolerancia 0.01) entre el
layout `profile_map.xml` y las constantes de `defaultProfile()`. Cualquier
ajuste del desarrollador a tamaños/posiciones del layout rompía el gate,
obligando a replicar el cambio a mano en las constantes del modelo.
```

**Proposed:**

```text
El layout `profile_map.xml` es la FUENTE del default en runtime
(`ProfileSurface` usa `ProfileMapLayout.extract` cuando no hay perfil
guardado); TEST-0143 pasa a exigir solo paridad ESTRUCTURAL con
`defaultProfile()`: los mismos 15 controles canónicos con kind/label/hid
exactos y bounds válidas 0…1. El desarrollador cambia posiciones/tamaños
en Android Studio sin tocar el modelo; el modelo queda como fallback del
runtime y fixture de tests.
```

## 3. Motivation

Los tamaños/posiciones son ajuste estético de desarrollo (CR-066/067);
exigir espejo numérico en `defaultProfile()` duplicaba cada edición y
bloqueaba la vía de edición declarada por CR-067.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-008 | no | §4 sigue definiendo coords 0…1 y «default = gamepad genérico»; la fuente concreta del default (layout vs constantes) es decisión de app. | none |
| TEST-0143 | yes | 2 tests relajados: `layout_matches_the_model_default_profile` (estructural) y `bounds_normalize_to_unit_surface_and_are_valid` (rango, sin x/y/w/h fijos). | update |
| TEST-0144 | no | Añadido por CR-068. | none |
| Runtime | no | `ProfileSurface` ya elegía el layout antes que `defaultProfile()`. | none |

## 5. Options considered

1. **Paridad estructural (propuesto)** — el layout gobierna el default;
   el modelo cubre el resto del contrato (validación §9, export).
2. Mantener paridad exacta y duplicar cada ajuste en el modelo — máxima
   duplicación, contrario a CR-067.
3. Actualizar constantes a mano en cada edición — mismo coste, sin beneficio.

## 6. Decision

- **Outcome:** `Approved` (por instrucción directa del owner)
- **Rollout:** mismo release (app Android).

## 7. Execution checklist

- [x] `ProfileMapLayoutTest`: paridad estructural + validación de rango
- [x] TEST-0143 actualizado en traceability
- [x] Gates en verde

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-03 | Buffy (agente) | Abierto y aplicado (TEST-0143 estructural) |
