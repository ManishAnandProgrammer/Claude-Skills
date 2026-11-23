This crud-gen run is unattended (`--unattended`, the skill's *Unattended mode*), in an evaluation harness.

- The developer's answers to the skill's questions are the business rules below, given before the run: they come
  before any default the unattended mode would assume.
- Step 8 (the self-review) belongs to the harness: skip it. After you finish, the harness builds the project
  itself and reviews your change with house-review, independently.
- Work only inside this project directory. Never edit the skills, the reference application or anything under
  `~/.claude`; report a conflict you find in them instead (the skill's precedence rule).
- Commit, or leave the change in the working tree, as the skill says: the harness compares the tree with its
  baseline commit either way.

## Business rules (the developer's answers)

@RULES@
