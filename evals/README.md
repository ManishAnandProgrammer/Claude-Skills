# Evals: do the skills work?

`scripts/verify.sh` proves the reference application and the bootstrap copies. It never runs a model. This suite
runs the two skills headless against fixed cases, and grades the result by machine:

- **crud-gen generates production code that follows the rules.** Each generation case gives it an entity and the
  developer's answers. The result must pass three gates:
  - the build is green;
  - the change stays in its lane;
  - an independent house-review finds nothing at Medium or above.
- **house-review catches what the rules forbid.** Each review case plants one defect in the golden example module,
  and the review must report the expected rule on the expected file. A clean control must stay clean, which catches
  a review that invents findings.

Demo projects are no longer the test of a skills release. A demo is something you show; this suite is what you run.

## Running it

```sh
scripts/eval.sh --check                        # no model: every fixture renders, every patch applies, all compile (~5 min)
scripts/eval.sh                                # every case (see the cost below)
scripts/eval.sh --case 'r*'                    # globs over case ids, comma-separated: --case g1-room,c1-*
scripts/eval.sh --case g1-room --budget 10     # the --max-budget-usd of each model run (default 15)
scripts/eval.sh --case g1-room --keep          # keep the work copies (a failed case's copy is always kept)
scripts/eval.sh --prepare-review /tmp/rv       # a review copy at its baseline commit, for writing a new defect.patch
```

**Prerequisites:**

- `claude` signed in;
- Docker reachable for Testcontainers. When your shell isn't in the docker group yet, run
  `sg docker -c 'scripts/eval.sh ...'`;
- the Maven repository warm, because builds run offline (`-o`). A green `scripts/verify.sh` leaves it warm;
- `JAVA_HOME`, which defaults to `/home/manu/.jdks/temurin-25.0.5`.

**What the harness tests:** a snapshot of the working tree, taken when the run starts:

- what it covers: the skills, the reference, overlays, scripts, guides and docs, uncommitted files included;
- what that means: edits made meanwhile don't change a running eval;
- where the record goes: `results/<run>/release.txt` records the commit and the tree hashes.

**Results:** they go to `evals/results/<YYYYMMDD-HHMMSS>/`, which is gitignored:

- `summary.md`: one row per case, with its verdict, each gate, the cost and time of each model run, and the findings
  by severity. A section per case follows, with details:
  - the denied commands;
  - the questions asked;
  - the blocking and the Low findings;
  - the expected rules that were found or missing;
- per case:
  - the transcripts (`*.jsonl`, stream-json);
  - the system prompt each model run got (`*.system.md`);
  - each run's record (`*.run.json`);
  - each gate's JSON;
  - the harness's build log (`gate1.log`).

A case's work copy (`$TMPDIR/claude-skills-eval.*/<case>`) is a git repository. Read its history with
`git log -p baseline..` to see what crud-gen wrote.

## How a run works

Every model run is `claude -p` with these settings:

- **An isolated HOME.** It holds:
  - the snapshot's `crud-gen` and `house-review`, linked under `.claude/skills/`;
  - `.claude/skills-reference`;
  - `Documents/claude-skills`, which the skills call for `bootstrap-copy.sh --render` and the release;
  - `~/.m2`;
  - the login, `.claude/.credentials.json`, a symlink that is never read.

  No other skill, plugin, memory or CLAUDE.md is loaded.
- **Its flags:** `--output-format stream-json --verbose`. Its last line is the `json` result, with
  `total_cost_usd`, `permission_denials` and `structured_output`. It also takes:
  - `--no-session-persistence --permission-mode acceptEdits --permission-prompts none`;
  - `--allowedTools`, `--disallowedTools`;
  - `--max-budget-usd`;
  - `--append-system-prompt`, with `lib/prompts/*.md`: the unattended rules, and for crud-gen the case's business
    rules as the developer's answers;
  - `--json-schema` for house-review: `lib/review-schema.json`, `{findings: [{rule, file, line, severity, summary}]}`.

  The prompt itself is the slash command alone: `/crud-gen <entity>` or `/house-review commit <range>`.
