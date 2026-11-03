# Change Request (CR) — CR-070 — Twinpad

> **What this is:** a controlled, reviewable change to an approved artifact. One CR per logical change.

| | |
|---|---|
| ID | CR-070 |
| Title | Distribución Nintendo/Xbox de los botones de cara sin coordenadas constantes: los 4 slots se derivan de la geometría del propio perfil |
| Type | `Behavior fix (aplicación)` |
| Status | `Applied (2026-11-03)` |
| Author | Buffy (agente), instrucción directa del owner («en el juego se ven chuecos, Y y A no aparecen alineados aunque en el mapper se ve bien») |
| Created | 2026-11-03 |
| Decided | 2026-11-03 |
| Decided by | Owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | ninguno (SPEC-008 §11 R5 sin cambios: la vista Nintendo emite button_x arriba, button_y izquierda, button_a derecha y button_b abajo, igual que CR-032) |
| Code | `android/.../surface/ControlProfile.kt` (`applyFaceDistribution`) |
| Tests | TEST-090 actualizado (`FaceDistributionTest`: +2 casos con clúster ancho) |

## 2. Requested change

**Current:**

```text
applyFaceDistribution (CR-032) sobreescribe las posiciones de los cuatro
botones de cara con CONSTANTES porcentuales fijas del default 16:9
(XBOX/NINTENDO_FACE_POSITIONS → DEF_FACE_*). Con el clúster del CR-069
(rombo en dp cuadrado sobre un eje invisible), esas constantes ya no
describen la geometría real: en el teléfono el clúster se veía como el
default 16:9 estirado — Y y A quedaban desalineados entre sí (cx 0.885
vs 0.865) y el rombo chueco, aunque el mapper mostrara el XML correcto.
```

**Proposed:**

```text
Los slots (arriba, abajo, izquierda, derecha) se derivan de la geometría
del PROPIO perfil: top/bottom por cy extremos de los cuatro controles de
cara y, entre los dos del medio, izquierda/derecha por cx. Nintendo
asigna button_x→top, button_y→left, button_a→right, button_b→bottom;
Xbox el orden inverso (es la restauración exata del perfil persistido).
Las identidades (id/label/hid) viajan con su control (R5), el resto del
perfil queda intacto, apagar restaura y es idempotente — para cualquier
aspecto de pantalla, layout o perfil guardado.
```

## 3. Motivation

El clúster del mapeo (CR-069: rombo dp) ya no coincide numéricamente con
las constantes del default; el reparto fijo lo pisaba al aplicar la
preferencia y rompía la alineación visible en runtime.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-008 R5 | no | El contrato observable (posición→Elemento HID en cada vista) es idéntico; solo cambian los MECHANISM internos de posicionamiento. | none |
| TEST-090 | yes | 2 casos nuevos: clúster ancho 2400×1080 — Nintendo permuta los slots propios y apagar restaura los 4 controles byte a byte. Los 5 casos previos siguen verdes (el default 16:9 coincide con las constantes). | update |
| Perfiles guardados | no | Los perfiles JSON se guardan canónicos Xbox; la vista se recalcula al cargar y al alternar el switch — los slots se derivan cada vez del propio perfil. | none |

## 5. Options considered

1. **Slots desde el perfil (propuesto)** — la vista respeta la geometría
   declarada en `profile_map.xml` (CR-067/CR-069) y en perfiles guardados.
2. Actualizar las constantes a los valores del rombo — volvía a acoplar la
   vista a una resolución/aspecto concreto (el rombo dp varía con el
   teléfono) y replicaba el defecto CR-070 en cualquier otro aspecto.
3. Desaplicar la distribución sobre el clúster — contradice el R5 vigente
   (el switch del drawer sigue requerido por CR-032).

## 6. Decision

- **Outcome:** `Approved` (por instrucción directa del owner)
- **Rollout:** mismo release (app Android).

## 7. Execution checklist

- [x] `applyFaceDistribution` con slots derivados del perfil
- [x] Mapas de constantes retirados (sin usos)
- [x] TEST-090 actualizado en traceability
- [x] Gates en verde

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-03 | Buffy (agente) | Abierto y aplicado (TEST-090 +2 casos) |
