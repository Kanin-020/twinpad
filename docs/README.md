# Twinpad — Documentation Map

> **What this is:** the map of the Spec-Driven Development (SDD) documentation for Twinpad. It defines the document types, the ID convention, the hierarchy of authority, and the single source of truth for each kind of information.
> **Audience:** human developers and AI agents.

## How to use this documentation

| If you are... | Start with | Then |
|---|---|---|
| New to the project | [`product/context.md`](product/context.md) | `requirements/`, `architecture/` |
| Looking for what is done / what is next | [`../TODO.md`](../TODO.md) | `specs/_index.md`, `testing/traceability.md` |
| Adding a feature | `requirements/_index.md` → `specs/_index.md` → `design/_index.md` | `process/workflow.md` |
| Implementing a SPEC as an agent | `specs/SPEC-XXX.md` | `engineering/guidelines.md` + `architecture/architecture.md` |
| Changing an approved SPEC | `process/change-requests/CR-XXX.md` | `process/workflow.md` |
| Making a commit (as agent or human) | `.agents/skills/commit/SKILL.md` | `process/workflow.md` §8 |
| Reviewing a change | `testing/traceability.md` | `specs/`, `testing/` |
| Validating a document | `product/quality-rules.md` | the document's own DoD section |

## Documents

| Document | ID | Location | Purpose | Answers | Owner | Updated when |
|---|---|---|---|---|---|---|
| Context | `CTX` | `product/context.md` | Why Twinpad exists: problem, goals, scope, actors, constraints, assumptions, glossary | Why, and for whom | Product | Scope, actors, constraints or glossary change |
| Requirement | `REQ` | `requirements/REQ-XXX-slug.md` | What Twinpad must do and how it is accepted, at capability level | What, and why | Product + Eng | New or changed capability |
| Specification | `SPEC` | `specs/SPEC-XXX-slug.md` | Precise, testable behavior that satisfies one or more requirements | How it must behave | Eng + Product | Behavior change (via CR) |
| Architecture | `ARCH` | `architecture/architecture.md` | System structure: components, responsibilities, boundaries, integrations | How it is structured | Eng lead | Structural change (via ADR/CR) |
| Detailed design | `DES` | `design/DES-XXX-slug.md` | How a SPEC is realized within the architecture | How it is built | Eng | New SPEC or architecture change |
| Decision record | `ADR` | `decisions/ADR-XXX-slug.md` | Why an architectural choice was made | Why this way | Eng lead | New decision, or one changes state |
| Testing strategy | `TEST-S` | `testing/test-strategy.md` | How compliance with the specs is demonstrated | How it is verified | Eng + QA | Test approach changes |
| Traceability matrix | — | `testing/traceability.md` | REQ → SPEC → DES → TEST links | What verifies what | Eng | A link is created or changed |
| Engineering guidelines | `ENG` | `engineering/guidelines.md` | Transversal implementation rules | How to write code | Eng lead | Standard change |
| Workflow | — | `process/workflow.md` | The SDD process: artifacts created/modified at each stage, and change management | In what order | Eng lead | Process change |
| AI agent rules | — | `process/ai-agent-rules.md` | Rules for AI agents consuming this documentation | How to work with it | Eng lead | Rule change |
| Commit skill (agent skill) | — | `.agents/skills/commit/SKILL.md` | Executable procedure that makes `/skills:commit` produce a real git commit | How to commit | Eng lead | Commit flow change |
| Change Request | `CR` | `process/change-requests/CR-XXX-slug.md` | Controlled change to an approved document | What changed and why | Anyone proposes; approver decides | Each change |
| Project tracker (status / next) | — | [`TODO.md`](../TODO.md) (repo root) | Living list of Done items (with commits) and the ordered roadmap of what to build next, mapped to the SDD pipeline | What is the current state, what is next, which drafts await approval | Eng lead + owner | Every completed item, approval or roadmap change |
| Quality rules | — | `product/quality-rules.md` | Validation checklist per document type for reviewers and agents | Is this doc correctly defined | Eng lead | A template or rule changes |
| Traceability example | — | `examples/traceability-example.md` | Worked miniature chain REQ→SPEC→DES→TEST as reference | What does a good chain look like | Eng lead | Only as a teaching reference |

## ID convention

| Prefix | Doc type | Example | Location |
|---|---|---|---|
| `REQ` | Requirement | `REQ-001` | `requirements/` |
| `SPEC` | Specification | `SPEC-001` | `specs/` |
| `DES` | Detailed design | `DES-001` | `design/DES-XXX-slug.md` |
| `ADR` | Decision record | `ADR-001` | `decisions/` |
| `TEST` | Test suite or case | `TEST-001` | Defined in `testing/` |
| `CR` | Change Request | `CR-001` | `process/change-requests/` |

