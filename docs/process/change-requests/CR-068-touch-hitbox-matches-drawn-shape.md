# Change Request (CR) — CR-068 — Twinpad

> **What this is:** a controlled, reviewable change to an approved artifact. One CR per logical change.

| | |
|---|---|
| ID | CR-068 |
| Title | La hitbox táctil de los controles coincide con la forma dibujada (círculo inscrito en los bounds, CR-067) |
| Type | `Behavior fix (aplicación)` |
| Status | `Applied (2026-11-03)` |
| Author | Buffy (agente), instrucción directa del owner («La hitbox de los iconos no se corresponde con su forma») |
| Created | 2026-11-03 |
| Decided | 2026-11-03 |
| Decided by | Owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | ninguno (SPEC-008 no define la forma del área de captura; las coords siguen siendo 0…1) |
| Code | `android/.../ui/ProfileTouchController.kt` (hitTest) |
| Tests | TEST-0144 nuevo (`InsideDrawnShapeTest`, 4 tests JVM) |

## 2. Requested change

**Current:**

```text
hitTest captura con el RECTÁNGULO completo de los bounds del control
(x…x+w, y…y+h), pero el renderer (CR-066/CR-067 estilo de referencia)
pinta cada control como el CÍRCULO inscrito en su lado menor: cara de
botón, plato del stick y plato del D-pad. Resultado: los iconos capturan
toques que no ven (p. ej. el halo de trigger_l absorbía la esquina del
botón de configs), y el D-pad tenía una hitbox elíptica 0.65·w/h propia
del transporte digital (ajuste del punto, no del área de captura).
```

**Proposed:**

```text
El área de captura ES la forma dibujada: círculo inscrito (lado menor de
los bounds) por control, en px de la superficie. La función pura
`insideDrawnShape` es la única regla (la usa hitTest y la testea
TEST-0144); el D-pad la comparte para el INICIO de captura, mientras el
ajuste digital del punto de dirección (DIGITAL_DPAD_RADIUS_SCALE 0.65)
queda intacto — es comportamiento de entrada, no de hitbox.
```

## 3. Motivation

Con los iconos circulares del estilo de referencia, la hitbox rectangular
dejaba zonas muertas visibles sin dibujo (y zonas de dibujo sin captura);
el owner reporta hitbox ≠ forma. La corrección alinea captura y render sin
tocar SPEC-008 (§4 solo fija coordenadas 0…1) ni la ruta wire/HID.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-008/REQ-008 | no | El contrato son coords 0…1 de bounds + validación; la forma de captura era comportamiento de app, no de SPEC. | none |
| TEST rows | yes | TEST-018 cubre la ruta input→juego (sin hitbox); TEST-0143 es del layout; se añade TEST-0144 para la regla de captura. | add |
| Wire / HID | no | Ningún mensaje cambia. | none |
| Riesgo | low | Reducir hitbox a lo visible puede cortar toques en bordes: el D-pad mantiene su comportamiento de ajuste digital una vez capturado. | validación owner |

## 5. Options considered

1. **Círculo inscrito = forma dibujada (propuesto)** — hitbox idéntica al
   icono; máxima claridad visual/táctil.
2. Hitbox elíptica rellenando los bounds — mantendría captura rectangular
   completa pero dibujando elipses (rompe el estilo de referencia).
3. Aumentar el dibujo hasta los bounds — iconos enormes, cubre zonas muertas
   con pictograma, no con captura.

## 6. Decision

- **Outcome:** `Approved` (por instrucción directa del owner)
- **Rollout:** mismo release (app Android).

## 7. Execution checklist

- [x] `insideDrawnShape` (pura) + hitTest px-based en `ProfileTouchController`
- [x] hitTestDpad retirado (la captura inicial usa la misma circunferencia)
- [x] TEST-0144 en traceability
- [x] Gates en verde

## 8. Icono del gear

Mismo batch de owner «quiero iconos bonitos»: el `btn_settings` del HUD
cambia `@android:drawable/ic_menu_preferences` → `@drawable/ic_settings`
(Material Symbols, Google/AOSP Apache-2.0; sin dependencia nueva).

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-03 | Buffy (agente) | Abierto y aplicado con TEST-0144 |
