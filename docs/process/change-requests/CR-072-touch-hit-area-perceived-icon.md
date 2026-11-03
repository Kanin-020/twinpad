# CR-072 — Hitbox de percepción del perfil táctil y prioridad por forma menor en solape

| | |
|---|---|
| ID | CR-072 |
| Fecha | 2026-11-03 |
| Estado | `Implemented` |
| Origen | Validación del usuario: «los sticks hacen blink cuando se presionan los botones; solo deben hacerlo cuando se están tocando» |
| Afecta a | SPEC-008 (implementación, no REQ), CR-068 |
| Impacto | TEST-0144 (regresión, sin cambios), TEST-0145 (nuevo) |

## 1. Motivación (invariantes de CR-068 preexistentes)

CR-068 fijó hitbox = forma dibujada (círculo inscrito en bounds) y
`hitTest` la resolvía por **first-match** en el orden del perfil. Los
clicks L3/R3 van pegados a los sticks con geometría casi tangente
(retrato 1080×2340): plato del stick r ≈ 97 px, cara de L3 r ≈ 48.6 px,
centros a ≈ 148 px. Un tap junto a L3 cae a menudo FUERA de su círculo
perceptible pero DENTRO del plato del stick: el first-match (stick antes
en el orden de dibujo) se llevaba el toque, ilumina y desvía el stick sin
haberlo tocado — el «blink» reportado.

## 2. Decisión

1. **Prioridad por forma menor**: nuevo selector (`hitIndex`, puro y
   testable en JVM): entre todos los controles cuya hitbox contiene el
   punto gana la de MENOR radio; a igualdad gana el último del orden de
   dibujo (el top visible). Esto también asegura que los iconos pequeños
   dibujados encima de uno menos pequeño son el objetivo real.
2. **Margen de percepción**: la hitbox captura dentro del círculo dibujado
   + un margen de 8 dp (`HITBOX_MARGIN_DP`) — un icono de 48.6 px es un
   objetivo duro; el margen cubre los taps de borde. `insideDrawnShape`
   recibe `marginPx` con default 0: el contrato estricto de CR-068 queda
   inalterado en su test (TEST-0144) y en todo uso sin margen.

Regla exclusiva: NO cambia la forma del D-pad ni el ajuste digital del
punto director (0.65) ni las capacidades (2 sticks, ≤10 pointers).

## 3. Compatibility affecting SPEC/REQ

Ninguna regla de SPEC-008 cambia: es refinamiento de la implementación de
CR-068 impulsado por la validación. No hay SPEC por modificar.

## 4. Verificación

- TEST-0145 (`TouchHitPriorityTest`, JVM, geometría real 1080×2340):
  tap entre iconos → `button_l3` (no stick); tap profundo en el plato →
  stick; a 1.04·r de la cara del L3 → captura solo con margen; lejos de
  todo → −1.
- TEST-0144 sin cambios (margin default 0).
- Gates `scripts/test.sh` en verde antes de cerrar el ciclo.
- Validación en el dispositivo: pendiente del usuario.
