# Change Request (CR) — CR-088 — Twinpad

> **What this is:** a controlled, reviewable change to an approved artifact. One CR per logical change.

| | |
|---|---|
| ID | CR-088 |
| Title | Quitar de la vista de espera el texto «Juego compatible: …» (aparecía como «Juego compatible: Cemu (experimental)» al vincular) |
| Type | `Behavior change` |
| Status | `Applied (2026-11-06, code-applied; actualización de SPEC-002 pendiente)` |
| Author | Buffy (agente), instrucción directa del owner |
| Created | 2026-11-06 |
| Decided | 2026-11-06 |
| Decided by | Owner («Cuando se hace una conexión se muestra el texto de Juego detectado experimiental cemu, puedes eliminar completamente ese texto?») |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `SPEC-002` (§11 R14, §5 salidas de la Pantalla de Pairing) |
| Sections | §11 R14 |
| Current version → new version | `1.13.0 → pendiente` |

## 2. Requested change

**Current:**

```text
Al recibir `games_available` la app escribe en `txt_games` (dentro de la
hoja de métricas, visible en V0) «Juego compatible: <game_name>» con un
plugin vivo, o «Juegos compatibles: <a>, <b>» con varios. Cemu anuncia
`game_name = "Cemu (experimental)"`, así que al vincular se lee
«Juego compatible: Cemu (experimental)».
```

**Proposed:**

```text
La app no muestra ningún texto con el/los juegos detectados. Se elimina
`txt_games` del layout, la observación de `availableGames` que lo
rellenaba y los strings `game_compatible_one`/`game_compatible_many`.
La selección de fuente no cambia: con varios plugins sigue apareciendo
el diálogo «Selecciona el juego» (SPEC-002 §6, R8) y con uno solo se
auto-selecciona como hasta ahora.
```

## 3. Motivation

Pedido del owner: al vincular, la vista de espera muestra «Juego compatible:
Cemu (experimental)» y no debe mostrarse ese texto. No aporta información
accionable (la fuente ya se selecciona sola o por diálogo) y el sufijo
«experimental» del `game_name` de Cemu (CR-004) no es relevante para el
usuario final.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004 | no | — | none |
| REQ-006 | no | El `game_name` del anuncio no cambia | none |
| SPEC-002 | yes | R14 nombra «la lista de juegos compatibles» al volver a V0 | update pendiente (owner) |
| SPEC-006 | no | `games_available`/`game_name` siguen igual en el wire | none |
| ARCH | no | — | none |
| DES-002 | no | — | none |
| TEST-053/055 | no | Cubren selección y liveness, no el texto | none |
| Code | yes | `activity_pairing.xml`, `PairingActivity.kt`, `strings.xml`; comentarios de `MetricsSliderController`/`MetricsSliderContainer` | aplicado |
| Data / migrations | no | — | none |
| Integrations / contracts | no | Wire sin cambios | none |

## 5. Options considered

1. Quitar solo el sufijo «(experimental)» del anuncio de Cemu — la línea seguiría ocupando la vista de espera y el owner pidió eliminar el texto completo.
2. Quitar la línea completa (propuesto) — la selección de fuente y el diálogo de varios plugins no cambian.
3. Do nothing — el texto sigue apareciendo en cada vinculación.

## 6. Decision

- **Outcome:** `Approved`
- **Rationale:** el owner pidió eliminar el texto por completo; la información de fuente activa ya está cubierta por la auto-selección y el diálogo de selección.
- **Rollout:** misma iteración, solo app Android.

## 7. Execution checklist

- [ ] Target document(s) updated (SPEC-002 R14, versión y changelog con este CR)
- [x] Dependent documents updated (ninguno)
- [x] Tests added/updated (sin tests nuevos: ningún test verificaba el texto)
- [x] `testing/traceability.md` rows updated (historia de cambios)
- [ ] Indexes (`_index.md`) updated
- [x] Code change merged (if any)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-11-06 | Buffy (agente) | Abierto y aplicado en código: sin texto de juego compatible en la vista de espera |
