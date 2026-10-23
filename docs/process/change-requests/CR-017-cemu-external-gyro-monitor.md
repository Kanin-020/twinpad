# Change Request (CR) — CR-017 — Twinpad

| | |
|---|---|
| ID | CR-017 |
| Title | Mover el monitor gyro de Cemu a una ventana externa ampliada |
| Type | Behavior change |
| Status | Approved / in execution |
| Author | Product owner + Twinpad team |
| Created | 2026-10-22 |
| Decided | 2026-10-22 |
| Decided by | Product owner (usuario, solicitud directa) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-004, SPEC-006, DES-004, testing/traceability.md |
| Sections | SPEC-004 §10/§16; SPEC-006 §7/§11/§12/§16; DES-004 §3/§8/§12; TEST-079 |
| Current version → new version | SPEC-004 1.4.0 → 1.5.0; SPEC-006 1.12.0 → 1.13.0; DES-004 1.0.0 → 1.1.0 |

## 2. Requested change

**Current:** Cemu draws the optional gyro monitor as a 280×180 pixel overlay on the TV/game rendering surface.

**Approved:** replace that in-game overlay with a separate, resizable Cemu diagnostic window. Its initial client size is 640×480 and its minimum client size is 480×360. It shows the received yaw/pitch/roll values and mapped VPAD axes in a larger orientation diagram, refreshing from the latest snapshot. The existing local “Monitor de giroscopio” preference continues to control visibility and remains disabled by default. Enabling it opens the window; disabling it closes the window. Closing the window directly also disables and persists that preference. With no valid gyro snapshot, the window remains open and indicates that it is waiting for gyro input. The monitor does not render in the game/TV or GamePad surface and does not change game input or wire behavior.

## 3. Motivation

The owner cannot easily see the small Cemu TV overlay and requests an external, larger window for the same diagnostic data. A standalone resizable window is visible independently of the game framebuffer and can be made larger when needed.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003/REQ-006 | No | Existing gyro input and host diagnostic capability remain unchanged | None |
| SPEC-004 | Yes | Replace Cemu overlay presentation with a separate diagnostic window | Update via this CR |
| SPEC-006 | Yes | Specify Cemu preference/window lifecycle and no-input state | Update via this CR |
| ARCH | No | Existing Cemu wxWidgets host UI boundary suffices | None |
| DES-004 | Yes | Add a wxWidgets diagnostic frame and connect it to the existing preference | Update via this CR |
| TEST-079 | Yes | Extend GUI acceptance to verify separate window, sizing, live values and preference lifecycle | Update traceability and manual GUI check |
| Code | Yes | Replace Latte overlay drawing with a resizable host diagnostic frame | Implement in this iteration |
| Data / migrations | No | Reuse the existing local preference | None |
| Integrations / contracts | No | No protocol or wire fields change | None |

## 5. Options considered

1. Replace the TV overlay with a separate resizable window — selected; it is easier to see and can be resized without covering gameplay.
2. Keep the in-game overlay and only enlarge it — still covers game output and remains tied to the TV surface.
3. Do nothing — leaves the diagnostic too small for the owner to read comfortably.

## 6. Decision

- **Outcome:** Approved.
- **Rationale:** The user directly requested an external, larger Cemu gyro monitor. A resizable 640×480 initial window, controlled by the existing opt-in preference and fed by the same latest input snapshot, satisfies that request without changing game inputs or the session contract.
- **Rollout:** Same implementation iteration; no wire or stored-data migration.

## 7. Execution checklist

- [x] Owner requests an external, larger Cemu gyro monitor.
- [x] Update SPEC-004, SPEC-006, DES-004 and their indexes/changelogs.
- [x] Extend TEST-079 traceability and GUI acceptance criteria.
- [x] Implement the external wxWidgets window and existing preference lifecycle.
- [x] Run the Cemu debug build and whitespace checks; GUI visual smoke check remains pending in this environment.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-22 | Product owner + Twinpad team | Opened and approved external, enlarged Cemu gyro monitor behavior |
