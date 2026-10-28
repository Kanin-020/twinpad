# Change Request — CR-035 — Micrófono del teléfono hacia Cemu

| | |
|---|---|
| ID | CR-035 |
| Title | Enviar audio del micrófono del teléfono a Cemu como entrada de micrófono del VPAD |
| Type | `New behavior` |
| Status | `Open` |
| Author | Twinpad team (agente) |
| Created | 2026-10-28 |
| Decided | - |
| Decided by | - |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | SPEC-005 (audio), SPEC-006, DES-004, ARCH (contrato), traceability |
| Sections | SPEC-006 mensajes Android → Cemu; SPEC-005 formato de audio |
| Current version → new version | Propuesto; pendiente de versión |

## 2. Requested change

**Current:**

```text
In VPADController::VPADRead, the Twinpad branch keeps m_mic_active = false. Cemu receives no microphone audio from the phone. Android only has audio playback (AudioPlayback.kt) for Cemu → phone audio.
```

**Proposed:**

```text
While the Cemu Plugin is connected, the Android app captures microphone PCM (s16le, mono, 16 kHz proposed) and sends it to Cemu as `mic_chunk` messages over the existing plugin channel. Cemu queues the chunks for the VPAD microphone API for Player 1 and sets m_mic_active while chunks are fresh. Chunks are dropped when stale (drop-old policy, as in CR-003). The microphone is captured only while the stream is active and the user has granted RECORD_AUDIO.
```

## 3. Motivation

Games that use the Wii U GamePad microphone cannot receive audio from the phone. Evidence: `VPADController.cpp` keeps the mic flag false in the Twinpad branch; `vpad.cpp` exports no VPAD microphone functions yet; Android has no capture path.

## 4. Impact analysis

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-006 | Maybe | New capability | Confirm scope |
| SPEC-005 | Yes | New PCM direction and format | Add rules |
| SPEC-006 | Yes | New `mic_chunk` message | Add message |
| DES-004 | Yes | Cemu VPAD mic path | Update |
| TEST | Yes | Encode/decode and drop-old tests | New TEST IDs |
| Code | Yes | Android capture, plugin message, Cemu VPAD mic exports | Large |
| Integrations / contracts | Yes | New message and permission | Version bump |

## 5. Options considered

1. Capture phone mic and feed Cemu VPAD mic with drop-old policy — matches the requested direction.
2. Do nothing — games that use the GamePad mic get no input.

**Open questions for the approver:**
- Sample rate and channels for the VPAD mic (16 kHz mono proposed; not verified against the Cemu VPAD API).
- Whether the VPAD mic exports must be implemented in `vpad.cpp` as part of this CR.

## 6. Decision

- **Outcome:** pending.
- **Rationale:** -
- **Rollout:** -

## 7. Execution checklist

- [ ] Product owner approval recorded
- [ ] Target document(s) updated
- [ ] Tests added/updated (TEST IDs)
- [ ] `testing/traceability.md` rows updated
- [ ] `_index.md` updated
- [ ] Code change merged

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-28 | Twinpad team (agente) | Opened |
