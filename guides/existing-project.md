# Guide: an existing (old) project

## Overview

**Goal:** bring a project that was written without these standards up to them, without
stopping feature work and without one huge risky rewrite.

**How it differs from a new project:**

| New project | Old project |
|---|---|
| Bootstrap everything on day 0 | First **measure** (a baseline audit), then adopt pieces in a deliberate order |
| Every rule applies as written | Some existing choices are legitimate **project conventions** (recorded with a reason); the rest are findings |
| Expected review result: clean | Expected result: a backlog, which shrinks release by release |
| API shape is yours to design | API shape is a **contract** with clients: changing it is a planned, announced breaking change |

**The strategy, in one line:** baseline audit → record conventions → fix by risk (security,
then transactions, then data integrity, then the test safety net, then structure) → all *new*
code via `crud-gen` → `house-review diff` on every PR, so nothing new adds to the debt.

---

## Details

### The first review builds the profile, and the profile decides the noise
The first `/house-review` run reads `CLAUDE.md`, the build file and the code, and writes
`.claude/review-profile.md`. Its **Conventions** section is the most important thing you
confirm:
- a deliberate choice **with a reason** is a convention: for example "hand-written mappers",
  "page-number paging in admin tables", "URLs are frozen because the mobile app depends on
  them". Code that follows a convention isn't reported;
- a choice nobody can justify is **not** a convention. Leave it out, and it becomes a finding.

Take ten minutes over this. A profile that lists bad habits as conventions hides exactly the
problems you want to find.

### Triage order (fix by risk, not by file)

| Order | Area | Typical findings | Why first |
|---|---|---|---|
| 1 | Security and configuration | auth bypasses, insecure defaults (a default profile, sandbox codes), secrets or OTPs in logs | Exploitable today |
| 2 | Connections and transactions | remote calls inside `@Transactional`, `REQUIRES_NEW` traps, no database timeouts | Takes the whole service down under load |
| 3 | Data integrity and concurrency | check-then-insert without a unique index, lost updates, records stuck "in progress", columns too short | Silent wrong data |
| 4 | Input validation | unbounded strings and lists, missing `@Valid` | Cheap to fix, removes classes of bugs |
| 5 | Test safety net | wrong-role tests on every endpoint, negative tests asserting `errorCode`, concurrency tests | Needed **before** refactoring |
| 6 | Performance | missing indexes, loading whole tables to aggregate | Grows with the data |
| 7 | API contract | error contract, `PaginationWrapper`, dropdown standard, status codes, OpenAPI | Breaking for clients: plan it with them |
| 8 | Structure | rich entities, splitting god classes, module boundaries, dead code | Safe only once 5 is done |

### Adopting the house infrastructure in an old codebase
`/crud-gen bootstrap` copies from the reference application with `scripts/bootstrap-copy.sh`, which
**never overwrites**: a file that exists is skipped and reported, and bootstrap then proposes the
differences between yours and the reference one by one. It only adds the missing pieces, asking
before every dependency. In an old project, run it **piece by piece**, because some pieces change
what clients see:
- **No client impact:** logging pattern, config baseline (database timeouts, Hikari), test
  base, ArchUnit, persistence base for *new* tables.
- **Client-visible (plan, announce, record in the frontend changelog):**
  - the error contract (adds `errorCode`, and validation errors become RFC 9457's `errors` list:
    `{"pointer": "#/email", "detail": ...}` for a body field, `{"parameter": "size", ...}` for a query
    parameter);
  - `PaginationWrapper` (`pageInfo` instead of whatever the lists return today);
  - the dropdown standard;
  - status code fixes (201, 204).

  Agree these with the client teams first; ideally batch them into one API version change.

### Role annotations become permissions (house-review AUTHZ-01 to AUTHZ-03)
A project that checks role names (`@RolesAllowed("TEACHER")`, `@PreAuthorize("hasRole('ADMIN')")`, an
`@AdminOnly`, a `RoleHierarchy`, a frontend table of which role may do what) moves to **Level 1 first**:
1. List every role check, endpoint by endpoint, and name the capability each one protects (`FEE_CONCESSION_GIVE`,
   `ATTENDANCE_MARK_ASSIGNED`), not the role: two endpoints the same role guards today may be two permissions.
