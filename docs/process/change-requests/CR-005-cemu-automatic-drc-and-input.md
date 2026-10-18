# Change Request — CR-005 — Flujo experimental Cemu: conexión, DRC e inputs

| | |
|---|---|
| ID | CR-005 |
| Title | Flujo experimental Cemu: conexión, DRC e inputs |
| Type | `Behavior change` |
| Status | `Approved` |
| Author | Twinpad team (propuesta solicitada por el owner) |
| Created | 2026-10-18 |
| Decided | 2026-10-18 |
| Decided by | Product owner (usuario) |

> Aprobado por el Product owner el 2026-10-18, incluidas las decisiones enumeradas en §6. Autoriza actualizar los contratos y realizar la implementación experimental descrita.

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-004, SPEC-006, ARCH, DES-004; REQ-003/004/006 y SPEC-003 no cambian sus contratos normativos |
| Sections | REQ-003 §2/§5/§6; REQ-004 §2/§5/§6; REQ-006 §2/§5/§6; SPEC-003 §3/§7/§8/§17; SPEC-004 §3/§4/§7/§12; SPEC-006 §3/§4/§7/§9/§11; ARCH §2/§3/§4/§6 |
| Current version → new version | SPEC-004 1.0.0 → 1.1.0; SPEC-006 1.2.0 → 1.3.0; ARCH draft 0.2.0 → 0.3.0; DES-004 0.1.0 |

## 2. Requested change

**Current — documented behavior:**

- `SPEC-006 §7`: Cemu sends an announcement; its GamePad texture capture is opt-in and limited to 5 fps.
- `CR-004`: the Cemu adapter connects to local IPC `127.0.0.1:8421` and `:8422`; the capture is enabled from Cemu's menu.
- `SPEC-003 §17`: desktop duplication is the current fallback when no game/plugin source exists.
- `SPEC-004 §17`: app `input_report` uses the authenticated PC session. The Cemu adapter does not currently consume those reports.
- Current implementation additionally requires the user to connect from Cemu's Twinpad menu. If the authenticated app session starts before Cemu has published its first GamePad frame, the server selects its desktop/demo source for that session rather than waiting for the later DRC frame.

**Approved — behavior:**

```text
Cemu lifecycle:
- When a Cemu title starts, its Twinpad adapter attempts to connect to the already-running PC Server on localhost. It does not connect from the game list.
- If the PC Server is not listening yet, Cemu retries every 2 seconds while the title remains active; if the server restarts, the adapter reconnects and announces again. Leaving/ending the title stops the connection and capture; a later title start retries.
- Once connected, Cemu announces itself and enables its GamePad/DRC framebuffer capture automatically, retaining the CR-004 limit of at most 5 fps and its existing audio behavior.

Video source:
- The normal Twinpad stream uses a live game/plugin framebuffer (for Cemu, the actual DRC/GamePad view).
- Preserve the existing SPEC-003 fallback behavior: desktop capture, then demo pattern if desktop capture is unavailable, while no game/plugin frame source is available. A DRC frame that exists when source selection occurs is streamed normally.
- This approval does not add dynamic source switching for a DRC frame that appears after the session selected its initial fallback; that limitation is explicit and remains unchanged.

Inputs:
- The app's existing default touch profile remains the starting layout.
- The authenticated app session forwards valid `input_report` state to the connected Cemu adapter over its primary localhost NDJSON connection; the server accepts controls from the first session to send input until that session disconnects.
- Cemu applies the report directly to its emulated Wii U GamePad/DRC (not to a separate PC operating-system gamepad): buttons A/B/X/Y, L1/R1, L3/R3, Start/Select map naturally; the two extra buttons are ignored; L2/R2 activate ZL/ZR whenever their analog value is greater than zero; sticks and D-pad map directly; gyro orientation and DRC touch map directly. Touch coordinates are normalized uint16 values; released reports retain the last coordinates. While connected, this Twinpad state is the exclusive VPAD input source; local controller assignments are ignored until disconnect.
```

The owner approved retry every 2 seconds only while a title is active, natural mapping with extra buttons ignored and binary-active ZL/ZR triggers, and the existing video fallback without late source switching. The approval also adds `touch_x`, `touch_y`, and `touch_pressed` to `input_report`: normalized 0…65535 coordinates and contact state; coordinates remain at the last position when released.

## 3. Motivation

