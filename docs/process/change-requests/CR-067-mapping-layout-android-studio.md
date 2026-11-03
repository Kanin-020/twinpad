# Change Request (CR) — CR-067 — Twinpad

> **What this is:** a controlled, reviewable change to an approved artifact. One CR per logical change.

| | |
|---|---|
| ID | CR-067 |
| Title | El mapeo táctil se define en `res/layout/profile_map.xml` (layout Android con `TwinpadControlView`), editable con drag & drop del editor de diseño de Android Studio |
| Type | `Scope change` |
| Status | `Applied (2026-11-03, code-applied; actualización de SPEC-008 pendiente)` |
| Author | Buffy (agente), instrucción directa del owner |
| Created | 2026-11-03 |
| Decided | 2026-11-03 |
| Decided by | Owner («Debe tener una vista fácil de editar, como los xml en res/drawables que te permiten usar la herramienta drag and drop de Android Studio» / «me refiero a que en android studio haya un xml con que se edite facilmente por el desarrollador») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `SPEC-008` (§4 forma de suministro del default/mapeo del desarrollador; §16 cerrada por CR-066 se DESARROLLA aquí) |
| Sections | §4, §5, §16 |
| Current version → new version | `SPEC-008 0.x.x → pendiente` |

## 2. Requested change

**Current** (CR-066):

```text
El mapeo se ajusta editando la config por desarrollador (archivo de
perfil en filesDir/profiles/ o Config externa twinpad-config-v1 …)
desde Android Studio. No hay editor en la app.
```

**Proposed:**

```text
La vía de edición del desarrollador NO es JSON a mano: es un LAYOUT
Android estático, `res/layout/profile_map.xml`, con vista marcador
TwinpadControlView (attrs twinpadKind/twinpadLabel/twinpadHid) y
guidelines PORCENTUALES (0…1). El desarrollador lo edita con el editor
de diseño de Android Studio (drag & drop para posición/tamaño, los
porcentajes a mano): formas y colores siguen siendo drawables XML
(touch_button, bg_control, stick_base, stick_thumb). En runtime,
ProfileMapLayout.extract infla el layout fuera de pantalla, normaliza
bounds a 0…1 (SPEC-008 §4) y valida con las reglas compartidas del
modelo (§9); el resultado ES el perfil default (paridad con el
defaultProfile del modelo: TEST-0143). Los perfiles por juego del
usuario (§5, store JSON) quedan intactos como carga automática.
```

## 3. Motivation

CR-066 dejó la edición «vía config», pero un JSON a mano no da feedback
visual ni posiciones exactas. El owner quiere la misma ergonomía que los
`res/drawables`: abrir un XML y arrastrar en el editor visual. Un layout
de vista-powered por el editor reproduce exactamente esa experiencia, y
el mapeo queda definido de forma declarativa, versionada y diffable.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-008 | yes | La vía de suministro del default del mapeo pasa de «config» (CR-066) a «layout del desarrollador»; OQ §16 queda cerrada con la vía exacta. | update (pendiente) |
| SPEC-007 | no | La Config externa `twinpad-config-v1` sigue siendo válida como entrada del usuario final; CR-067 NO la toca. | none |
| ARCH | no | Nuevo módulo de superficie (`surface/`), sin cambio de fronteras. | none |
| TEST rows | yes | TEST-0143 nuevo: extractor, normalización, validación heredada y paridad default (7 tests JVM Robolectric). | add |
| Code | yes | `surface/TwinpadControlView` (marcador + attrs + preview de editor), `surface/ProfileMapLayout` (extract), `res/layout/profile_map.xml`, `res/values/attrs_twinpad_control.xml`, app `build.gradle.kts` (dependencias de test JVM/Robolectric). | aplicado |
| Data / migrations | no | El formato de perfil JSON de `filesDir/profiles/` no cambia. | none |
| Integrations / contracts | no | Signature HID de SPEC-004 intacta; wire sin cambios. | none |

## 5. Options considered

1. **Layout estático + vista marcador (propuesto)** — drag & drop nativo
   del editor, sin инструментo ad hoc y sin runtime de arrastre en la app.
2. Config JSON/asset XML a mano (CR-066) — sin feedback visual y con
   riesgo de errores de coordenadas.
3. Reactivar el editor en pantalla (CR-066 lo descartó por instrucción
   del owner) — contradice la decisión y roba la ruta de inputs.

## 6. Decision

- **Outcome:** `Approved` (por instrucción directa del owner)
- **Rationale:** ergonomía exactamente igual a los drawables; el ejecutable
  mapeo vive en un XML del repo, y el default del modelo queda verificado
  por paridad (TEST-0143) para que el default de fábrica y el del layout
  no se desalineen.
- **Rollout:** mismo release (app Android).

## 7. Execution checklist

- [ ] SPEC-008 actualizado (§4 vía layout; §16 cerrada con la vía exacta)
- [x] Código aplicado (`TwinpadControlView`, `ProfileMapLayout`, `profile_map.xml`, `attrs_twinpad_control.xml`)
- [x] Tests: TEST-0143 (7 tests JVM Robolectric) en verde — extracción de
  15 controles, normalización a 0…1, paridad con el default del modelo,
  y rechazo (hid inválido, id duplicado, campo ausente, bounds fuera de rango)
- [x] `testing/traceability.md`: fila de TEST-0143 añadida
- [x] `_index.md` actualizado (next free CR-068, fila de CR-067)
- [x] Gates en verde antes del cierre

## 8. Nota de diseño

`TwinpadControlView` es solo marcador: pinta siluetas únicamente cuando
`isInEditMode` (preview del editor de Android Studio); en runtime nunca
dibuja ni recibe toque — el renderer de la superficie (`ProfileSurfaceRenderer`)
usa los drawables tal como estaba. El extractor deriva las bounds del
layout medido (view.x/width del wrapper) y el android:id de la
vista da el id del control (fallback al label si no tiene id).

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-03 | Buffy (agente) | Abierto y aplicado en código con tests en verde |
