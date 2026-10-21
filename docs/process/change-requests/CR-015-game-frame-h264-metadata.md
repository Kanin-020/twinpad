# Change Request (CR) — CR-015 — Twinpad

| | |
|---|---|
| ID | CR-015 |
| Title | Añadir metadata de access units H.264 al IPC Plugin |
| Type | Correction |
| Status | Approved / in execution |
| Author | Product owner + Twinpad team |
| Created | 2026-10-20 |
| Decided | 2026-10-20 |
| Decided by | Product owner (usuario) |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-006, DES-004, testing/traceability.md |
| Sections | SPEC-006 §§4, 7, 12, 16; DES-004 IPC contract |
| Current version → new version | SPEC-006 1.10.0 → 1.11.0 |

## 2. Requested change

**Current:** `game_frame` has fields `gfn`, `w`, `h`, `fmt`, `data`; its H.264 IPC payload does not identify capture timestamp or IDR status.

**Approved:** for `fmt:"h264"`, require `ts_capture_us:u64` (monotonic timestamp from encoder origin) and `idr:boolean` (true iff this Annex B access unit is IDR). For all other formats, both fields are absent. The SDK and Cemu IPC emit these fields; the server validates and relays them in the encrypted SPEC-003 payload without substituting a receive-time timestamp.

## 3. Motivation

SPEC-003 §4.3 requires the server-to-Android plaintext to contain encoder-origin `ts_capture_us` and an IDR flag. The existing SPEC-006 IPC contract omits both, so a conforming relay cannot be implemented without extending that contract. The owner approved this exact extension before implementation.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-006 | No | Existing capability unchanged | None |
| SPEC-006 | Yes | Strictly define H.264 IPC metadata | Version 1.11.0 |
| ARCH | No | Existing component boundaries suffice | None |
| DES-004 | Yes | Cemu/SDK IPC carries timestamp and IDR | Update contract reference |
| TEST-073 | Yes | Verify IPC metadata and monotonic encoder timestamp/IDR | Update traceability |
| Code | Yes | Core schema, Rust SDK, Cemu encoder, server relay | Coordinated rollout |
| Data / migrations | No | No persisted data | None |
| Integrations / contracts | Yes | Local SDK IPC gains required H.264 fields | Coordinated rollout; raw formats unchanged |

## 5. Options considered

1. Add the two required metadata fields for H.264 — preserves SPEC-003's encoder timestamp and IDR semantics.
2. Do nothing — makes CR-014's media payload nonconforming or requires the server to invent metadata.

## 6. Decision

- **Outcome:** Approved.
- **Rationale:** This is the minimum IPC change that transports the exact metadata already required by SPEC-003; it does not alter raw-frame messages or the LAN wire protocol.
- **Rollout:** Coordinated with CR-014; no compatibility requirement for older SDK IPC clients.

## 7. Execution checklist

- [x] Owner approved the two-field extension.
- [ ] SPEC-006 and DES-004 updated; version/changelog references CR-015.
- [ ] Core/SDK/Cemu/server implementation and tests updated.
- [ ] `testing/traceability.md` updated.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-20 | Product owner + Twinpad team | Opened and approved the H.264 IPC metadata extension |
