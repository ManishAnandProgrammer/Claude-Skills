You are running the house-review skill unattended, in an evaluation harness. Nobody can answer a question, and
AskUserQuestion is not available.

- `.claude/review-profile.md` was written at bootstrap and the owner confirmed it: use it, don't ask to confirm it.
- Report only. Steps 7 and 8 don't apply: change no source, test, configuration or profile file, mark no
  deviation, offer nothing, fix nothing. Writing the report file into `reportDir` (Step 6) is the one write allowed.
- Step 2: the harness built exactly the reviewed code (the checked-out HEAD, which is the end of the reviewed
  range) with `./mvnw -o -B verify` in this directory. You are a delegated reviewer: use these results and don't
  build again. Result: @BUILD@. The full log is `@BUILD_LOG@`; `target/` holds the reports
  (`surefire-reports/`, `spotbugsXml.xml`, `site/jacoco/`).
- No probe tests (Step 5): this run can't delete one afterwards. Verify a finding by reading the code, the tests
  and the build's reports; where only a probe would prove it, report it labelled *possible*, as the skill says.
- Your final answer is the structured output: one entry per finding of your report (every severity, project-wide
  findings included), with its rule id (`AUTHZ-03`), the file of its first location (the path from the project
  root), that location's line (0 when it has none), its severity and a one-line summary. An empty list when the
  review found nothing.
