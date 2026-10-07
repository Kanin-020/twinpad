# AGENTS.md — for AI coding agents working in this repository (Twinpad)

## Mandatory reading order

1. [`docs/README.md`](docs/README.md) — documentation map, ID convention, authority hierarchy.
2. [`docs/product/context.md`](docs/product/context.md) — problem, actors, constraints, glossary.
3. The SPEC file(s) for your task — the behavior contract you implement.
4. [`docs/architecture/architecture.md`](docs/architecture/architecture.md) — boundaries and responsibilities.
5. [`docs/engineering/guidelines.md`](docs/engineering/guidelines.md) — coding rules, including repo-specific agent rules (§13).
6. [`docs/process/ai-agent-rules.md`](docs/process/ai-agent-rules.md) — your binding rules (ambiguity protocol, forbidden actions, self-check).

## Hard rules

- Implement exactly what the SPEC says. Never invent behavior, fields or errors.
- If information is missing or ambiguous: STOP and report with document IDs. Do not assume.
- Never modify REQs, SPECs, architecture or ADRs to fit your code — open a Change Request (`docs/process/change-requests/_template.md`).
- On conflicts between docs, the authority hierarchy in `docs/README.md` decides; always report the conflict.
- Every new test gets a TEST ID and a row in `docs/testing/traceability.md`.
- Use glossary terms exactly as defined; never rename domain concepts.
- Commits follow the commit flow (workflow §8): Conventional Commits format, descriptive body, one logical change, gates green.
- **Commit execution:** when invoked with `/skills:commit` (or asked to commit), load and follow `.agents/skills/commit/SKILL.md` and create the commit directly yourself — do not output a command or message for the user to copy. Never add Co-Authored-By or agent trailers.

## Committing via the skill

- Whenever the user types `/skills:commit` or says "commit this", you MUST:
  1. Load `.agents/skills/commit/SKILL.md` (the `commit` skill).
  2. Follow it step by step and leave the commit actually created in git.
  3. Report in one line: commit hash, type(scope), and files included.
