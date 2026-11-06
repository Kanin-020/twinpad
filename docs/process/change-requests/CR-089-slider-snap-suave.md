# Change Request (CR) — CR-089 — Twinpad

> **What this is:** a controlled, reviewable change to an approved artifact. One CR per logical change.

| | |
|---|---|
| ID | CR-089 |
| Title | Suavizar el ajuste automático del slider de métricas: corte en el medio (49 %/51 %) decidido al soltar el dedo y una sola animación proporcional con desaceleración |
| Type | `Behavior change` |
| Status | `Applied (2026-11-06, code-applied; nota de SPEC-002 §6 pendiente)` |
| Author | Buffy (agente), instrucción directa del owner |
| Created | 2026-11-06 |
| Decided | 2026-11-06 |
| Decided by | Owner («Ahora quiero revises el slider de metricas, la animación de expander al 100% y ocultar es demasiado abrupta cuando se llega al 30% y 70%») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `SPEC-002` (§6, nota de la hoja de métricas abierta por CR-079) |
| Sections | §6 |
| Current version → new version | `1.13.0 → pendiente` |

## 2. Requested change

**Current (CR-079 v12):**

```text
En cada MOVE del drag se recalcula el ajuste automático y se relanza su
animación (150 ms fijos, aceleración inicial). Al cruzar el umbral del
30 % (abrir a 100 %) o del 70 % (plegar), el panel salta animado pero la
animación se reinicia en CADA evento del dedo: el panel se queda clavado
y acaba saltando de golpe. Además, mover unos píxeles alrededor del
umbral alternaba panel clavado → seguimiento del dedo → panel clavado.
```

**Proposed:**

```text
- Durante el arrastre el panel sigue al dedo 1:1, sin ajustes ni animaciones:
  desaparece el salto al cruzar el umbral (era la brusquedad reportada).
- El ajuste se decide UNA sola vez, al soltar (objetivoAlSoltar): con el
  corte del medio (recorrido ≥ 51 %, resto ≤ 49 %) termina de abrirse (0) o
  de plegarse (tope); en la franja del medio se queda donde lo sueltas (v6).
- Animación única de 140 ms (recorrido mínimo) a 280 ms (plegado completo)
  según el recorrido real que le queda al panel, con DecelerateInterpolator
  en lugar de aceleración inicial.
- El plegado inicial al entrar en sesión usa la misma animación proporcional
  (0 → tope, 280 ms desacelerando).
```

El umbral pasa de 30 % a 49 %/51 % (`FRACCION_AJUSTE_PANEL = 0.49`, pedido del
owner: «En vez de 30% puedes hacer que sea 49 y 51?»): pasado el medio el panel
termina de abrirse y por debajo termina de plegarse, siempre al soltar.

> **Nota de la v0.3.0 (prueba del owner):** la primera versión mantenía el
> ajuste DURANTE el arrastre (clavado + histéresis del 10 %) con el corte nuevo.
> Con el corte en el medio eso deja el panel soldado: desde plegado la primera
> MOVE ya cae en el tramo de cierre y el ajuste se engancha a «plegado», así que
> el panel no se mueve durante el 59 % del recorrido y no llega a abrirse; en el
> sentido contrario, igual. Por eso el ajuste pasa a decidirse al soltar.

## 3. Motivation

Pedido del owner: la animación de expandir al 100 % y de ocultar era demasiado
abrupta al llegar al 30 % y al 70 %. La causa no era la duración (150 ms) sino
que cada MOVE recalculaba el ajuste y relanzaba la animación desde la posición
viva, así que el panel se quedaba clavado y saltaba de golpe al cruzar el
umbral; sin histéresis, el dedo podía alternar enganchado/seguimiento varias
veces por segundo.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-002 | no | — | none |
| REQ-006 | no | Ninguno sobre `host_metrics` ni el pintado de gauges | none |
| SPEC-002 | yes | §6 documenta la hoja de métricas con nota pendiente (CR-079) | update pendiente (owner) |
| SPEC-006 | no | Wire sin cambios | none |
| ARCH | no | — | none |
| DES-002 | no | — | none |
| TEST-0173 / TEST-0174 / TEST-0179 | no | Siguen siendo válidos: helpers puros `ajusteAutomaticoAlFinal` / `pliegueAutomaticoAlFinal` / `panelConAnimacion` intactos | none |
| Code | yes | `MetricsSliderController.kt` (snap único + histéresis + duración proporcional); `MetricsSliderContainer.kt` solo comentario | aplicado |
| Data / migrations | no | — | none |
| Integrations / contracts | no | — | none |

## 5. Options considered

1. Subir la duración de la animación (150 → 300 ms) — no toca la causa: la animación se seguía relanzando en cada MOVE.
2. Mantener el ajuste durante el arrastre con histéresis (probado en la v0.1.0) — con el corte en el medio el panel queda soldado y no se puede invertir el gesto.
3. Ajustar solo al soltar, con el corte en el medio y duración proporcional (elegido) — el arrastre es 1:1, la animación ocurre una vez y el panel termina siempre en un extremo estable.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** el corte en el medio (49 %/51 %) que pidió el owner solo tiene sentido como decisión al soltar; así el arrastre no salta nunca y la animación ocurre una sola vez por gesto.
- **Rollout:** misma iteración, solo app Android; la sensación final del gesto se valida en banco físico.

## 7. Execution checklist

- [ ] Target document(s) updated (SPEC-002 §6, versión y changelog con este CR)
- [x] Dependent documents updated (ninguno)
- [x] Tests added/updated (TEST-0184 nuevo; TEST-0173/0174/0179 sin cambios)
- [x] `testing/traceability.md` rows updated (TEST-0184 + historia de cambios)
- [x] Indexes (`_index.md`) updated
- [x] Code change merged (if any)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-06 | Buffy (agente) | Abierto y aplicado en código: snap único por gesto con histéresis del 10 % y animación proporcional de 140–280 ms con desaceleración |
| 0.2.0 | 2026-11-06 | Buffy (agente) | Umbral de 30 % a 49 %/51 % (owner: «En vez de 30% puedes hacer que sea 49 y 51?»): `FRACCION_AJUSTE_PANEL = 0.49`; TEST-0173/0174 al nuevo corte |
| 0.3.0 | 2026-11-06 | Buffy (agente) | Fix (owner: «Ahora esta roto creo que están al revés»): el ajuste se decide UNA vez al soltar (`objetivoAlSoltar`), no durante el arrastre — con el corte en el medio, clavarlo mientras el dedo sigue soldaba el panel (no se movía el 59 % del recorrido y no llegaba a abrirse). Se retiran la histéresis y el estado de enganche; el arrastre es 1:1 y la animación (140–280 ms, desacelerando) se lanza al soltar. TEST-0184 renombrado a `MetricsSliderReleaseSnapTest` |
