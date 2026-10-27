# Change Request (CR) — CR-025 — Twinpad

| | |
|---|---|
| ID | CR-025 |
| Title | Modo de escalado de la Segunda pantalla: `contain` (letterbox) y `expand` (estirar), con mapeo táctil coherente |
| Type | `Behavior change` |
| Status | `Applied` |
| Author | Product owner + Twinpad team |
| Created | 2026-10-27 |
| Decided | 2026-10-27 |
| Decided by | Product owner (aprobación explícita en esta iteración) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-002 §5/§11, SPEC-003 §5/§11, `testing/traceability.md` |
| Sections | Presentación de la Segunda pantalla en Android; mapeo táctil sobre la imagen DRC |
| Current version → new version | SPEC-002 `1.3.1 → 1.4.0`; SPEC-003 `1.5.0 → 1.6.0` |

## 2. Requested change

**Current:**

```text
SPEC-003 §5: el SurfaceView presenta el frame decodificado; §8.1/§11 indican que
el SurfaceView reescala al cambiar orientación. No hay modo de escalado elegible.
El contenedor de video ocupa todo el espacio disponible (equivalente a expand).
El toque se normaliza contra el contenedor completo, incluidas las zonas fuera de la imagen.
```

**Proposed:**

```text
SPEC-002 R12 (CR-025): en ajustes del drawer, la Segunda pantalla ofrece dos modos
de escalado persistidos localmente:
  - contain: conserva la relación de aspecto del video del stream y deja barras
    negras en los bordes sobrantes (letterbox).
  - expand: estira el video para ocupar todo el contenedor (sin barras, puede
    deformar la imagen).
El valor por defecto es expand, que coincide con el comportamiento actual.

SPEC-002 R13 (CR-025): el mapeo táctil usa el rectángulo real donde se dibuja el
video. En contain, los toques sobre las barras negras se ignoran (no envían touch
ni cambian touch_pressed). En expand, el rectángulo es el contenedor completo.
Las coordenadas normalizadas 0…65535 (SPEC-004) siempre cubren exactamente la imagen.

SPEC-003 §11 R9 (CR-025): el modo de escalado es presentación local de Android;
no cambia resolución, fps, codec ni el wire contract. Al cambiar el modo, el
SurfaceView se relayout sin renegociar el stream ni reiniciar el decoder.
```

## 3. Motivation

El owner pidió poder mostrar la proyección con relación de aspecto preservada (contain) o ocupando toda la pantalla (expand), y que el toque responda solo sobre la imagen visible. Hoy el toque se normaliza contra todo el contenedor, de modo que en contain los toques en las barras caerían fuera de la imagen DRC.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-002 / REQ-003 | no | No fijan modo de escalado ni geometría de la imagen | none |
| SPEC-002 | yes | Nuevo ajuste persistente y regla de mapeo táctil | añadir R12/R13, versión y changelog |
| SPEC-003 | yes | Presentación local del SurfaceView | añadir R9 y actualizar §11/changelog |
| SPEC-004 | no | Rango normalizado 0…65535 sin cambios; solo cambia qué toques se emiten | none (verificar en test) |
| ARCH | no | Sin cambios de componentes | none |
| DES-002 | yes | Cálculo del rectángulo de video y letterbox | actualizar §1/§12 (nota en changelog) |
| TEST-xxx | yes | Nuevos tests unitarios del cálculo de rectángulo y del mapeo | añadir filas en traceability |
| Code | yes | Selector en drawer, persistencia, cálculo de rectángulo y mapeo táctil en Android | PR en `android/app` |
| Data / migrations | no | Clave nueva en SharedPreferences con valor por defecto `expand` | none |
| Integrations / contracts | no | Wire y Cemu sin cambios | none |

## 5. Options considered

1. `contain` y `expand` como ajuste local con toque filtrado al rectángulo de video — `cumple la petición sin tocar el wire`.
2. Do nothing — la proyección sigue estirada y el toque ignora la geometría visible.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** El owner eligió "estirar" para expand y "ignorar toques en las barras" para contain. El cambio es de presentación local de Android.
- **Rollout:** same release

## 7. Execution checklist

- [x] Target document(s) updated (version bumped, changelog entry references this CR ID)
- [x] Dependent documents updated (from impact table)
- [x] Tests added/updated (TEST IDs)
- [x] `testing/traceability.md` rows updated (including CR history table)
- [x] Indexes (`_index.md`) updated
- [x] Code change merged (if any)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-27 | Product owner + Twinpad team | Opened |
| 1.0.0 | 2026-10-27 | Product owner | Approved (contain/expand y mapeo táctil) |
| 1.1.0 | 2026-10-27 | Twinpad team | Applied: TEST-086 y código Android (selector en drawer, letterbox, táctil sobre la imagen visible) |
