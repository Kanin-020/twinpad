# Change Request — CR-036 — Continuidad del video de la Segunda pantalla al cambiar de escena

| | |
|---|---|
| ID | CR-036 |
| Title | Mantener el stream de video sin congelados ni tirones cuando el juego cambia de escena |
| Type | `Behavior change` |
| Status | `Open` |
| Author | Twinpad team (agente) |
| Created | 2026-10-28 |
| Decided | - |
| Decided by | - |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-003 (video), SPEC-006, DES-004, traceability |
| Sections | SPEC-003 cadencia y reinicio del encoder; SPEC-006 publicación de frames |
| Current version → new version | Propuesto; pendiente de versión |

## 2. Requested change

**Current:**

```text
PublishGamePadFrame publishes only when the game draws to the DRC target. EncodeGamePadFrame recreates the H.264 encoder when the measured cadence changes by more than 30% (2 s hysteresis), forcing an IDR and a visible cut.
```

**Proposed:**

```text
While the Cemu Plugin is connected, Cemu repeats the last published frame when no new DRC frame arrives within a bounded interval (value to be set by the approver). The encoder is not recreated for cadence changes alone; it is recreated only when the encoded resolution changes. Both rules apply to scene transitions only.
```

## 3. Motivation

The user reports stutter or freeze when the game changes scene (for example Wind Waker). Code evidence: `LatteRenderTarget.cpp` captures only when the game renders to the DRC target; `TwinpadClient.cpp` recreates the encoder on cadence changes. Measurement is still required: the `[TwinpadVideo]` counters have not been read during a transition, so the cause is not confirmed.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-004/005 | Maybe | Video continuity requirement | Confirm |
| SPEC-003 | Yes | Encoder restart and frame repeat rules | Update |
| SPEC-006 | Yes | Frame publication rule | Update |
| DES-004 | Yes | Cemu encoder and publish path | Update |
| TEST | Yes | Transition test and encoder recreation test | New TEST IDs |
| Code | Yes | TwinpadClient and LatteRenderTarget | Moderate |
| Integrations / contracts | No | No wire change | None |

## 5. Options considered

1. Repeat last frame on gaps and recreate the encoder only on resolution change — removes the cut without a new protocol.
2. Do nothing — the freeze and cut remain on scene changes.

**Open questions for the approver:**
- The repeat interval (not defined yet).
- Whether the cadence-based encoder recreation (CR-029/CR-030 era logic) must be kept.

## 6. Decision

- **Outcome:** pending.
- **Rationale:** -
- **Rollout:** -

## 7. Execution checklist

- [ ] Product owner approval recorded
- [ ] Measurement with `[TwinpadVideo]` counters during the transition
- [ ] Target document(s) updated
- [ ] Tests added/updated (TEST IDs)
- [ ] `testing/traceability.md` rows updated
- [ ] `_index.md` updated
- [ ] Code change merged

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-28 | Twinpad team (agente) | Opened |
