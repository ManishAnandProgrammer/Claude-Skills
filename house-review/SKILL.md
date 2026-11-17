---
name: house-review
description: Review Java / Spring Boot code against the house engineering standards (REST guidelines, code smells, Effective Java, persistence pitfalls, the eight lenses incl. required tests). Works on a branch diff, a commit, uncommitted changes, a path or module, or the whole codebase. Builds and runs the tests, verifies every serious finding, handles deliberate deviations (accept + mark, or fix), reports, then offers to fix. Project conventions come from .claude/review-profile.md, built from the codebase on first use.
argument-hint: "[diff | commit <sha> | uncommitted | all | <path>] [--focus api|data|tests|security|config|arch]"
allowed-tools: [Read, Write, Edit, Bash, Glob, Grep, Agent, AskUserQuestion]
---

# House Review

Review scope: **$ARGUMENTS**

The rules are fixed and identical across projects. They live in `references/`:

| File | Rule IDs | Covers |
|---|---|---|
| `references/rest-api.md` | REST-xx | Endpoints, status codes, errors, validation, authorization, OpenAPI and doc drift |
| `references/code-smells.md` | SMELL-xx | Code smells, Clean Code, Refactoring |
| `references/java.md` | JAVA-xx | Effective Java and the house Java conventions |
| `references/persistence.md` | DATA-xx | Transactions, connections, deletes, history, ids, locking, indexes |
| `references/lenses.md` | SEC/PERF/REL/OPS/CONC/TEST-xx | The eight lenses, resilience, the runbook and required tests |
| `references/architecture.md` | ARCH-xx | Module map, boundaries, layers, events, ArchUnit enforcement |
| `references/error-contract.md` | ERR-xx | The house error contract (ErrorCode, mapper, ProblemDetail, 409 vs 400, 503 + Retry-After) |
| `references/config-baseline.md` | CFG-xx | Hikari/JPA/PostgreSQL, server and threads, Jackson, actuator, logging, JVM |
| `references/metadata.md` | META-xx | Configuration metadata and counters; only for projects with the `metadata` piece |
| `references/media.md` | MEDIA-xx | File uploads to S3 (presigned URLs, purposes and policies, claim, verification, cleanup); only for projects with the `media` piece |
| `references/tenancy.md` | TEN-xx | Multi-tenancy: the tenant from the token, the connection hook, the platform and tenant planes, memberships, jobs per tenant, roles; schema-per-tenant migrations; row mode's policies, keys and leakproof operators; a tenant's data as a closed graph that can move to its own database (members, per-tenant numbers and file prefixes, replica identities). Only when the profile's `tenancy` is `row` or `schema`; its test matrices (token paths × access changes, seeds × tenant statuses) are `references/tenancy-matrices.md` |

A review reads only the references its scope needs (Step 3). `references/deep-passes.md` holds the
architecture and resilience passes of path and whole-codebase reviews (Steps 3a and 3b).

Everything that differs between projects (conventions, thresholds, pagination style, token
revocation, accepted deviations) is in the project profile, `.claude/review-profile.md`.
**The profile overrides a reference rule only where it says so explicitly, with a reason.**

Principles:
- **A finding must be real.** Read the code before reporting. Every Critical and High finding is
  re-verified (Step 5). If unsure, label it "possible" and say why.
- **A deviation is an exception, never a pattern.** Code that breaks a guideline is either fixed
  or accepted by the author and marked, so nobody copies it (Step 7).
- **Fewer, grouped findings beat many trivial ones.** One finding lists every location of the
  same problem.
- Never change code before the report is shown (Step 6) and the user has chosen what to fix.

---

## Step 0: Load the project profile

Look for `.claude/review-profile.md` at the project root.

**If it exists:** read it. Also read CLAUDE.md (project rules) and, if present,
`.claude/crud-profile.md`.

**If it doesn't exist:** build it before reviewing anything.
1. Copy `~/.claude/skills/house-review/profile-template.md`.
2. Fill every key from the codebase: build file, CLAUDE.md, crud-profile, existing code,
   migrations. Don't guess. Mark keys with no equivalent as `none`.