2. Add `Permission` (each constant's area, reach and prerequisites) and give each existing role, in `Role`, exactly
   the permissions its endpoints let it use today: behaviour stays the same. A role hierarchy becomes explicit lists
   (the higher role lists the lower one's permissions too).
3. Map the token's role to its permissions in the converter (`TokenPermissions`), replace each role check by
   `@Requires(Permission.X)`, the authenticated-only ones by `@SignedIn`, the public ones by `@Public`, then turn on
   the ArchUnit rules and the generated 403 test (bootstrap copies them).
4. Move what isn't a yes/no permission out of the roles into the module's `<Resource>Policy`: ownership and
   assignment, states, amounts ("up to 2,000": a module setting, never a role column), dates.
5. Give the client `GET /users/me/access` and retire its copy of the role table (client-visible: plan it like the
   error contract). Only then, if admins must build roles themselves, the roles piece (Level 2) moves `Role` into
   tables with no endpoint, policy or client change.

### Existing tables and ids
- Keep existing ids (`Long`, UUIDv4) on existing tables. Set `idStrategy` in the profile to
  what new tables use (`uuidv7`). Converting ids is a data migration of its own: only if worth it.
- Don't edit applied Liquibase changesets: every change is a new changeset.

### ArchUnit on a legacy codebase
Generating the ArchUnit rules on day one would fail the build everywhere. Two options:
- fix the violations first, then add the rules (best for a small codebase);
- add the rules with `FreezingArchRule` (existing violations recorded, **new** ones fail the
  build). Every frozen violation goes in the deviation register, so the freeze stays a list of
  exceptions and doesn't become the pattern.

### Deviations in old code
Old code will have many guideline findings. Don't accept them one by one, or fix them all at
once:
- **accept per rule, with one reason**, for code you won't touch soon ("legacy reports module,
  rewritten in Q3"). Markers show the spot isn't a pattern to copy;
- the broken-window alarm (3 deviations of one rule) tells you when it is really a convention
  question.

---

## Playbook

### Week 1: measure
1. Install the skills (`~/Documents/claude-skills/scripts/install.sh`), check JDK and Docker,
   and make sure the project builds and its tests pass as they are. Note any already-failing
   tests: they are the first findings.
2. Create a branch, e.g. `chore/house-standards`.
3. If the project has no `CLAUDE.md`, run `/init`, then add what the code can't tell (which URLs
   are frozen, who the clients are).
4. Run `/house-review all`. The first run builds `.claude/review-profile.md`:
   - **review the Conventions section** (see Details). Keep only deliberate choices with
     reasons;
   - confirm `instances`, `moduleContracts`, the `hotPaths` and the `publishedContracts`.
5. Let the audit finish. Read the report in `docs/reviews/<date>-all.md`: the structural
   verdict, the timeout and safeguards tables, and the findings by severity.
6. Turn the report into a tracker (one task per group of related findings, numbered, with
   status, priority and a "Done on / Notes" line), e.g. `docs/house-standards-tasks.md`. Order
   the tasks by the triage table above.
7. Commit the profile, the report and the tracker.

### Weeks 2+: fix by risk
8. Take the next task. One task per branch or PR.
9. Fix it. Let Claude do it with the review's **Fix** guidance ("fix finding X from the
   report"), or yourself.
10. Run `/house-review diff`:
    - the task's findings are gone;
    - no **new** findings appear in the changed code;
    - accept or reject deviations where it asks.
11. If clients see the change, add the entry to `docs/frontend-api-changes.md`.
12. Merge, and mark the task done in the tracker with the date and notes.

### When you reach the API-contract tasks (triage 7)
13. Agree the changes with the client teams (error shape, `pageInfo`, dropdowns, status codes).
14. Run `/crud-gen bootstrap`, approving only the pieces you're adopting in this step. It skips
    what exists.
15. Move the endpoints over module by module, each with its changelog entry and updated tests.

### Before structural refactoring (triage 8)
16. Make sure the test safety net (triage 5) is done. Refactoring without wrong-role,
    negative and concurrency tests is how old code became fragile.
17. Add ArchUnit: fix-then-add, or freeze (see Details).

### From day 1, for all new work
18. **New entities go through `/crud-gen`.** The first run builds `.claude/crud-profile.md`
    from the code. List the project's real conventions under *Deviations* (for example "keep
    existing URLs"). Where the house infrastructure is missing, it offers bootstrap for just
    what it needs.
19. **Every PR gets `/house-review diff`.** The rule is a ratchet: old debt can wait, but no PR
    adds new debt.

### Every month
20. Run `/house-review all` again. Its "Previous findings" section marks each earlier finding
    fixed, open, stale or rejected, so you can see the backlog shrink. Re-prioritise the
    tracker from it.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| The first audit reports hundreds of Low findings | Review the profile's Conventions (step 4); accept per rule with one reason for code you won't touch soon |
| A finding contradicts a deliberate project choice | Record it as a convention, with the reason, in `.claude/review-profile.md` |
| Bootstrap wants to replace something you have | It skips existing pieces; if it treats yours as missing, tell it the class name and it updates the profile |
| `/crud-gen` output conflicts with old patterns around it | Generated code follows the house standard on purpose; list a real project convention under *Deviations* in `.claude/crud-profile.md` if the new code must match the old |
| The build already failed before you started | Fix or quarantine the failing tests first (each as a tracked task), or the review can't trust the build |
