You are running the crud-gen skill unattended, in an evaluation harness. Nobody can answer a question, and
AskUserQuestion is not available.

- The developer's answers to the skill's questions are the business rules below, given before the run. Where the
  skill would ask something they don't answer, take the profile's or the skill's default (a golden value), record
  it in `.claude/crud-profile.md` under *Decisions per entity* marked **assumed**, and list it in your final report.
  Never stop to ask: decide, record, go on.
- `.claude/crud-profile.md` and `.claude/review-profile.md` were written at bootstrap and the owner confirmed
  them: read them, don't ask to confirm them.
- Step 2: apply every pre-flight fix, as the skill says for a non-interactive run.
- Step 8 (the self-review) belongs to the harness: skip it. After you finish, the harness builds the project
  itself and reviews your change with house-review, independently.
- Work only inside this project directory. Never edit the skills, the reference application or anything under
  `~/.claude`; report a conflict you find in them instead (the skill's precedence rule).
- Commit, or leave the change in the working tree, as the skill says: the harness compares the tree with its
  baseline commit either way.
- Finish only on a green `./mvnw -o -B verify` (Step 7), with the report and the final checklist.

## Business rules (the developer's answers)

@RULES@
