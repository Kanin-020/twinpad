# CR-071 — Ajuste fino del render de la superficie táctil y endurecimiento de la carga de perfil

| | |
|---|---|
| ID | CR-071 |
| Fecha | 2026-11-03 |
| Estado | `Implemented` |
| Origen | Ajuste del usuario tras validar CR-068/069/070 en el juego |
| Afecta a | SPEC-008 (implementación, no REQ), CR-067/068/069 |
| Impacto | TEST-023 (regresión), TEST-090 (regresión) |

## 1. Motivación

Validación en el juego de la superficie táctil dejó cinco observaciones:

1. Los botones de cara Y/X/A/B conservaban un ligero desfase: el centro del
   D-pad quedaba en cy ≈ 0.3715 frente al eje del clúster de cara en
   cy 0.3665 (vertical_bias 0.3164 con height 0.30 no alineaba la fila).
2. La cruceta debía verse más grande y las flechas separadas entre sí:
   con `DPAD_BUTTON_OFFSET` 0.45, dos flechas contiguas se solapaban
   (distancia diagonal 0.45·√2 ≈ 0.636·R < 0.64·R = suma de radios).
3. La opacidad de todos los botones resultaba alta.
4. Las etiquetas usaban negritas; se pide letra normal.

## 2. Decisión

- **Fila del D-pad** (CR-067/069): `dpad_cross` en `vertical_bias 0.3093`
  → centro cy = 0.3093·(1−0.30) + 0.15 = 0.3665, idéntico a `face_axis`.
- **Cruceta**: `dpad_cross` en `width_percent 0.24` (antes 0.20); el plato
  render es el lado menor, así el plato crece ~20 %.
- **Flechas**: `DPAD_BUTTON_OFFSET` 0.45 → 0.58 (hueco diagonal
  0.82·R − 0.64·R = 0.18·R, sin salirse del plato: 0.58 + 0.32 ≤ 0.94).
  La posición de mapeo no cambia: la hitbox y el uso siguen siendo el
  conjunto entero (CR-068).
- **Opacidad**: `NORMAL_ALPHA` 204 → 150, `PRESSED_ALPHA` 153 → 235
  (el pulso del control activo sigue perceptible), `DPAD_DOT_ALPHA`
  120 → 95. `DIMMED_ALPHA` sin asignar queda en 77.
- **Tipo**: etiquetas en `Typeface.NORMAL` (letra normal, sin negritas).

## 3. Endurecimiento (nobehavior-invented: SPEC-008 §9)

`ProfileSurface.loadProfile` ahora captura `ProfileException` del
[`ProfileMapLayout.extract`] y cae al perfil default en lugar de
explotar la corrutina del ciclo de vida (un layout de mapeo inválido se
descarta, mismo criterio que un perfil persistido corrupto; §9).

## 4.impacto en SPEC/REQ

Ninguna regla de SPEC-008 cambia: es ajuste de la implementación de
CR-068/069 y robustez §9. No hay SPEC por modificar.

## 5. Ronda 2 (validación del 2026-11-03)

Tras probar el build con la ronda 1, el usuario pidió:

1. Opacidad más baja aún: `NORMAL_ALPHA` 150 → 115,
   `DPAD_DOT_ALPHA` 95 → 75 (`PRESSED_ALPHA` 235 se conserva).
2. Subir un poco el clúster XYAB y la cruceta: fila común del D-pad y
   del clúster de cara baja de cy 0.3665 a **cy 0.3515** (ambos clústeres
   con el MISMO delta: `face_axis` en 0.3515 y `dpad_cross` en
   vertical_bias 0.2879, conservando la alineación de la ronda 1).
3. Cruceta aún más grande: `dpad_cross` `width_percent` 0.24 → 0.27
   (en retrato el plato es el lado menor: r = 0.135·W).
4. A/Y «no alineados verticalmente»: en el perfil derivado de
   `profile_map.xml` comparten la MISMA coordenada cx (la desalineación
   percibida no reproduce la geometría; se pidió captura para descartar
   artefacto de render/video en el próximo ciclo.

## 6. Verificación

- Compilación + gates (`scripts/test.sh`): testDebugUnitTest, ktlint y
  detekt en verde.
- Los tests de ProfileMapLayout inflan el XML actualizado (Robolectric):
  cubren regenerativamente la fila del D-pad con el nuevo bias (los tests
  de posiciones usan el conjunto como fixture).
- Validación visual en el dispositivo: pendiente del usuario.
