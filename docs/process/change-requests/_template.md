# Change Request (CR) — CR-XXX — Twinpad

> ⚠️ TEMPLATE — the ONLY mechanism to change an `Approved` document. One CR per logical change.

> **What this is:** a controlled, reviewable change to an approved artifact (REQ, SPEC, ARCH, DES, guidelines, strategy). It records what, why, impact and decision.
> **What it is NOT:** a bug report (use the issue tracker) or a discussion thread — keep it decidable.

| | |
|---|---|
| ID | CR-XXX |
| Title | `<change in one line>` |
| Type | `Clarification / Correction / New behavior / Behavior change / Scope change` |
| Status | `Open / In review / Approved / Applied / Rejected / Withdrawn` |
| Author | `<name/agent>` |
| Created | `<YYYY-MM-DD>` |
| Decided | `<YYYY-MM-DD / ->` |
| Decided by | `<role/name / ->` |

## 1. Target

| Field | Value |
|---|---|
| Document(s) changed | `<SPEC-XXX / REQ-XXX / ARCH / ENG / ...>` |
| Sections | `<§n>` |
| Current version → new version | `0.x.y → 0.x.z` |

## 2. Requested change

> 💡 Exact before/after. For behavior changes: a redline diff of the affected SPEC sections. "Improve wording" without diff is rejected.

**Current:**

```text
<exact current text or behavior>
```

**Proposed:**

```text
<exact new text or behavior>
```

## 3. Motivation

> 💡 Why now. Categories: implementation discovery, new requirement, external change (regulation/vendor), defect in spec, feedback. Reference evidence (failing test, ticket, benchmark).

<The reason, with evidence.>

## 4. Impact analysis

> 💡 Every dependent artifact. "No impact" is a valid cell only after checking the traceability matrix (`testing/traceability.md`).

| Artifact | Affected? | Impact | Action needed |
|---|---|---|---|
| REQ-XXX | `<yes/no>` | `<how>` | `<update / none>` |
| SPEC-XXX | `<yes/no>` | `<how>` | `<update / none>` |
| ARCH | `<yes/no>` | `<how>` | `<update / ADR / none>` |
| DES-XXX | `<yes/no>` | `<how>` | `<update / none>` |
| TEST-XXX | `<yes/no>` | `<how>` | `<update / none>` |
| Code | `<yes/no>` | `<effort estimate>` | `<PR scope>` |
| Data / migrations | `<yes/no>` | `<how>` | `<migration plan>` |
| Integrations / contracts | `<yes/no>` | `<how>` | `<version bump / none>` |

## 5. Options considered

> 💡 At least the proposed one and "do nothing". Keep short.

1. `<proposed change>` — `<why best>`
2. Do nothing — `<consequence>`

## 6. Decision

> 💡 The approver fills this. An Approved CR becomes the temporary authority for implementation until docs are updated — apply doc changes within the same iteration.

- **Outcome:** `Approved / Rejected / Deferred`
- **Rationale:** `<one paragraph>`
- **Rollout:** `<same release / next release / migration required>`

## 7. Execution checklist

- [ ] Target document(s) updated (version bumped, changelog entry references this CR ID)
- [ ] Dependent documents updated (from impact table)
- [ ] Tests added/updated (TEST IDs)
- [ ] `testing/traceability.md` rows updated (including CR history table)
- [ ] Indexes (`_index.md`) updated
- [ ] Code change merged (if any)

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1.0 | `<YYYY-MM-DD>` | `<author>` | Opened |