3. Fill these from the code (ask the user when the code can't tell):
   - `modules`: one owner line per module. If you can't write one, that is itself a finding
     (ARCH-01);
   - `moduleContracts`: the owner's choice; suggest modulith style (`api-facades`) when services
     may be extracted later;
   - `instances`;
   - `hotPaths`;
   - `unpagedLists` (the developer's statement for each unpaged list the code doesn't bound, REST-11);
   - `archTests` (with their carve-outs, entered in the register);
   - `publishedContracts`.
4. Fill the **Conventions** section with the project's deliberate choices that differ from the
   reference rules, each with a *why* taken from CLAUDE.md or the user. If a choice has no
   documented reason, ask the user instead of inventing one.
5. Write `.claude/review-profile.md`. Show a short summary and get the user's confirmation
   **once**. Later runs reuse it without asking.

---

## Step 1: Choose the scope

Parse `$ARGUMENTS`:

| Argument | Mode | Files |
|---|---|---|
| `diff` | Branch diff | `git diff --name-only <mainBranch>...HEAD` (merge base) |
| `diff <base>` | Branch diff against another base | `git diff --name-only <base>...HEAD`: for a branch stacked on another feature branch, so only its own commits are reviewed |
| `commit <sha>` | One commit | `git diff --name-only <sha>~1 <sha>`: what one commit changed (crud-gen's commit of one entity); a file a later commit changed again is read as that commit has it (`git show <sha>:<path>`), and its findings say whether HEAD still has them |
| `commit <a>..<b>` | A range of commits | `git diff --name-only <a> <b>`: the commits after `<a>` up to `<b>` |
| `uncommitted` | Uncommitted changes | `git diff --name-only HEAD` plus untracked files (`git ls-files --others --exclude-standard`) |
| `all` | Whole codebase | Every source, test, resource and migration file |
| a path | Path or module | Every file under the path |
| empty | Ask | Use **AskUserQuestion** with the four modes. Show the changed-file count for diff and uncommitted, so the user can pick |
| any of the above, then `--focus <area>` | The mode, narrowed | The mode's files; only the rules of `api`, `data`, `tests`, `security`, `config` or `arch` load (Step 3, Gate 3) |

Then add the **related files** the rules need even when unchanged:
- the API interface (`controller/api/…Api`) of every changed controller, request DTO or
  response DTO, for the doc-drift check (REST-20);
- the tests of every changed class, for the test duties in lenses.md;
- the migrations touching every changed entity;
- `.claude/review-profile.md` and the deviation markers in scope (Step 4).

Lenses per mode (the lenses are in lenses.md):
- **diff, commit and uncommitted:** lenses 1, 2, 6 and 7, plus lens 8 when the change touches locks,
  versions, schedulers, uniqueness or shared state;
- **path and all:** all eight.

Architecture:
- **diff, commit and uncommitted:** check the changed files' imports for other modules' `repository`,
  `domain`, `dto.request|response` and `mapper` packages (ARCH-02/04/06/11);
- **path and all:** the full architecture pass (Step 3a).

---

## Step 2: Build and test

Run the profile's `buildCommand` and `testCommand` (normally one `verify` run). **Delegated
reviewer:** when the caller hands over the results of a build of exactly the reviewed code (one
`target/` and one Testcontainers set per working tree: a second build collides with work running
there), use them instead and say so in the report's header, with the commit they came from.
- A compile error stops the review: report it and ask whether to continue on the sources only.
- Record compiler warnings in the scoped files as SMELL-18 / JAVA findings.
- Record test failures as findings, with the test name and the assertion that failed. Don't
  diagnose them beyond reading the failure, unless they are in scope.
- A red ArchUnit rule is a finding citing the rule name and its `because`. Don't hand-check what
  a green ArchUnit rule guarantees: list it under "Checked and fine (ArchUnit)".
- Note the total duration. When an earlier report in `reportDir` records one, give the difference in
  one line.

---

## Step 3: Review

Load the rules by need (below), then apply each loaded rule to the files in scope, following its
**Detect** hints and honouring its **Not a finding when** clause and the profile. A *section* runs
from a `## ` heading to the next one; a *rule* from its heading (`###`, or `##` in config-baseline.md
and error-contract.md) to the next heading.

**Always loaded:** the profile; lenses.md's opening lines and its section *Lens 2: Security (SEC)*;
REST-10 and CFG-05, which SEC-05 relies on.

**Gate 1, pieces.** A piece's references load only when the project has the piece, and then for every
file kind and every focus:
- `tenancy` `row` or `schema` (review profile): tenancy.md without the other mode's section (*Row mode*
  or *Schema per tenant*), and tenancy-matrices.md;
- `media` (review profile; a profile without the key: the code has the piece's `media.api.Uploads`):
  media.md;
- `metadata` (review profile; a profile without the key: the code has the piece's
  `metadata.api.Metadata`): metadata.md.

**Gate 2, the kinds of the files in scope.** Path and all load every reference the pieces allow;
diff, commit and uncommitted load the union of the rows below for the files the scope changed. The
related files Step 1 adds are read for the rule that added them and add no references. **The core** is
rest-api.md, error-contract.md, persistence.md, code-smells.md, java.md, architecture.md and lenses.md's
section *Tests (TEST)*.

| Changed file | Loads |
|---|---|
| Java of a feature module: controller, API interface, request, response, filter and view records, entity and domain enums, repository and its implementation, service, facade, Manager, mapper, `*ConstraintErrorCodes` | the core; lenses.md's section *Lens 8: Concurrency (CONC)*; PERF-01 (the profile's `hotPaths` answer it on every change) |
| Test (`testRoots`) | the core |
| Changeset (`migrationsDir`), configuration (`application*.yml`, `config/*.yml`, `logback*.xml`) | the core without java.md; config-baseline.md |
| Messages file (`message_*.properties`) | error-contract.md; lenses.md's section *Tests (TEST)* |
| A scheduler, an outbound client (S3, HTTP) or a cache, besides its own row | REL-01, REL-08, PERF-05 and the rules they name: REL-02, REL-04, REL-09, OPS-05, OPS-06, CONC-01, ARCH-17, REST-13, REST-15 |
| Anything else: a class in `config/` or `common/` (security, filters, the exception handler), a listener or executor, a build or deploy file, a kind this table doesn't name | every reference the pieces allow |

When a file's kind, or whether a rule applies to it, is unclear, load the reference.

**Gate 3, `--focus <area>`** (Step 1) replaces Gate 2; the always-loaded rules and the pieces stay:

| Focus | Loads |
|---|---|
| `api` | rest-api.md, error-contract.md, REL-10, ARCH-13 |
| `data` | persistence.md; lenses.md's section *Lens 8: Concurrency (CONC)*; REST-08, REST-09, ERR-03 |
| `tests` | lenses.md's section *Tests (TEST)*; ERR-06, ERR-07, DATA-06, DATA-10 and DATA-19 (their **Detect** hints name test defects) |
| `security` | REST-09, REST-14 to REST-18, REST-22, REST-25; TEST-02, TEST-03, TEST-04 |
| `config` | config-baseline.md; DATA-19 |
| `arch` | architecture.md, code-smells.md |

In path and all, `arch` also runs Step 3a, which brings persistence.md's section *Transactions and
connections* (its transaction-semantics item), and `config` runs Step 3b, which brings OPS-07; the other
focuses run neither. The report's header names the focus. Its verdict is never READY TO MERGE: NOT READY
while a Critical or High finding is open (Step 6), otherwise its findings, or "no findings in <focus>".

**Rules a loaded file depends on** load with it, whichever gate loaded it: persistence.md brings REL-10
(DATA-18 merged into it); config-baseline.md brings PERF-03, REL-01, REL-04, REL-14 and OPS-06 (CFG-04,
CFG-06 and CFG-09 name them as the rules behind their keys). A loaded rule that points to one item of
a file not loaded reads that item alone: SMELL-10 the change-blast test and REL-04 the timeout table
(`references/deep-passes.md`, Steps 3a and 3b); SEC-07, without tenancy, the *Suspended account* row of
tenancy-matrices.md.

**Small scope** (up to `subagentThreshold` files in the profile, default 25): review it
yourself, file by file.

**Large scope** (path or whole codebase above the threshold): split it by module into 3–5
groups and start one reviewer per group with the **Agent** tool, all in one message so they
run in parallel. Each reviewer must get:
- the list of files;
- the paths of the references this review loads and of the profile, with the instruction to read
  them first;
- the instruction to be read-only;
- the exact finding format below;
- the instruction to group repeated issues and to list "checked and fine" items.

Run the test-suite lens (TEST-xx, CONC, PERF query counts) as its own reviewer in whole-codebase
mode. Do the REST pass, including doc drift, yourself across all controllers: it needs one view
of every endpoint.

### Steps 3a and 3b: the architecture and resilience passes (path and all)
`references/deep-passes.md`, read only in path and all (with `--focus`, Gate 3 says which).

Finding format (reviewers and you):
```
### <RULE-ID>: <short title>
- Where: path:line (all locations)
- Severity: Critical | High | Medium | Low
- What: 1–3 sentences, concrete
- Fix: concrete
- Tracked: <task id, and which part is new> | new
```

**Severity comes from the defect, never from a guessed data size.** A rule's "High on a hot path" means
any list, dropdown or public endpoint (and the profile's `hotPaths`); no rule raises a finding for a
"growing" or "large" table or tenant, which only the developer can know.

**Project-wide findings.** A path, diff or uncommitted review also meets causes outside its
scope: shared configuration (`application.yml`, timeouts, Jackson), the exception handler, a
`common` class. Report such a finding once, with `(project-wide)` in its title and the in-scope
symptom in **What**, and don't count it toward the scope's own findings in the summary. When an
earlier report or the tracker already holds it, list it only under "Previous findings" (open),
not as a new finding, so the same problem doesn't reappear in every module's review.

---

## Step 4: Deviation markers and the register

1. Find the markers in scope: `grep -rn "DEVIATION(" <scope>`.
2. A finding located exactly at a marked spot, for the same rule, is **not reported**. List it
   in the footer.
3. Compare the markers with the profile's deviation register:
   - a marker with no register entry, or an entry with no marker: report as **stale (Low)**;
   - a marker whose code has changed so much that the reason no longer applies: report as
     **stale (Low)**.
4. **Broken-window alarm:** when a rule has `deviationAlarm` (default 3) or more accepted
   deviations project-wide, report a **Medium** finding: "a pattern is forming". The fix is
   either to change the rule in the profile (with a reason) or to refactor the deviations.

---

## Step 5: Verify

For every Critical and High finding (yours and the reviewers'):
- Open the cited code and confirm the behaviour. Check line numbers: reviewers often cite them
  wrongly. Cite the method name when unsure of the line.
- Prefer proof: a call path, a missing constraint in the migration, a failing test you can name.
- Downgrade or drop anything you can't confirm. Keep "possible" findings only with a reason.
- Look for the finding's twins: the single and batch endpoints, user and admin variants, copies of
  the same logic. Report each one that shares the defect.

For every **Medium** finding: verify it the same way when a probe is cheap (one test or one SQL
statement); otherwise label it "possible" with what would confirm it. An unverified Medium sends the
fixer (or a generator) after a non-problem.

Also check any number you report (counts of endpoints, tests, files) by recomputing it.

- **A framework claim needs proof:** cite the framework source or javadoc from `~/.m2`, or a small
  experiment. Examples: what is ignored under virtual threads, default timeouts, what
  `cancel(true)` really does. The strongest proof is a **probe test**: a throwaway test class
  extending the project's test base that sends the real request (or reads the real bean) and
  prints the result, deleted right after.
- **Static reading shows the mechanism, not the magnitude.** List each magnitude question under
  "Runtime experiments still to run", with the result that would change the severity. Don't run
  load tests unless asked.
- **Re-check earlier findings:** read prior reports in `reportDir` and the project's trackers.
  Classify each tracked finding as fixed, open, stale or rejected, with the deciding line, so
  stale claims aren't reported again.
- **Push back on book prescriptions this system doesn't need**, listed under "Questionable
  recommendations, and why not". The house has already rejected these, with the reason:
  - a breaker in front of the only database (Hikari's wait + 503 already fails fast), a message
    broker, platform threads;
  - **raising the pool as a fix**: instances × pool must stay below `max_connections`, and a bigger
    pool only moves the queue into the database (OPS-10);
  - **`@Async`, WebFlux or R2DBC** instead of virtual threads + bulkheads (PERF-03);
  - **`show-sql` in prod** (CFG-02: datasource-proxy in dev only);
  - **migrations on a read replica** (a replica is read-only; DATA-19 bounds migration locks
    instead);
  - **`@DataJpaTest` slices** instead of ArchUnit (layers) + integration tests on the real database
    (behaviour);
  - **epoch `bigint` instead of `timestamptz`**: PostgreSQL already stores `timestamptz` as an
    8-byte integer, so the index is as fast and the type stays readable.

---

## Step 6: Report

**Diff and uncommitted:** print the report in the terminal; when the call says it reviews one
entity's generation (crud-gen Step 8), also write it as `<reportDir>/<yyyy-mm-dd>-<entity>.md`.

**Commit:** write `<reportDir>/<yyyy-mm-dd>-<short sha or range>.md` (a crud-gen entity's review:
`<yyyy-mm-dd>-<entity>.md`, crud-gen Step 8) and print a short summary with the path: a commit is
reviewed after the fact, and its report is the record.

**Path and all:** write `<reportDir>/<yyyy-mm-dd>-<scope>.md` (`reportDir` from the profile,
default `docs/reviews`) and print a short summary with the path.

The report comes **before** the questions of Steps 7 and 8, so the author decides with every
finding in front of them. Step 7 then adds its decisions to the report.

Each finding carries a **Tracked** line: the tracker task that already covers it (fully or in
part, saying which part is new), or `new`. Project-wide findings say `project-wide` in their
title (Step 3).

Report layout:
```
# Review: <scope> (<date>, commit <sha>)

Verdict: READY TO MERGE | NOT READY (n Critical, n High open)
Build: ok | failed · Tests: x passed, y failed · Duration: …

## Findings
(most severe first; format from Step 3)

## Missing tests
(test duties from lenses.md not met, per changed endpoint or class)

## API docs out of date
(REST-20 doc drift: endpoint → what changed → what the docs say)

## Accepted deviations in scope
(marker locations, by rule; stale markers; broken-window alarms)

## Decisions
(added in Step 7: per finding fix, tracked in <task>, or accepted; what was added to the tracker)

## Structural verdict (path and all)
(verdict, deep pattern, smallest refactoring move, architecture priority list)

## Timeouts and safeguards (path and all)
(the two Step 3b tables)

## Previous findings
(fixed, open, stale or rejected, with evidence)

## Checked and fine
(short list, so the reader knows what was covered; ArchUnit-guaranteed rules separately)

## Not investigated enough
(coverage gaps)

## Runtime experiments still to run

## Questionable recommendations, and why not

## Notes the profile overrides
(one line per convention, with its reason)
```

The verdict is **NOT READY** while any Critical or High finding is open and not accepted. A
finding that is tracked is still open: tracking it doesn't make the code ready. A `--focus` review
never says READY TO MERGE (Step 3, Gate 3).

---

## Step 7: Decisions: fix, track or accept

A **guideline finding** is a finding that is not a bug, security hole or data risk: style,
structure, naming, conventions, API shape. For guideline findings at Medium or Low, ask the
author with **AskUserQuestion** (up to 4 per call, grouped by rule). The options:
- **Fix it** (default);
- **Track it**: already a tracker task, or to be added as one (name the task: `T26`, or "new
  task"). Nothing is marked in the code: a tracked finding is planned work, not an exception;
- **Accept as an exception**, with a reason from the author.

Don't ask about findings the tracker already covers completely and that nothing new was found
in: list them in **Decisions** as "tracked in <task>". Ask only about new findings and new parts.

Findings the author chooses to track are written to the project's tracker (in the user's words
when they gave a reason): a note on the existing task, or a new task in the tracker's own format
and numbering, with a link back to the report. Ask before creating a tracker file that doesn't
exist.

Critical findings, and High findings about security or data integrity, are **never** offered
as deviations.

For each accepted deviation:
1. Add the marker comment directly above the code, using the project's comment syntax:
   ```java
   // DEVIATION(<RULE-ID>, accepted <yyyy-mm-dd>): <reason>. Exception, do not copy.
   ```
   For SQL or YAML, use `--` or `#`.
2. Append a row to the profile's **Deviation register**: rule, file and symbol, reason, date,
   accepted by.
3. Re-check the broken-window alarm (Step 4).

In whole-codebase mode with many guideline findings, ask per rule ("accept all N locations of
SMELL-21 for this reason?"), not per location.

Record every decision in the report's **Decisions** section.

---

## Step 8: Offer fixes

Before asking, check **where the fixes collide**. Examples:
- a `Retry-After` against client retries;
- a 503 on a COMMIT that may have landed, against non-idempotent POSTs (REL-11);
- a lock timeout against all-or-nothing batches.

Each proposed fix names the failure it prevents *in this system*.

Ask with **AskUserQuestion** which findings to fix now: all Critical and High, a chosen set, or
none (tracker only: the findings go to the tracker as in Step 7). Then:
1. Fix them, following the rule's **Fix** guidance, the profile and CLAUDE.md. **Compliant code to
   follow** is the house reference application, `~/.claude/skills-reference` (its `common/` and
   `config/` for infrastructure, its `todo` module for a feature): when a fix has a counterpart
   there, make the project's code match it rather than writing a new variant. Search for the
   finding's twins (single and batch endpoints, user and admin variants, copies of the same logic)
   and fix them too, or report them: a fixed single endpoint left its batch twin with the old
   authorisation.
2. Update the API-interface docs for every endpoint touched (REST-20).
3. Add the missing tests from the report for the code you changed.
4. Re-run the build and the full test suite.
5. Re-review only the changed lines against the same rules.
6. Report the outcome per finding: fixed, skipped, or no change needed.

Also offer:
- generating ArchUnit rule classes from the profile (architecture.md), when they are missing, or
  when an automatable guideline finding recurs;
- fixing the generator itself (`~/.claude/skills/crud-gen`), when a finding is in generated code.
  Generated code must pass this review.

Ask before adding any new dependency, Lombok included. In a project that has Lombok, the annotations a
rule's fix names (`@RequiredArgsConstructor` for JAVA-26, JAVA-16's on entities) need no question; ask
before any other Lombok annotation a class doesn't already use.

Never commit unless the user asks.
