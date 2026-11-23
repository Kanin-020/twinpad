# Change Request (CR) — CR-107 — Twinpad

| | |
|---|---|
| ID | CR-107 |
| Title | Barra de rendimiento de la sesión como `BottomSheetBehavior` de Material (sustituye el gesto propio CR-079) |
| Type | Behavior change |
| Status | Approved |
| Author | Buffy (agente) |
| Created | 2026-11-23 |
| Decided | 2026-11-23 |
| Decided by | Product owner (Jesús Álvarez) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-002 (§6, slider de métricas), DES-002 |
| Sections | SPEC-002 §6 (R21 y nota CR-079/CR-089); DES-002 (slider de gauges) |
| Current version → new version | `1.x.y → 1.x.z` (a fijar por el PO) |

## 2. Requested change

> Antes de implementar, revisar el texto actual de SPEC-002 §6 y DES-002 para el slider.

**Current:**

```text
En STREAMING la retícula de gauges (CR-076 v3, única instancia) entra como
hoja arrastrable sobre el video: nace ABIERTA (CR-079), se pliega y despliega
con gesto propio (asa, drag vertical, ajuste automático CR-089).
```

**Proposed:**

```text
En STREAMING la retícula de gauges entra como bottom sheet de Material
(BottomSheetBehavior) anclada abajo sobre el video, con asa Material y estados
expandido/colapsado del propio componente. El comportamiento de plegado
automático y el gate táctil CR-079/CR-082/CR-089 se retiran.
```

## 3. Motivation

El rework de UI de Android pide usar componentes de Android/Material en la barra
inferior de rendimiento. El gesto propio (CR-079 v1..v12) reimplementa lo que
`BottomSheetBehavior` ya resuelve y es la mayor fuente de complejidad táctil de
`MetricsSliderController`/`MetricsSliderContainer`.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-006 | yes | El requisito de slider se mantiene; cambia el mecanismo | update |
| SPEC-002 | yes | §6 y nota CR-079/CR-089 cambian | update vía este CR |
| DES-002 | yes | Gesto/asa/plegado del slider | update |
| TEST-0160, TEST-0172..0184 | yes | Tests del gesto propio (MetricsSliderTouchGateTest, MetricsSliderAnimationTest, MetricsSliderAutoFoldTest, MetricsSliderReleaseSnapTest, MetricsSliderFinalAdjustTest) dejan de aplicar | retirar o reemplazar con TEST nuevos, con fila en traceability |
| Code | yes | `MetricsSliderController`, `MetricsSliderContainer`, `fragment_session.xml` | reimplementar sobre BottomSheetBehavior |

## 5. Decision

Aprobado por el product owner el 2026-11-23. Se aplica en SPEC-002 §6 y DES-002, y los tests CR-079 se retiran o reemplazan con TEST IDs nuevos.

**Nota de aplicación:** el texto del slider de CR-079 no figura literalmente en SPEC-002 (su rastro está en `testing/traceability.md`, fila CR-079, «SPEC-002 §6 nota pendiente»). Por eso la SPEC no se reescribe a ciegas: el texto definitivo de la nota se redacta al aplicar el CR, y se revisa antes de cerrarlo.