- **Permissions:**
  - **Allowed:** `./mvnw *`, read-only commands, git reads, and `git add` / `git commit` for crud-gen, plus Edit and
    Write in the working directory (acceptEdits).
  - **A caveat:** both skills' frontmatter grants plain `Bash` while the skill runs, so the allow list is not the
    fence. The deny list is, because deny rules win:
    - `AskUserQuestion`;
    - the login;
    - `python`, `perl`, `node`, `bash -c` and `rm -rf`;
    - Edit and Write under `/home`.

    Every copy, HOME and snapshot lives under `$TMPDIR`.
- **Profiles:** both profiles are written before the run from `lib/profiles/`, as bootstrap leaves them. The prompt
  says the owner confirmed them.

**Generation case** (`generation/<id>/`). The harness renders a copy with the case's pieces and package
`com.acme.<gN>`, through `bootstrap-copy.sh`, like `verify.sh`. It then:

- writes `lib/coverage`'s minimums for those pieces into the pom;
- commits that as `bootstrap`;
- adds `fixture/` (the entity, its changeset in the master) and commits it as the baseline: the developer's
  entity, never applied anywhere;
- runs `/crud-gen` with a 3-hour timeout. Step 8 (the self-review) is skipped: the harness reviews instead.

Then the three gates:

1. **Build:** the harness itself runs `./mvnw -o -B verify` on what crud-gen left, which must exit 0. The skill
   claiming green isn't enough.
2. **Paths:** `git diff --name-only <baseline>` (untracked files included) lies within `allowed`, the case's
   allow-list of globs. A change to the core, the sign-in piece, the pom's minimums or a test it shouldn't touch
   fails.
3. **Review:** `/house-review commit <bootstrap>..HEAD`, report-only. The range covers the entity and crud-gen's
   change, so the changeset reads as new. It is a delegated reviewer handed gate 1's
   result, so it doesn't build again. It must report nothing at Medium or above. Low findings are listed. Gate 3 is
   skipped when gate 1 is red; `--always-review` runs it anyway.

**Review case** (`review/<id>/`). The harness renders one review copy per run: pieces `core,sign-in,example`,
package `com.acme.review`, its minimums, committed. Then, for each case:

- it copies that copy;
- it applies `defect.patch` as a commit of its own, with the message from the `## Commit message` section of
  `case.md`, so the message names no defect;
- it builds the result, which may be red, as for R1;
- it runs `/house-review commit <sha>`, handed the build;
- it scores the result against `expected.json`.

A defect case passes when every `expect` entry is matched: a finding whose rule id is one of `rules`, on a file
ending with `file` (one path or a list). Other findings are recorded as noise. A control (`"control": true`) passes
with no finding at Medium or above.

## The cases

| Id | What | Passes when |
|---|---|---|
| g1-room | plain CRUD of a shared catalogue: an admin-only write, an offset list, a searchable dropdown (`core,sign-in`) | all three gates pass |
| g2-expense | an owned record: a status workflow, `allowedActions`, a cursor list at `/mine`, `@NotAfterToday` | all three gates pass |
| g3-expense-claim | three parties at three reaches: an any-of detail, one endpoint per move, a new FINANCE role, a 90-day rule from `BusinessCalendar.today()` | all three gates pass |
| r1-missing-requires | `TagController.get` loses `@Requires` | AUTHZ-03 on TagController |
| r2-flag-permission | `markDone` built with `PLAN_READ_OWN`, its endpoint `@Requires(PLAN_EDIT_OWN)` | AUTHZ-07 on PlanService |
| r3-javadoc-restates-name | `/** Creates a tag. */` on TagService's methods | SMELL-19 on TagService |
| r4-query-per-row | the plan list reads each row's todo with a statement of its own | DATA-11 (or PERF-01, TEST-06, ARCH-15) on PlanService |
| r5-update-drops-version-check | `PlanService.update` without `checkVersion` | DATA-06 (or CONC-01) on PlanService |
| r6-sort-by-string | `root.get("name")` instead of `Tag_.name` in the order | REST-08 on TagRepositoryImpl |
| r7-undocumented-error-code | a new 409 `TAG_MERGE_TARGET_INACTIVE` thrown, `TagApi.merge` unchanged | REST-20 on TagApi or TagService |
| r8-today-from-the-clock | an ended-plan rule decided with `LocalDate.now(clock)` | DATA-37 (or JAVA-25) on PlanService |
| c1-clean-control | a correct private rename | nothing at Medium or above |

