# Change Request (CR) — CR-016 — Twinpad

| | |
|---|---|
| ID | CR-016 |
| Title | Añadir monitores opcionales de orientación gyro en Android y Cemu |
| Type | New behavior |
| Status | Approved / in execution |
| Author | Product owner + Twinpad team |
| Created | 2026-10-22 |
| Decided | 2026-10-22 |
| Decided by | Product owner (usuario) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-002, SPEC-004, SPEC-006, DES-002, DES-004, testing/traceability.md |
| Sections | SPEC-002 outputs/inputs; SPEC-004 Cemu mapping/verification; SPEC-006 Cemu host configuration; DES-002/004 monitor realization |
| Current version → new version | SPEC-002 1.2.0 → 1.3.0; SPEC-004 1.3.0 → 1.4.0; SPEC-006 1.11.0 → 1.12.0; DES-002 0.2.0 → 0.3.0; DES-004 0.9.0 → 1.0.0 |

## 2. Requested change

**Current:** Android sends absolute yaw/pitch/roll as an `input_report` to Cemu, and Cemu maps it directly into VPAD orientation. Neither side has a live visual gyro monitor.

**Proposed:** Add a diagnostic gyro monitor to Android and Cemu, separately activated from settings and disabled by default. Android shows a rectangle with screen-relative yaw/pitch/roll input, axes and numeric values, updated as sensor orientation reports arrive. Cemu shows a rectangle with the yaw/pitch/roll values received in the input snapshot and the three mapped VPAD orientation axes, updated while input snapshots are consumed. Persist each application's opt-in preference locally; absent preference means disabled. The Cemu monitor renders on the main TV overlay only under CR-016; CR-017 later moves it to an external window, without adding a DSU motion packet or changing the session wire contract.

## 3. Motivation

The user needs to observe both the Android sensor input and the result after Cemu's axis mapping in real time to locate whether unexpected perception originates at capture, transport, or Cemu mapping. The existing `input_report` contract already transports absolute orientation; the monitor is diagnostic and does not alter it.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003/REQ-006 | No | Existing gyro input and Cemu adapter remain the source; diagnostics do not change capability | None |
| SPEC-002 | Yes | Specify Android monitor control, display and preference | Update via this CR |
| SPEC-004 | Yes | Specify received and mapped Cemu orientation diagnostics; preserve direct orientation semantics | Update via this CR |
| SPEC-006 | Yes | Specify Cemu host setting and overlay activation | Update via this CR |
| ARCH | No | Existing app and Cemu overlay boundaries suffice | None |
| DES-002/DES-004 | Yes | Identify Android custom view and Cemu Latte overlay/preferences | Update via this CR |
| TEST | Yes | Add TEST-078 Android monitor and TEST-079 Cemu monitor; retain TEST-077 orientation mapping test | Registered in traceability; Cemu GUI visual check requires manual run |
| Code | Yes | Android View/settings/sensor values; Cemu preference/menu/ImGui overlay and snapshot monitor data | Implement in this iteration |
| Data / migrations | Yes | Add local boolean preferences defaulting false; no wire/storage migration | Default false |
| Integrations / contracts | No | No protocol or DSU fields added | None |

## 5. Options considered

1. Add opt-in visual diagnostics on both endpoints — selected; separates what Android measures from what Cemu maps without changing input behavior.
2. Log only — does not let the user visually compare axes in real time.
3. Do nothing — leaves the source of orientation mismatch opaque.

## 6. Decision

- **Outcome:** Approved.
- **Rationale:** A separately activatable monitor in each UI, with per-app local persistence and a false default, provides the requested real-time comparison without altering gameplay inputs or protocol contracts. Android displays the orientation that it reports; Cemu displays received orientation and the post-mapping VPAD axes.
- **Rollout:** Same implementation iteration; no wire compatibility impact.

## 7. Execution checklist

- [x] Owner approves Android and Cemu gyro monitors, rectangle/axes, real-time values and disabled-by-default activation.
- [x] Owner chooses local persistence for the activation preference.
- [x] Update SPEC-002, SPEC-004, SPEC-006 and DES-002/DES-004.
- [x] Implement both monitors and the Cemu axis mapping.
- [x] Add TEST-077..079 and update traceability.
- [x] Android unit tests/debug assembly and Cemu debug build pass; Android instrumentation cannot run without a connected device; Cemu GUI overlay smoke check remains on-device/manual.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-22 | Product owner + Twinpad team | Opened and approved dual-endpoint gyro monitor behavior |
