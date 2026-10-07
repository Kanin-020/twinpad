# ADR-XXX — <Título de la decisión en imperativo>

> ⚠️ TEMPLATE — replace every `<placeholder>`, delete guidance blocks (`> 💡 …`) and this note before moving Status to `Accepted`.

> **What this is:** the immutable record of one significant architectural decision: the context, the options, the choice and its consequences.
> **What it is NOT:** detailed design (see `design/`). An Accepted ADR is not edited to change the decision — a new ADR supersedes it.

| | |
|---|---|
| ID | ADR-XXX |
| Title | `<name>` |
| Version | 1.0.0 |
| Status | `Proposed / Accepted / Superseded by ADR-YYY / Deprecated` |
| Date | `<YYYY-MM-DD>` |
| Deciders | `<names/roles>` |
| Affects | ARCH §n, DES-XXX |

## 1. Context

> 💡 The forces at play: requirements, constraints (CTX §6), technical environment, deadlines, team knowledge. Written so a reader in two years understands without asking.

<Context and problem framing.>

## 2. Problem

> 💡 The decision that had to be made, as a question.

<Which decision was needed and when.>

## 3. Options considered

> 💡 At least two real options (including the one rejected for being "too obvious"). For each: honest pros and cons, cost, migration burden. Strawman options dilute the record.

### Option A — `<name>`

- **Pros:** ...
- **Cons:** ...

### Option B — `<name>`

- **Pros:** ...
- **Cons:** ...

## 4. Decision

> 💡 One sentence, imperative, unambiguous.

<We will ... for <scope>, because <one-line driver>.>

## 5. Justification

> 💡 Why this option over the others, referencing REQs, NFRs, constraints or ADRs by ID. "We already know it" is a valid reason if stated honestly.

<Arguments, ranked.>

## 6. Consequences

> 💡 Both kinds. Every accepted trade-off is written down — future readers must see what was knowingly given up.

**Positive:**
- `<gain>`

**Negative / trade-offs accepted:**
- `<cost or limitation>`

**Neutral:**
- `<things that change but are neither gain nor cost>`

## 7. Compliance and validation

> 💡 How we will know the decision is being followed (lint rule, review checklist, structure test) and when to revisit it.

- Enforcement: `<mechanism>` / review only
- Revisit trigger: `<condition that invalidates this decision>`

## 8. Related

| Relation | ID |
|---|---|
| Supersedes | ADR-XXX / `-` |
| Superseded by | ADR-XXX / `-` |
| Relates to | REQ-XXX, NFR-XXX |
| Contextualized in | ARCH §n, DES-XXX |
| Changed by CR | CR-XXX / `-` |

## Changelog

| Version | Date | Author | Change |
|---|---|---|---|
| 1.0.0 | `<YYYY-MM-DD>` | `<author>` | Accepted |
