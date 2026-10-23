# Change Request (CR) — CR-018 — Twinpad

| | |
|---|---|
| ID | CR-018 |
| Title | Alinear valores y colores de los monitores gyro |
| Type | Correction |
| Status | Approved / in execution |
| Author | Product owner + Twinpad team |
| Created | 2026-10-22 |
| Decided | 2026-10-22 |
| Decided by | Product owner (usuario) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-002, SPEC-004, DES-002, DES-004, testing/traceability.md |
| Sections | SPEC-002 §5/§11/§16; SPEC-004 §10/§16; DES-002 §3/§4/§12; DES-004 §3/§12; TEST-078/080 |
| Current version → new version | SPEC-002 1.3.0 → 1.3.1; SPEC-004 1.5.0 → 1.5.1 (clarification); DES-002 0.3.0 → 0.3.1; DES-004 1.1.0 → 1.1.1 |

## 2. Requested change

**Current:** Android's monitor displays sensor-derived absolute yaw/pitch/roll, but `ProfileGyroController` calibrates by subtracting a reference and emits a delta. Cemu therefore receives values different from Android's display. The Android view colors yaw green, pitch red, roll blue, while Cemu's monitor labels the different VPAD axes with a mismatching color assignment.

**Approved:** Android sends the same absolute screen-relative yaw/pitch/roll values displayed in its monitor, clamped only to the int16 wire range. Both monitor diagrams use matching semantic colors: yaw green, pitch red, and roll blue. Cemu's diagram uses the raw received yaw/pitch/roll independently of VPAD's axis order; Cemu's VPAD mapped values remain displayed separately. At near-zero roll, including the captured values `-212` and `-262`, the roll line points right like Android.

## 3. Motivation

The owner observed Android displaying yaw -7375, pitch -331, roll 12 while Cemu showed yaw -3286, pitch -897, roll -701. The Android-side hidden calibration transform is inconsistent with SPEC-004 §4, which defines an absolute orientation report. Mismatched axis colors add ambiguity while comparing the endpoints.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003 | No | No capability change | None |
| SPEC-002/004 | Yes | Specify the absolute Android orientation report and common semantic monitor palette | Clarify by this CR |
| ARCH | No | Existing boundaries suffice | None |
| DES-002/DES-004 | Yes | Remove calibration subtraction in the Android gyro path and align monitor axis colors | Update realization |
| TEST-078/080 | Yes | Verify semantic palette and exact absolute report values | Update/register tests |
| Code | Yes | Remove the hidden offset/sensitivity transform; align Cemu diagram's axis colors | Implement in this iteration |
| Data / migrations | No | No persisted data changes | None |
| Integrations / contracts | No | Existing int16 yaw/pitch/roll wire shape is unchanged | None |

## 5. Options considered

1. Send absolute screen-relative sensor angles and use the same semantic axis palette on both endpoints — selected; matches SPEC-004 and permits direct monitor comparison.
2. Calibrate the Android monitor to show the transformed report — rejected; it would misrepresent the screen-relative sensor orientation already specified by SPEC-002/CR-016.
3. Do nothing — leaves the mismatch and color ambiguity unresolved.

## 6. Decision

- **Outcome:** Approved.
- **Rationale:** SPEC-004 §4 defines gyro as absolute yaw/pitch/roll and SPEC-002/CR-016 shows the sensor-derived screen-relative orientation. Reporting it unchanged makes Android and Cemu values directly comparable; semantic colors clarify axes without changing the wire format or Cemu VPAD mapping.
- **Rollout:** Same iteration; wire format and stored data remain unchanged.

## 7. Execution checklist

- [x] Owner approves matching values and axis colors.
- [x] Remove Android calibration offset and sensitivity from the input report path.
- [x] Use yaw green, pitch red, roll blue in both monitor diagrams.
- [x] Remove the 90° display-only roll offset from Cemu; zero roll now points right like Android, with TEST-079 assertions.
- [x] Decouple Cemu's monitor angle calculation from VPAD-axis remapping so each displayed line uses its matching received yaw/pitch/roll value; assert the captured near-zero roll values from Cemu/Android screenshots in TEST-079.
- [x] Remove the extra negation on Cemu's mapped VPAD pitch and update TEST-077 to assert positive-pitch sign preservation.
- [x] Add TEST-080, update TEST-078 assertions and complete SPEC/DES/traceability/index updates.
- [x] Android unit tests and ktlint pass; Cemu debug build and whitespace checks pass. Android instrumentation is not run in this environment.
- [x] Rebuild Cemu after the pitch-sign correction; TEST-077 assertions are included in the debug test entrypoint.
- [x] Rebuild Cemu after the roll-diagram alignment and verify TEST-079 assertions in the debug test entrypoint.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-22 | Product owner + Twinpad team | Opened and approved gyro monitor value/color correction |