### ID rules

1. Sequential, zero-padded to 3 digits (`REQ-001`); never reuse IDs.
2. Next available number = per-type "Next free IDs" table in the `_index.md` of each directory.
3. Reserve a number when creating a draft; it stays assigned on acceptance.
4. IDs are semantic-free — the number carries no meaning, ever.
5. IDs never change; content is modified in place; removal = mark Deprecated.
6. Cross-references use IDs, not prose ("verifies REQ-001", not "the one about pairing").

### Link semantics

| Relationship | Notation | Where it is written |
|---|---|---|
| refines | `SPEC-001 implements REQ-001` | SPEC header + `testing/traceability.md` |
| verifies | `TEST-001 verifies SPEC-001` | SPEC "Verification" section + `testing/traceability.md` |
| realizes | `DES-001 realizes SPEC-001 (full / partial)` | DES header + `testing/traceability.md` |
| justifies | `ADR-003 justifies a decision in DES-001` | ADR "Backlinks" + DES "Related decisions" |
| changes | `CR-001 changes SPEC-004` | CR header + `process/change-requests/_index.md` |
| supersedes | `REQ-002 supersedes REQ-001` | Both document headers |

Every link is also recorded, in both directions, in `testing/traceability.md`.

## Single source of truth (SSOT)

| Type of information | SSOT | Appears elsewhere only as a link |
|---|---|---|
| Why Twinpad exists | `product/context.md` | — |
| What Twinpad must do + priority | `requirements/` | traceability matrix |
| Exact behavior | the owning SPEC in `specs/` | traceability matrix |
| How Twinpad is structured | `architecture/architecture.md` | ADRs reference it |
| How a SPEC is realized | the owning DES in `design/` | traceability matrix |
| Why an architectural choice | `decisions/` (ADRs) | DES reference the ADR |
| How compliance is demonstrated | `testing/test-strategy.md` | SPECs list TEST IDs |
| Which test verifies which SPEC | `testing/traceability.md` | — (it IS the registry) |
| Transversal coding rules | `engineering/guidelines.md` | — |
| Process and change management | `process/workflow.md` | CR index |
| AI rules | `process/ai-agent-rules.md` | — |
| Commit execution procedure | `.agents/skills/commit/SKILL.md` | referenced from AGENTS.md + workflow §8 |
| Change management | `process/workflow.md` + `process/change-requests/` | — |

Rule: any content that would be duplicated is replaced by an ID link.

## Hierarchy of authority (on conflict)

On conflict between documents, the higher one wins; always report the conflict, and open a CR if the lower one is the intended truth.

1. Approved Requirements
2. Approved Specifications
3. Approved Architecture
4. Approved Design
5. Engineering Guidelines
6. Existing code / implementation
7. Agent skills (`.agents/skills/`) are process tooling; they never override the hierarchy above.

Notes:

- ADRs are not in the list because an Accepted ADR may only **explain** what Architecture/Design already state. If an ADR contradicts an approved SPEC, report it and resolve via CR — the hierarchy applies, not the ADR text.
- Existing code is last: it never silently redefines a SPEC; discrepancies go through a CR.
- If an approved doc contradicts the Context (e.g. Context mandates offline-first LAN operation and a SPEC contradicts it), the requirement wins per this list, but always report: it usually signals a broken or missing REQ.

## Document quality rules

Quality rules per type live in [`product/quality-rules.md`](product/quality-rules.md) (the normative per-type checklist) and as a local DoD inside each template; `process/ai-agent-rules.md` adds AI-oriented rules. A document fails review if:

- It contains placeholders other than the template's placeholders while its Status is `Approved`.
- It duplicates information from another doc without an ID link (the traceability matrix is a link registry, not duplicated content).
- It has broken links or broken ID references.

## Deprecated content

A deprecated doc keeps its ID and gets a header note: `> ⚠️ DEPRECATED — superseded by REQ-002 (CR-003)`. Never delete files: links in `testing/traceability.md` and other docs would break. Deprecated docs are removed from the "Active" tables in `_index.md` and listed under "Deprecated".

## Document control

| Field | Value |
|---|---|
| ID | DOC-001 |
| Version | 0.1.0 |
| Status | Draft |
| Owner | Twinpad team |
| Last updated | 2026-10-07 |
| History | 0.1.0 — 2026-10-07 — Initial structure adapted from template |
