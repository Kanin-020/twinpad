# SDD Workflow — Twinpad

> **What this is:** the process that takes an idea from problem to verified implementation using the docs/ tree. It defines what is created or modified at each stage, and the mandatory change process.
> **What it is NOT:** a substitute for the docs themselves — it is the ordering and the rules of engagement.

| | |
|---|---|
| Version | 0.1.0 |
| Owner | Twinpad team |
| Last updated | 2026-10-07 |

## 1. The pipeline

```text
Context → Requirements → Specification → Architecture → Design → Implementation → Verification
        (CTX)     (REQ)          (SPEC)            (ARCH)      (DES)        (code + tests)     (TEST + matrix)
```

Reality check: the pipeline is the ideal order, not a waterfall. Feedback loops exist and are legitimate — but every loop that changes an approved artifact goes through a CR (§5). What is forbidden is silent modification.

## 2. Stages and artifacts

| Stage | Input | Created / modified | Exit criteria (all required) |
|---|---|---|---|
| 1. Context | problem, constraints, actors | `product/context.md` | DoD of context.md met; actors and glossary stable |
| 2. Requirements | context | `requirements/REQ-XXX.md`, `requirements/nfrs.md`, `_index.md` | Every REQ has binary ACs; actors/terms exist in CTX |
| 3. Specification | REQs | `specs/SPEC-XXX.md`, `_index.md` | SPEC DoD met; deterministic; errors and edge cases enumerated |
| 4. Architecture | SPECs | `architecture/architecture.md` (+ ADRs) | Component responsibilities explicit; boundaries + failure semantics defined |
| 5. Design | SPECs + ARCH | `design/DES-XXX.md` (+ ADRs) | Every SPEC aspect mapped to a component and a TEST |
| 6. Implementation | SPEC + DES + ENG guidelines | code + tests (TEST IDs assigned) | Code compiles/lints; tests green; no SPEC modified |
| 7. Verification | code + tests | `testing/traceability.md` rows updated; REQ → `Implemented` | Gates in test-strategy.md §6 green; matrix complete |

Minimum viable path for a small feature: REQ → SPEC → (reuse ARCH) → DES (may be a section if trivial) → implementation → verification. For a trivial change, a SPEC alone may suffice — never zero docs.

## 3. Document states

```text
Draft → In review → Approved → (Implemented for REQ) → Deprecated
```

- Only `Approved` documents are binding for implementation.
- `Draft`/`In review` documents may be edited freely; `Approved` only via CR.
- An `Approved` SPEC is the contract for implementation and for tests.

## 4. Roles

| Role | Responsibility |
|---|---|
| Product owner | CTX, REQs, priorities, ACs |
| Eng lead | ARCH, ADRs, guidelines, approval of SPECs |
| Engineer/agent | DES, implementation, tests, traceability rows |
| Reviewer | DoD + quality rules per document type |

One person may hold several roles; separation is about attention, not headcount.

## 5. Discoveries during implementation

> 💡 The implementation NEVER silently modifies a SPEC, REQ or ARCH. Each discovery maps to exactly one action:

| Discovery | Mandatory action | Never do |
|---|---|---|
| Ambiguous requirement | STOP the affected task; open a CR with the ambiguity quoted and 2+ interpretations; ask the owner | guess an interpretation and code it |
| Contradiction between docs | STOP; report both IDs; higher authority wins per README hierarchy; CR to fix the loser | patch the lower doc silently |
| New need discovered mid-build | new REQ (Draft) → SPEC → normal pipeline; do not fold it into the current SPEC | grow the SPEC's scope silently |
| New architectural decision needed | ADR (Proposed) → accept → apply via CR to ARCH/DES | just code the alternative |
| Technical limitation found | CR proposing SPEC adjustment, quoting the limitation (failing test, benchmark, bug); owner decides: change SPEC or change approach | weaken tests to pass |

The implementation may always propose; only the owner of a document approves its change.

## 6. What an AI agent must do at each stage

| Stage | Agent behavior |
|---|---|
| Context | Read CTX for problem, actors, constraints, glossary |
| Requirements | Read the REQs it implements; never modify them |
| Specification | Treat SPEC as the contract; GWT examples are normative |
| Architecture | Respect boundaries and dependency rules; flag violations instead of working around them |
| Design | Follow the DES; deviations require a note + CR |
| Implementation | Implement exactly the SPEC; ask for missing decisions, never invent |
| Verification | Register TEST IDs; update matrix; failures resolved via test-strategy.md §7 |

## 7. Definition of done (feature level)

A feature is done when:

1. Its SPEC is `Approved`.
2. All its TESTs are green in CI gates.
3. `testing/traceability.md` has complete rows REQ → SPEC → DES → TEST.
4. REQ is moved to `Implemented`.
5. No open CR affecting it.

## 8. Commit flow

Every commit follows [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): summary`.

- **Types:** `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `build`, `ci`, `perf`, `revert`. `scope` is the module (`server`, `android`, `hid`, `core`, `sdk`, `docs`); optional.
- **Summary:** imperative mood, ≤ 72 chars, no trailing period.
- **Body:** for features, fixes and refactors, a short descriptive body — what changed and why, bullets over prose, referencing SPEC/TEST IDs when applicable. No boilerplate.
- **Authorship:** commits are authored solely by the repository owner. Never add `Co-Authored-By` trailers or any agent/tool attribution.
- **Granularity:** one logical change per commit; code and its tests together; never mix unrelated edits.
- **Gates:** `cargo fmt --check`, `clippy -D warnings`, `ktlint`/`detekt` and tests green before committing.
- **Execution:** to actually create the commit, the agent loads and follows `.agents/skills/commit/SKILL.md` (the `commit` skill) — `/skills:commit` must end with a real git commit, not a proposed message.

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | 2026-10-07 | Twinpad team | Initial draft adapted from template; commit execution via commit skill |