The owner reports that Cemu currently shows “Could not connect to Twinpad server at 127.0.0.1:8421/8422” and requests a flow in which the mobile device is Pairing-linked, Cemu connects automatically when opened, the app's controls operate Cemu's emulated GamePad, and the phone receives the actual second-screen DRC image instead of a desktop cast. Code inspection confirms that the server is a separate process (`scripts/run-server.sh`), Cemu's connection is user-triggered in its Twinpad menu, DRC capture is opt-in, and session `input_report` messages are only decoded/sequence-filtered by the server. The server also chooses desktop/demo if no game frame exists at session startup.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-003 | No | CR-005 is an experimental adapter exception; REQ-003 generic Bluetooth HID scope is unchanged | None |
| REQ-004 | No | Existing desktop/demo fallback is expressly retained | None |
| REQ-006 | Yes | Cemu auto-announcement/retry and DRC capture lifecycle change from menu/opt-in to automatic | Update Cemu exception and failure/liveness behavior |
| SPEC-003 | No | Current fallback and source selection remain unchanged; delayed source switching is excluded | None |
| SPEC-004 | Yes | Define forwarding path for app reports to Cemu and the target controls | Resolve mapping and transport contract; add verification |
| SPEC-006 | Yes | Auto-connect, retry, auto-capture, and Cemu input capability | Update flow, errors and rules after agreement |
| ARCH | Yes | Adapter becomes bidirectional for app inputs and Cemu; clarify boundaries and runtime components | Update component, communication and failure semantics |
| SPEC-008 | Potentially | Existing generic profile can be reused; a Cemu-specific profile/auto-selection is not required by this proposal | Confirm profile-selection behavior; no new layout proposed yet |
| DES-004 | Yes | Title lifecycle, retry, bidirectional IPC, and Wii U input mapping | Created as Draft |
| TEST | Yes | Verify startup with/without server, retry/reconnect, delayed DRC source, no desktop leakage, and input mapping | Define TEST IDs and traceability rows after approval |
| Code | Yes | Cemu title lifecycle/client/VPAD and server session/IPC input forwarding; no server source-selection or Android display behavior change | Implement per approved CR-005 |
| Data / migrations | No | No persistence or schema migration proposed | None |
| Integrations / contracts | Yes | Reuse existing `input_report` semantics but make the Cemu IPC path bidirectional; preserve IPC ports and frame/audio payload contracts unless separately approved | Specify direction, framing, sequencing, and failure semantics |

## 5. Options considered

1. Adopt the proposed automatic Cemu-to-server flow, real DRC-only normal stream, and direct app-input mapping — matches the requested test experience and avoids mistaking desktop content for a game's second screen.
2. Keep CR-004 behavior — requires manual Cemu connection/capture and may show desktop content; does not deliver app controls into Cemu.

## 6. Decision

- **Outcome:** `Approved` by the Product owner on 2026-10-18.
- **Rationale:** The owner chose title-scoped retry and connection, explicit natural DRC mappings, the existing desktop/demo video fallback, and extension of the input wire with touch fields. Late DRC source switching is intentionally not included.
- **Rollout:** Local experimental Cemu build only; no general emulator support is implied.

### Approved decisions

1. Connect/retry every 2 seconds only while a title is active; stop on title exit and reconnect after server restart.
2. Map buttons A/B/X/Y, L1/R1, L3/R3 and Start/Select naturally; ignore extra buttons; map L2/R2 to binary ZL/ZR when value > 0; map sticks, D-pad, gyro and touch directly.
3. Preserve the current SPEC-003 desktop capture/demo fallback; no late source switching is added by this CR.
4. Begin connecting only after a title starts, not from Cemu's game list.
5. Extend `input_report` with `touch_x`, `touch_y` (uint16 normalized 0…65535) and `touch_pressed` (boolean); released reports retain the last coordinates.
6. While connected, Twinpad exclusively controls the emulated VPAD; do not combine with local controller mappings.

## 7. Execution checklist

- [x] Owner resolves the decisions above and approves this CR
- [ ] Target REQs/SPECs/ARCH updated with versions and CR-005 changelog entries
- [ ] Cemu/server design artifact created or updated
- [ ] TEST IDs assigned and registered in `testing/traceability.md`
- [ ] Existing desktop/demo fallback is preserved; late source switching is not part of this CR
- [ ] Cemu title-scoped auto-connect/retry and automatic DRC capture implemented
- [ ] App input is forwarded and applied to Cemu's emulated Wii U GamePad per the approved mapping
- [ ] Affected unit, loopback, Android, Cemu build, and end-to-end checks pass

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-18 | Twinpad team | Open proposal based on owner-requested Cemu behavior; awaiting decisions and approval |
| 1.0.0 | 2026-10-18 | Product owner + Twinpad team | Approved title-scoped connection, 2 s retry, direct DRC input mapping, existing video fallback, and touch fields in `input_report` |
| 1.0.1 | 2026-10-18 | Product owner + Twinpad team | Clarifies that REQ-003/004 and SPEC-003 behavior is unchanged; DES-004 is assigned; control source is exclusive Twinpad while connected |
