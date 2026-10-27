# Change Request (CR) — CR-026 — Twinpad

| | |
|---|---|
| ID | CR-026 |
| Title | Pantalla limpia durante la transmisión: sin marco de la superficie de controles ni textos superpuestos |
| Type | `Behavior change` |
| Status | `Applied` |
| Author | Product owner + Twinpad team |
| Created | 2026-10-27 |
| Decided | 2026-10-27 |
| Decided by | Product owner (aprobación explícita en esta iteración) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-002 §11, SPEC-003 §6 |
| Sections | Presentación de la Segunda pantalla: textos de estado y lista de juegos compatibles; marco dibujado por la superficie de controles |
| Current version → new version | SPEC-002 `1.4.0 → 1.5.0`; SPEC-003 `1.6.0 → 1.7.0` |

## 2. Requested change

**Current:**

```text
La superficie táctil de controles dibuja además un marco translúcido (fondo
"gamepad shell") que cubre ~96% del ancho y ~70% del inferior de la pantalla,
incluso cuando no hay controles configurados o "Mostrar botones" está apagado.
Durante V1 (streaming) y V2 (recuperando) la app muestra "Segunda pantalla" y
"Juego compatible: {game}" sobre la imagen.
```

**Proposed:**

```text
SPEC-002 R14 (CR-026): durante la presentación del stream de video (V1/V2 de
SPEC-003) la app no muestra ningún texto superpuesto sobre la imagen ni el marco
de la superficie de controles. El mensaje de estado ("Sin segunda pantalla
activa") y la lista de juegos compatibles reaparecen cuando la vista vuelve a
un estado sin stream (V0). El chip HUD y los controles editables siguen sus
reglas propias (preferencia local y SPEC-008); este CR no los altera.

SPEC-003 §6 (CR-026): la imagen de la Segunda pantalla se presenta sin texto
superpuesto en V1 y V2; solo V0 muestra el mensaje de estado inactivo.
```

## 3. Motivation

El owner reportó que durante la transmisión se ve "un rectángulo sobre la pantalla que no debería mostrarse" (el marco translúcido del shell, dibujado siempre por el renderizador aunque no haya nada que presentar) y los textos "Segunda pantalla" y "Juego compatible" sobrepuestos al video, pidiendo que no se muestre nada durante la transmisión en curso.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| SPEC-002 | yes | Regla de presentación sin textos superpuestos (R14) | añadir R14, versión y changelog |
| SPEC-003 | yes | Presentación sin texto en V1/V2 | nota en §6, versión y changelog |
| SPEC-008 | no | Los controles táctiles siguen dibujándose según perfil; solo el fondo extra desaparece | none |
| REQ-002 / REQ-004 / REQ-005 | no | No cambian AC de pairing, video/audio | none |
| Wire / Cemu / Plugin | no | Cambios solo de presentación local de Android | none |
| TEST-xxx | no | Ningún test existente cubre el shell ni los textos de V1 | none |
| Code | yes | `ProfileSurfaceRenderer` (sin shell) y visibilidad de `txt_session_message`/`txt_games` en `PairingActivity` | PR en `android/app` |

## 5. Options considered

1. Eliminar el shell y ocultar los textos solo durante V1/V2 — pantalla limpia exactamente cuando hay imagen; texts regresan en V0.
2. Ocultar el HUD completo — rechazado: el chip fps y los controles editables tienen sus reglas y preferencias propias, fuera de esta petición.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** El owner lo pidió explícitamente en esta iteración ("NO debe mostrarse nada" durante la transmisión). Es presentación local de Android; no toca wire ni contratos.
- **Rollout:** same release

## 7. Execution checklist

- [x] Target document(s) updated (version bumped, changelog entry references this CR ID)
- [x] Dependent documents updated (from impact table)
- [x] Tests added/updated (no new TEST IDs: presentación visual sin comportamiento de input; gates ktlint/detekt/tests en verde)
- [x] `testing/traceability.md` rows updated (n/a: sin filas nuevas)
- [x] Indexes (`_index.md`) updated
- [x] Code change merged (if any)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-27 | Product owner + Twinpad team | Opened |
| 1.0.0 | 2026-10-27 | Product owner | Approved (pantalla limpia durante la transmisión) |
| 1.1.0 | 2026-10-27 | Twinpad team | Applied: shell retirado del renderizador y textos ocultos en V1/V2 |
