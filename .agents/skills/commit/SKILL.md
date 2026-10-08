---
name: commit
description: Creates a real git commit (not a proposed message) following Conventional Commits and the project gates. Use it when the user invokes /skills:commit or asks to "commit" / "comitear".
---

# Skill: commit

## Goal

The agent **creates the commit directly** in the repository. The expected outcome is a real git commit — never a suggested message for the user to copy.

## Steps

1. **Check the repo state**
   - `git status --short` and `git diff` (staged + unstaged) to know what changes.
   - If there are no changes: report and stop (no empty commit).

2. **Group the changes into ONE logical change**
   - Code and its tests together; never mix unrelated edits.
   - If there are clearly unrelated changes, ask before committing everything together.

3. **Project gates (workflow §8)**
   - Run the gates defined in `docs/process/workflow.md` §8 (format, linter, tests) over the changed files.
   - If a gate fails: do NOT commit. Diagnose (code bug, test bug, SPEC ambiguity) and report it.

4. **Determine the message**
   - **If the user provided a message**, use it as is. If it does not follow workflow §8 format, normalize it to Conventional Commits and note it in the report.
   - **If the message is empty** (the user did not give one, or just said "commit"), **generate it automatically** from the changes. Never ask what message they want, and never leave the commit half-done:
     1. Infer the `type` from the dominant content of the diff:

        | Content of the change | Type |
        |---|---|
        | New functionality or capability | `feat` |
        | Defect fix | `fix` |
        | Documentation only | `docs` |
        | Tests only | `test` |
        | Internal change with no behavior change | `refactor` |
        | Build or dependencies | `build` / `chore` |
        | CI pipelines | `ci` |
        | Performance | `perf` |
        | Revert of a previous commit | `revert` |

     2. Infer the `scope` from the main area touched (component, module or package); omit it if the change is transversal.
     3. Write the summary: imperative, ≤ 72 chars, no trailing period.
     4. For `feat`/`fix`/`refactor`, add a bulleted body (what and why) referencing IDs (SPEC-XXX, TEST-XXX, REQ-XXX, CR-XXX) where applicable.
   - Formato final siempre Conventional Commits: `type(scope): summary`.
   - **Never** add attribution trailers (Co-Authored-By or similar): each commit belongs to the team member who made the change (`docs/process/team.md`). The agent only creates commits with the git identity of the member it works for — never in its own name.

5. **Create the commit**
   - Stage only the files of the logical change (`git add <paths>`, or `git commit -am` if everything applies).
   - Create the commit with a heredoc:

     ```bash
     git commit -m "$(cat <<'EOF'
     type(scope): summary

     - bullet 1 (what and why, with IDs if applicable)
     - bullet 2
     EOF
     )"
     ```

6. **Confirm and report**
   - Verify with `git log -1 --stat` that the commit exists and contains what is expected.
   - Report one line: short hash, `type(scope)`, files included and the message used (noting whether it was auto-generated).
   - Do NOT push unless the user explicitly asks for it.

## Rules

- One commit = one logical change.
- No empty commits (`--allow-empty` forbidden).
- **Empty message ⇒ automatic generation** from the diff; never block the commit by asking the user for a message.
- No automatic push.
- If the repo has pre-commit hooks, let them run; if they fail, treat it as a failed gate.
- If the repo state is ambiguous (detached HEAD, merge in progress), stop and report.
