# Change Request — CR-013 — VPAD temporal automático de Twinpad en Cemu

| | |
|---|---|
| ID | CR-013 |
| Title | Crear VPAD temporal de Jugador 1 cuando Twinpad conecta |
| Type | `Clarification / Correction` |
| Status | `Approved` |
| Author | Twinpad team (implementación solicitada por el Product owner) |
| Created | 2026-10-19 |
| Decided | 2026-10-19 |
| Decided by | Product owner (usuario) |

> Aprobado por el Product owner al solicitar implementar que Cemu detecte Twinpad como Wii U GamePad ya configurado, sin depender de “Separate GamePad View”.

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-006, DES-004, traceability |
| Sections | SPEC-006 §7/§11/§16; DES-004 §3/§6/§12 |
| Current version → new version | SPEC-006 1.9.0 → 1.10.0; DES-004 0.7.0 → 0.8.0 |

## 2. Requested change

**Current:**

```text
CR-005 says connected Twinpad input is applied directly to Cemu's emulated VPAD. The adapter does not ensure a Player 1 VPAD exists if no controller/profile has instantiated one, so VPADRead may return an empty sample and titles that require an attached GamePad do not recognize Twinpad.
```

**Approved:**

```text
While the Cemu Plugin is connected, if Player 1 has no emulated VPAD, Cemu creates a temporary in-memory VPAD for Player 1. It applies Twinpad input through the existing VPAD path. A configured Player 1 VPAD is reused unchanged. The temporary VPAD is never written as a persistent controller profile; on disconnect it is removed, and any configured controller remains unchanged. “Separate GamePad View” is not opened or required; DRC capture behavior remains as approved by CR-008/CR-010.
```

## 3. Motivation

The current VPAD adapter maps Twinpad state only inside `VPADController::VPADRead`. Without an emulated Player 1 VPAD object, Cemu's `VPADRead` returns an empty sample; creating the object must therefore be part of the connection lifecycle. This is a correction to the implementation gap in CR-005's already approved direct-to-VPAD behavior, not a new input mapping or protocol.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-006 | No | Existing Cemu input integration remains | None |
| SPEC-006 | Yes | State the missing-VPAD lifecycle and persistence invariant | Update §7/§11/§16 |
| ARCH | No | No component boundary changes | None |
| DES-004 | Yes | Specify temporary VPAD ownership and cleanup | Update §3/§6/§12 |
| TEST-064 | Yes | Verify create-on-connect, no duplication, reuse of configured VPAD, cleanup and replacement safety | Add test and traceability row |
| Code | Yes | InputManager creates/removes a temporary Player 1 VPAD under its controller lock | Cemu integration |
| Data / migrations | No | No saved profile or schema change | None |
| Integrations / contracts | No | No wire/protocol changes | None |

## 5. Options considered

1. Create only a missing temporary VPAD, preserving any configured Player 1 controller and profile — completes CR-005's direct VPAD behavior without overwriting user settings.
2. Require manual profile setup — leaves the GamePad unavailable when the profile is absent and does not meet the requested plug-and-play behavior.

## 6. Decision

- **Outcome:** `Approved` by the Product owner on 2026-10-19.
- **Rationale:** the GamePad must be present to Wii U titles when Twinpad connects, but the user's existing Cemu controller profile must remain persistent and untouched. The adapter owns the temporary object only for its live connection.
- **Rollout:** experimental Cemu adapter; no changes to wire, SDK, server or Android behavior.

## 7. Execution checklist

- [x] Product owner approval recorded
- [x] SPEC-006 and DES-004 updated with lifecycle and persistence rules
- [x] TEST-064 assigned and registered in `docs/testing/traceability.md`
- [x] Temporary VPAD lifecycle implemented without writing profiles
- [x] Cemu debug build (`Cemu_debug`) succeeds; `InputManager_twinpad_vpad_test` is compiled into the debug unit-test entry point
- [ ] Execute TEST-064 assertions in the debug app and confirm Player 1 recognition in a physical title (existing Cemu instance/title must be safely smoke-tested)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 1.0.0 | 2026-10-19 | Product owner + Twinpad team | Approved automatic temporary Player 1 VPAD when missing, preserving existing assignments/profiles |
