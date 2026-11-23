This house-review run is unattended (`--unattended`, the skill's *Unattended mode*: report only), in an evaluation
harness.

- Step 2: the harness built exactly the reviewed code (the checked-out HEAD, which is the end of the reviewed
  range) with `./mvnw -o -B verify` in this directory. You are a delegated reviewer: use these results and don't
  build again. Result: @BUILD@. The full log is `@BUILD_LOG@`; `target/` holds the reports
  (`surefire-reports/`, `spotbugsXml.xml`, `site/jacoco/`).
- Your final answer is the structured output: one entry per finding of your report (every severity, project-wide
  findings included), with its rule id (`AUTHZ-03`), the file of its first location (the path from the project
  root), that location's line (0 when it has none), its severity and a one-line summary. An empty list when the
  review found nothing.
