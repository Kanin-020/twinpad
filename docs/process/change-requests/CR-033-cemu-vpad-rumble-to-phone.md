# Change Request — CR-033 — Vibración del VPAD de Cemu hacia el teléfono

| | |
|---|---|
| ID | CR-033 |
| Title | Enviar los patrones de `VPADControlMotor` de Cemu al teléfono para vibrar |
| Type | `New behavior` |
| Status | `Applied` |
| Author | Twinpad team (agente) |
| Created | 2026-10-28 |
| Decided | 2026-10-28 |
| Decided by | Product owner |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-006, SPEC-002 (ajuste de vibración en Android), DES-004, ARCH (contrato de mensaje), traceability |
| Sections | SPEC-006 (mensajes Cemu → Android); SPEC-002 (vibración del teléfono); DES-004 |
| Current version → new version | SPEC-006 1.14.0 → 1.15.0 (propuesto); SPEC-002 → propuesto |

## 2. Requested change

**Current:**

```text
Cemu's VPADControlMotor calls InputManager::push_rumble, which fills a local queue. Nothing is sent to the phone. Android has no vibration code. The phone does not vibrate when the game requests it.
```

**Proposed:**

```text
While the Cemu Plugin is connected, each accepted VPADControlMotor pattern for Player 1 is sent to Android as a new Cemu → Android message `rumble` carrying the pattern bytes and length (max 120 bytes, as in vpad.cpp). A length of 0 is sent as a stop request. Android plays the pattern with the platform Vibrator at a fixed 60 Hz bit cadence, matching the existing Cemu pattern decoding. If the device has no vibrator, the message is ignored. The message is not persisted.
```

## 3. Motivation

The game requests vibration through `VPADControlMotor`, and Cemu decodes it, but the phone never receives it. This is a gap between the approved VPAD input path (CR-005, CR-013) and the phone. Evidence: `integrations/cemu/src/Cafe/OS/libs/vpad/vpad.cpp` (`vpadExport_VPADControlMotor`) and `VPADController::push_rumble`; no vibration code in `android/`.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-006 | Yes (to confirm) | Vibration is a new capability of the VPAD integration | Confirm whether REQ-006 covers it |
| SPEC-006 | Yes | New message type and its payload | Add message to §protocol |
| SPEC-002 | Yes | Phone vibration behavior | Add rule |
| ARCH | Maybe | New Cemu → Android message | Update contracts list |
| DES-004 | Yes | Cemu hook that forwards pattern | Update |
| TEST | Yes | Encode/decode test for pattern and stop | New TEST ID + traceability row |
| Code | Yes | Cemu hook, plugin message, Android Vibrator handler | Moderate |
| Integrations / contracts | Yes | New message on the plugin IPC | Version bump |

## 5. Options considered

1. Forward each accepted pattern as a `rumble` message; Android plays it — matches what the game asked for.
2. Do nothing — the phone never vibrates for Wii U games.

**Open questions for the approver:**
- Should the phone vibrate only when the game asks (proposed), or should it also apply a fixed intensity?
- Is `REQ-006` the right requirement to extend?

## 6. Decision

- **Outcome:** approved and applied (SPEC-006 1.17.0).
- **Rationale:** the game requests vibration through `VPADControlMotor`; the phone must receive it.
- **Rollout:** server queues rumble per game (bounded), Cemu sends accepted patterns for Player 1, Android plays with `VibratorManager` at 60 Hz.

## 7. Execution checklist

- [x] Product owner approval recorded
- [x] Target document(s) updated
- [x] Tests added/updated (TEST IDs)
- [x] `testing/traceability.md` rows updated
- [x] `_index.md` updated
- [x] Code change merged

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-28 | Twinpad team (agente) | Opened |
| 1.0.0 | 2026-10-28 | Twinpad team (agente) | Applied: SPEC-006 1.17.0, TEST-092 |
