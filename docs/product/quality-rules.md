# Quality Rules per Document Type — Twinpad

> **What this is:** the validation checklist per document type. Reviewers and AI agents use it to decide whether a document is correctly defined. Templates embed their own DoD; this file is the normative per-type rule set.
> **How to use:** for a document of type X, validate every rule in section X. A rule that fails = a change request or an edit while still `Draft`.

## Context (CTX)

Valid when:

- [ ] Problem describes pain with evidence, not a solution.
- [ ] Objective is outcome-oriented; vision non-goals stated when relevant.
- [ ] Scope items are capability-level (each can become ≥ 1 REQ).
- [ ] Out-of-scope has a reason per row.
- [ ] Actors are complete: every human/system that interacts appears once, with type.
- [ ] Constraints are external, non-negotiable and sourced (e.g. interview decisions, Android API floor).
- [ ] Assumptions state impact if false.
- [ ] Every domain term used anywhere in docs/ exists in the glossary with a stable definition.
- [ ] No behavior, no requirements, no technology choices beyond CTX-C constraints.

## Requirement (REQ)

Valid when:

- [ ] One capability per REQ; ID unique and stable.
- [ ] Objective references CTX problem/goal.
- [ ] No implementation detail (tech, endpoints, schemas, UI specs).
- [ ] Actors exist in CTX §5.
- [ ] Terms come from the CTX glossary.
- [ ] Every acceptance criterion is objective, observable and binary-pass.
- [ ] At least one negative/error AC, when the capability can fail.
- [ ] NFR references by ID, no restated thresholds.
- [ ] Edge cases are named (behavior goes to SPEC).
- [ ] Traceable: has ≥ 1 implementing SPEC once `Implemented`.

## Specification (SPEC)

Valid when:

- [ ] Deterministic: two competent readers cannot interpret a statement differently.
- [ ] Verifiable: every statement can become a test.
- [ ] Unique ID; implements ≥ 1 REQ explicitly.
- [ ] Preconditions stated; inputs/outputs fully enumerated (types + constraints) — no "etc.".
- [ ] Main flow numbered and observable; every alternative flow has trigger + result.
- [ ] Every observable error has trigger + exact observable behavior.
- [ ] Edge cases with exact expected behavior (or moved to open questions).
- [ ] Business rules atomic and referenced (`SPEC-XXX.Rn`).
- [ ] Contracts referenced to canonical source, never duplicated.
- [ ] GWT examples present when the behavior is interaction-heavy; examples are normative.
- [ ] Invariants stated.
- [ ] "Complies" definition present (what passes, what is NOT required).
- [ ] No implementation detail beyond boundary contracts.
- [ ] No contradiction with the implementing REQ.
- [ ] Every aspect has a TEST row (in traceability.md) before `Approved`.

## Architecture (ARCH)

Valid when:

- [ ] System context shows all external actors and integrations (Jugador, Android device, PC Host, juego huesped).
- [ ] Every component has a single-sentence responsibility.
- [ ] Dependency rules explicit: allowed AND forbidden directions.
- [ ] Communication includes failure semantics (timeout/retry/idempotency), including stream interruption.
- [ ] Data ownership: single writer per data set.
- [ ] Every integration has a failure story.
- [ ] Security posture stated; requirement-specific security lives in REQs/SPECs.
- [ ] Scalability references NFR IDs, does not restate numbers.
- [ ] Cross-cutting decisions reference ADR IDs instead of narrating rationale.
- [ ] No DES-level detail (schemas, signatures, class internals).

## Design (DES)

Valid when:

- [ ] Realizes named SPEC(s) explicitly; deferrals stated.
- [ ] Respects architecture boundaries; deviations went through ADR + CR first.
- [ ] Domain model keeps glossary naming (Segunda pantalla, Gamepad HID, Pairing, Framebuffer…).
- [ ] Every SPEC error maps to a handling mechanism.
- [ ] Concurrency and caching addressed or marked N/A with a reason.
- [ ] Algorithms named with complexity when non-trivial.
- [ ] Traceability: every SPEC aspect → component → TEST.
- [ ] No copy of SPEC behavior; structure, not contract.

## ADR

Valid when:

- [ ] Context explains forces so a future reader understands without asking.
- [ ] ≥ 2 real options with honest pros/cons.
- [ ] Decision is one imperative sentence.
- [ ] Justification references REQ/NFR/CTX/ADR IDs.
- [ ] Consequences include negative/trade-offs accepted.
- [ ] Compliance mechanism and revisit trigger stated.
- [ ] Accepted ADRs are immutable; changes = new ADR superseding.

## Engineering guidelines (ENG)

Valid when:

- [ ] Every rule is checkable by a reviewer, linter or CI.
- [ ] No rule restates a SPEC behavior or an ADR decision (links only).
- [ ] Naming table covers files, types, functions, constants, DB, tests.
- [ ] Error/logging/security sections give mechanisms, not behaviors.
- [ ] AI rules are short, numbered and verifiable.

## Test strategy (TEST-S)

Valid when:

- [ ] Each level has location pattern, runner and owner.
- [ ] Ownership column prevents duplicate coverage between levels.
- [ ] Gates explicit and enforced.
- [ ] Coverage policy states what MUST be tested rather than a bare global %.
- [ ] TEST ID rules stable and registered in traceability.md.
- [ ] Failure triage rule present (code bug / test bug / spec ambiguity → CR).

## Traceability matrix

Valid when:

- [ ] Every Approved SPEC has ≥ 1 TEST row.
- [ ] Every `Implemented` REQ has all ACs covered.
- [ ] Every TEST ID used anywhere in docs/ exists in the matrix.
- [ ] Every CR that changed a doc has a history row.
- [ ] No orphan rows (all referenced IDs exist).

## Change Request (CR)

Valid when:

- [ ] Exact before/after (no "improve wording" without diff).
- [ ] Motivation with evidence.
- [ ] Impact table checked against traceability matrix — every dependent artifact listed.
- [ ] Options include "do nothing".
- [ ] Decision recorded with approver and date.
- [ ] Execution checklist completed before the CR is `Applied`.

## Workflow & AI rules

Valid when:

- [ ] Pipeline stages map 1:1 to artifacts and exit criteria.
- [ ] Every "discovery during implementation" has exactly one mandatory action.
- [ ] Agent rules are checkable and enforce the authority hierarchy.
- [ ] Anti-pattern table covers the recurring failure modes observed.
- [ ] The commit execution procedure (`.agents/skills/commit/SKILL.md`) is referenced, not duplicated.
