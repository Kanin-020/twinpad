# Rules for AI Agents — Twinpad

> **What this is:** the binding contract for any AI agent (coding assistant, review bot, generator) consuming or producing documents in this repo.
> **Why it exists:** agents fail by inventing context, resolving ambiguity silently, or "fixing" specs to match code. These rules make that impossible.

| | |
|---|---|
| Version | 0.1.0 |
| Owner | Twinpad team |
| Last updated | 2026-10-07 |

## 1. Reading order (mandatory)

1. `docs/README.md` — map, IDs, authority hierarchy.
2. `docs/product/context.md` — problem, actors, constraints, glossary.
3. The SPEC(s) for the task at hand — the contract.
4. `architecture/architecture.md` §2–§4 — boundaries.
5. `engineering/guidelines.md` — how to write code here.
6. The DES if one exists for the SPEC.
7. `testing/test-strategy.md` §4–§7 — how to register tests.

Never skip 1–2: agents that skip context invent actors and redefine terms.

## 2. Context discipline

- **Explicit over implicit.** If information is not in the docs, it does not exist. Never assume conventions, endpoints, schemas or decisions that are not written.
- **No invention.** Do not create new behavior, fields, errors or rules that no SPEC defines — even if they seem "obviously needed". Propose instead: draft SPEC change or open question.
- **Terms are frozen.** Use glossary terms exactly as defined in CTX §8 (Twinpad, Segunda pantalla, Gamepad HID, Pairing, Framebuffer, Signature HID, SDK Twinpad, Config externa…); never rename or reinterpret them.
- **Reference by ID.** When speaking about a requirement or rule, cite its ID (`SPEC-004.R2`), never paraphrase it.
- **Facts vs decisions.** Facts (constraints, contracts) come from CTX/ARCH/SPECs; decisions (choices with alternatives) from ADRs. Do not re-derive a decided question — apply the ADR; do not re-decide it (e.g. transporte y stack ya están decididos en ADR-001/002).

## 3. Ambiguity and conflict protocol

- On **ambiguity** (2+ reasonable interpretations): STOP the affected task. Report: doc IDs involved, the ambiguous quote, the interpretations, your recommendation. Do not pick one silently.
- On **contradiction** between documents: apply the authority hierarchy in `docs/README.md` (REQ > SPEC > ARCH > DES > ENG > code), report the conflict, and open a CR if the losing document is the intended truth.
- On **missing information**: mark the task blocked with the exact list of missing decisions. "I assumed" is not a valid state.
- Never weaken a test, skip a test, or mark a criterion as done without evidence.

## 4. The implementation never edits the contract

- The agent does NOT modify REQs, SPECs, ARCH, ADRs or the test strategy to accommodate code it wrote.
- If code cannot comply: open a CR (`process/change-requests/_template.md`) quoting the blocker with evidence (failing test, benchmark, limitation).
- If a test contradicts the SPEC, treat it as a defect in test OR spec — report, do not adjust silently (test-strategy.md §7).
- After an Approved CR changes a SPEC, the implementation follows the NEW spec — the CR's decision section is temporary authority until docs are updated.

## 5. Output requirements

- Every delivered change lists: SPEC ID(s) implemented, TEST ID(s) added/updated, docs touched (with CR IDs).
- New tests carry TEST IDs and are registered in `testing/traceability.md` before merge.
- No placeholders, no dead code, no TODOs in delivered work.
- Generated documents follow the templates in this tree; any structural deviation is called out explicitly.
- When the user asks to commit (or invokes `/skills:commit`), the agent loads `.agents/skills/commit/SKILL.md` and creates the real commit itself following workflow §8.

## 6. Self-check before declaring done

Run this list against your own work:

- [ ] Each behavior I implemented traces to a SPEC statement or explicitly reported open question.
- [ ] I did not add behavior, fields or errors absent from the SPEC.
- [ ] Boundaries from architecture §3 are respected.
- [ ] Engineering guidelines followed (naming, errors, logging).
- [ ] TEST IDs assigned + traceability rows added.
- [ ] No spec/req/arch file modified (except via an Approved CR).
- [ ] Contradictions and ambiguities were reported, not resolved silently.

## 7. Anti-patterns (auto-reject)

| Anti-pattern | Correct behavior |
|---|---|
| "I implemented it slightly differently because it was cleaner" | CR proposal or report blocker |
| "The spec was wrong so I fixed it" | CR with evidence; never silent edit |
| "I assumed the API accepts X" | Ask; treat as missing information |
| "I generalized the spec to cover more cases" | Out of scope; propose a new REQ |
| "I renamed the entity to match the code" | Glossary terms are frozen |
| "Test failed so I relaxed the assertion" | Diagnose: bug in code, test, or spec ambiguity |
| "I skipped the traceability update" | Matrix is a merge gate |
| "I printed a commit message for the user to copy" | Follow `.agents/skills/commit/SKILL.md` and commit directly |