Each case's `case.md` says what it tests, why, and, for generation, the business rules and what a reader checks by
hand.

## What it costs

These figures come from the calibration on 2026-11-23, with claude-opus-5-5. See `results/` for the actual run.

| Run | Model cost | Model time | Harness build |
|---|---|---|---|
| crud-gen, G1 Room (33 turns) | $4.00 | 13 min | 2.2 min (713 tests) |
| house-review of G1's change (gate 3: three passes as subagents, 28 files) | $5.69 | 6 min | (gate 1's, reused) |
| house-review of a one-commit defect (R1) | $0.53 | 0.9 min | 2.7 min (1193 tests) |
| house-review of the clean control (C1) | $0.40 | 0.6 min | 2.4 min |

**Estimate for a full run.** Each figure below is an estimate:

| | Cost | Time |
|---|---|---|
| G1 | ~$10 | 21 min |
| G2 (workflow, flags, cursor list) | ~$12–18 | ~35 min |
| G3 (three parties, a new role) | ~$18–28 | ~60 min |
| the nine review cases | ~$5–15 | ~35–60 min, a build each |
| **total** | **~$45–70** | **~2.5–3 hours** |

The worst case is every run at its cap: 6 × $15 for generation plus 9 × $15 for review, $225 in all. G3's crud-gen
may need more than the $15 default: pass `--case g3-* --budget 25`.

Every run is capped at `--budget`. A run that hits the cap ends as `error_max_budget_usd` and its case fails. Cases
run one after another; a full run takes an afternoon. Run `--check` before every full run, and `--case` while
working on one rule.

## Adding a case

**A review case** (most new rules):

1. Run `scripts/eval.sh --prepare-review /tmp/rv`. It renders a review copy at its baseline commit.
2. Plant the defect in `/tmp/rv`, the smallest change that breaks one rule and nothing else. Then run
   `git -C /tmp/rv diff > evals/review/<id>/defect.patch`.
3. Write `case.md`: what it plants, the rule, and when it passes. Add `## Commit message` with a neutral message,
   never one that names the defect.
4. Write `expected.json`:
   `{"expect": [{"rules": ["REST-08"], "file": "todo/repository/impl/TagRepositoryImpl.java"}]}`.
   - `rules` lists every id that rightly names the defect.
   - `file` is a path suffix, or a list of them.
   - For a control, write `{"control": true, "expect": []}`.
5. Run `scripts/eval.sh --check --case <id>`, then `scripts/eval.sh --case <id>`.

**A generation case:**

1. Create `generation/<gN-name>/`. The id's first segment becomes the package (`com.acme.gN`).
2. Write these files:
   - `pieces`: the bootstrap pieces, which must have a line in `lib/coverage`. Measure a bare copy for new pieces.
   - `module`: one line, `name: owner statement`.
   - `entity`: the entity's path under the root package.
   - `fixture/java/**`: the entity and its enums, rooted at the root package, with `@ROOT_PACKAGE@` in package
     lines.
   - `fixture/changes/*.sql`: its changeset, numbered 0100 and up.
   - `allowed`: the paths crud-gen may change. Start from `g1-room/allowed`, and widen it only for what the case
     needs, saying why.
   - `case.md`: `## Business rules`, which are the developer's answers and the only part the model sees, and
     `## Expected outcomes`, for a reader.
3. Run `scripts/eval.sh --check --case <id>`.

When a rule's id changes, update every `expected.json` naming it.
