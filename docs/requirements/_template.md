# REQ-XXX — <Requerimiento en 3–7 palabras>

> ⚠️ TEMPLATE — replace every `<placeholder>`, delete guidance blocks (`> 💡 …`), and delete this note before moving Status to `Approved`.

> **What this is:** a single verifiable capability Twinpad must provide, with its acceptance criteria.
> **What it is NOT:** precise behavior (see the SPECs that implement it) or a solution design (see `design/`).

| | |
|---|---|
| ID | REQ-XXX |
| Title | `<name>` |
| Version | 0.1.0 |
| Status | `Draft / In review / Approved / Implemented / Deprecated` |
| Priority | `Must / Should / Could` (MoSCoW) |
| Owner | `<role>` |
| Refines | CTX scope item `<n>` or `-` |
| Implemented by | SPEC-XXX, SPEC-YYY (fill in; `-` until assigned) |
| Last updated | `<YYYY-MM-DD>` |

## 1. Objective

> 💡 Why this requirement exists, in 1–3 sentences, referencing the problem or goal in `product/context.md`. If you cannot reference it, the requirement may not belong.

<What user or business outcome this requirement produces.>

## 2. Description

> 💡 WHAT Twinpad must provide, stated at capability level. Behavior lives in the SPEC. Use glossary terms exactly as defined in `product/context.md` §8 (Twinpad, Segunda pantalla, Gamepad HID, Pairing, Framebuffer, Stream, Plugin…).

<One paragraph. No implementation, no UI details, no field lists — those belong to SPEC/DES.>

## 3. Actors

> 💡 Only actors already defined in `product/context.md` §5 (Jugador / Usuario PC, Android device, PC Host, Juego huesped, Plugin, Twinpad Core). Never invent new actors here — add them to Context first.

| Actor | Role in this requirement |
|---|---|
| `<actor>` | `<what it does here>` |

## 4. Preconditions

- `<state that must hold before this capability applies>`

## 5. Behavior expected (summary)

> 💡 3–6 bullets describing observable outcome, NOT mechanics. Each bullet must later be traceable to at least one SPEC.

- `<observable outcome 1>`
- `<observable outcome 2>`

## 6. Acceptance criteria

> 💡 The contract for calling this requirement implemented. Each criterion: objective, binary-pass, observable. If a criterion cannot be verified, it is not a criterion — it is a wish. These are typically satisfied by the acceptance tests of the implementing SPECs.

- [ ] AC-1: `<Given <precondition>, when <action>, then <observable result>>`
- [ ] AC-2: `<...>`
- [ ] AC-3: `<error/negative case>`

## 7. Non-functional concerns

> 💡 Only the NFRs that constrain THIS requirement (latency, throughput, availability, security, accessibility). Reference NFRs defined globally in `requirements/nfrs.md` by ID; add requirement-specific thresholds here only if they differ. Twinpad NFRs: NFR-001..007 (latencia HUD, latencia HID, autoarranque, seguridad, utilización CPU, múltiples input simultáneos, batería Android).

- `<NFR-XX: constraint relevant here>` / `-`

## 8. Edge cases and limits

> 💡 Boundary conditions the SPECs must cover. Do not define the behavior here — name the cases the SPEC must answer.

- `<boundary condition or limit>`

## 9. Dependencies

| On | Type | Why |
|---|---|---|
| `<REQ-XXX / CTX-A1 / external system>` | `<blocking / soft>` | `<why>` |

## 10. Related specifications

- `SPEC-XXX — implements this requirement (full/partial)` — `-` until assigned

## 11. Notes and open questions

- `<open question, with owner and date>`

## Definition of Done

- [ ] Every acceptance criterion is objective and binary-pass.
- [ ] Actors exist in `product/context.md` §5.
- [ ] Terms used are defined in the glossary.
- [ ] At least one SPEC implements this REQ (when Status is `Implemented`).
- [ ] No implementation details (tech, frameworks, endpoints, schemas).
- [ ] Traceability row exists in `testing/traceability.md`.
- [ ] No placeholders; Status updated.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | `<YYYY-MM-DD>` | `<author>` | Initial draft |
