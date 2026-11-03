# Change Request (CR) — CR-066 — Twinpad

> **What this is:** a controlled, reviewable change to an approved artifact. One CR per logical change.

| | |
|---|---|
| ID | CR-066 |
| Title | La superficie de controles NO se edita en la app: el ajuste del mapeo es tarea del desarrollador vía config (fuera de alcance un editor en pantalla) |
| Type | `Scope change` |
| Status | `Applied (2026-11-03, code-applied; actualización de SPEC-008 pendiente)` |
| Author | Buffy (agente), instrucción directa del owner |
| Created | 2026-11-03 |
| Decided | 2026-11-03 |
| Decided by | Owner («Elimina lo de toggle editing UI, no quiero que el usuario edite, el tema de edición es para hacerse desde Android Studio») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `SPEC-008` (Draft: §2 editor, §3 S0 Editing, §5 guardar desde el editor, §8 acciones, §16 OQ del editor) |
| Sections | §2/§3/§5/§8/§9/§16 |
| Current version → new version | `SPEC-008 0.x.x → pendiente` |

## 2. Requested change

**Current:**

```text
SPEC-008 (Draft) define un EDITOR en pantalla: acción de editor
(agregar/mover/redimensionar/asignar/eliminar), estado `S0 Editing`
(en el que no se envían inputs), R3 (la edición ocurre en S0) y la
superficie editable con arrastre. La OQ de §16 (¿editor solo en el
celular o también desde la PC?) quedó abierta.
```

**Proposed:**

```text
No hay editor en la app y ningún estado S0: el usuario NO edita la
superficie. El mapeo se ajusta editando la config por desarrollador
(archivo de perfil en `filesDir/profiles/` o Config externa
`twinpad-config-v1` de SPEC-007 §4; formas/posiciones viajan en el
modelo `TouchControl` de SPEC-008 §4 sin cambios) y se compila/instala
desde Android Studio. La OQ de §16 se cierra: la edición vive en la
PC (herramientas de desarrollo), no en el teléfono.
```

## 3. Motivation

La ruta de edición existente era código inaccesible: nadie llama
`toggleEditing()` (el único disparador era la propia superficie) y el
owner además NO quiere que el usuario consumidor edite controles. El
código muerto cumple el S0 Editing solo teoricamente; mantenerlo
obligaba a arrastrar parámetros de edición por la ruta de inputs real.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-008 | no | Los AC observables (carga automática, conflicto) no dependen del editor. | none |
| SPEC-008 | yes | Se retira del Draft el editor/S0; el modelo §4 y la carga §5 quedan. | update (pendiente) |
| SPEC-007 | no | La Config externa sigue siendo la vía de suministro. | none |
| ARCH | no | Sin cambios de fronteras. | none |
| TEST rows | yes | TEST-023: la descripción pierde «superficie editable»; los 9 tests del modelo/persistencia no cambian. | update |
| Code | yes | `ui/ProfileSurface`, `ui/ProfileTouchController`, `ui/ProfileSurfaceRenderer`: se elimina la maquinaria de edición (~120 líneas). | aplicado |
| Data / migrations | no | Los perfiles JSON existentes son válidos. | none |
| Integrations / contracts | no | Signature HID de SPEC-004 intacta. | none |

## 5. Options considered

1. **Retirar la maquinaria de edición (propuesto)** — la ruta productiva queda única (el input táctil siempre produce inputs reales), y SPEC-008 §4 sigue definiendo las formas/posiciones que el desarrollador edita en la config.
2. Completar el editor en pantalla (drawer editable) — contradecir la
   instrucción del owner y añadir la superficie de atacar la ruta de inputs.
3. Dejar el código muerto — mantiene una segunda rama por toque y desalinea
   TEST-023 al documentar un flujo que nadie puede lanzar.

## 6. Decision

- **Outcome:** `Approved` (por instrucción directa del owner)
- **Rationale:** la edición del mapeo es ergonomía del desarrollador, no
  del usuario; la config JSON es la fuente de verdad y el repo ya trae el
  default del §10 (rev. del §10).
- **Rollout:** mismo release (app Android).

## 7. Execution checklist

- [ ] SPEC-008 actualizado (editor retirado del Draft; OQ §16 cerrada)
- [x] Código aplicado (`ProfileSurface`, `ProfileTouchController`, `ProfileSurfaceRenderer`)
- [x] Tests: los existentes no cubren edición (nada que actualizar); se
  ejecutó la suite completa de gates
- [x] `testing/traceability.md` filas de CR-066 y TEST-023 en el historial
- [x] `_index.md` actualizado (next free CR-067, fila de CR-066)
- [x] Gestión de cambios cerrada (CR-066 aplicada con gates en verde)

## 8. Nota de diseño

El render del estado `pressed` del control (alpha de pulsación y
`state_pressed` del drawable) se CONSFRVA: es feedback del input real,
no parte del editor. Solo se retiran: selección con contorno, arrastre
de posición y las ramas `if (editing)`.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-03 | Buffy (agente) | Abierto y aplicado en código con gates en verde |
