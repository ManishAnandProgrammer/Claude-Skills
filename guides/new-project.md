# Guide: a brand-new project (fresh from Spring Initializr)

## Overview

**Goal:** start a Spring Boot project that follows the house standards from the first commit,
so `house-review` has nothing to say about anything except your business logic.

**What you end up with**, in about an hour before the first feature:

| Piece | What it gives you |
|---|---|
| Error contract | `ErrorCode` + mapper + `AppException` + `GlobalExceptionHandler` + messages: every error is Problem Details with a stable `errorCode`; outages answer 503 + `Retry-After` |
| API primitives | `ObjectWrapper`, `ListWrapper`, `PaginationWrapper` (explicit `pageInfo`), paging filters, dropdown standard (`DropdownItem`, `DropdownFilter`, `@DropdownCandidate`) |
| Persistence base | `AuditableEntity` (UUIDv7 id, audit columns, `@Version`), Liquibase baseline |
| Configuration | Hikari, JPA, PostgreSQL timeouts, Jackson strictness, actuator and metrics, graceful shutdown, virtual threads + admission control |
| Logging | Async logback, request id / trace / user on every line, JSON in prod, one request-completion line |
| Security skeleton | Stateless JWT, CORS from config, Problem Details for auth errors |
| Tests | Testcontainers integration-test base, ArchUnit rules generated from your module map |
| Profiles | `.claude/crud-profile.md` and `.claude/review-profile.md`, which hold every project-specific decision |

After that, every entity is one `/crud-gen` run: the generated code includes its tests and is
self-reviewed.

**The loop, in one line:** write the entity → `/crud-gen` → add the business rules →
`/house-review diff` → merge.

---

## Details

### The three commands

| Command | When | What it does |
|---|---|---|
| `/crud-gen bootstrap` | Once, right after creating the project | Asks the project-level questions, writes both profiles, **copies the shared infrastructure from the reference application** (`scripts/bootstrap-copy.sh`: tested code, never overwrites), adapts it, builds, then reviews the whole project |
| `/crud-gen path/to/Entity.java` | For every new entity | Checks the entity and its migration first (offers fixes), generates every layer + ErrorCodes + tests, builds, runs house-review on what it generated |
| `/house-review [diff \| uncommitted \| <path> \| all]` | Before every merge (`diff`), and a full audit now and then (`all`) | Builds and tests, reviews against the rules, verifies findings, asks you to accept or reject deviations, reports, offers fixes |

### Questions bootstrap asks (have the answers ready)

The list, with what each answer changes, is step 0 of
[crud-gen's bootstrap reference](../crud-gen/references/bootstrap.md): root package and modules
(one owner line each, ARCH-01), module contracts, instances, request suffix, sign-in and signup
policy, the breached-password check, token revocation, client-IP logging, the metrics scrape,
multi-tenancy, packaging, configuration metadata and rate limiting.

Bootstrap **asks before adding any dependency** (libraries-first rule) and shows the current
version and its compatibility. The copy script prints what each piece needs: springdoc-openapi,
Resilience4j, logstash-logback-encoder, Testcontainers, ArchUnit, the Hibernate metamodel
processor, the OAuth2 resource server, and for sign-in Redis and Bucket4j. You also need two
secrets in `.env`: `JWT_SECRET` and `PERSONAL_DATA_PEPPER`.

### What a generated entity looks like
Like the reference application's `todo` module (`~/.claude/skills-reference`): request records
with configurable `@MaxLength` limits (`config/limits.yml`), projections for lists, cursor paging
by default (offset only for bounded admin tables), `text` columns, an index per list order.

### What a finding means
- **Critical / High**: fix before merging. The verdict says NOT READY.
- **Medium / Low**: fix, or **accept as a deviation**. An accepted deviation gets a
  `// DEVIATION(<rule>, accepted <date>): <reason>. Exception, do not copy.` comment plus a row
  in `.claude/review-profile.md`. The third accepted deviation of one rule raises "a pattern is
  forming": then change the rule for this project, or refactor.
- **A finding in code that crud-gen generated is a bug in the skill.** Fix the code, then fix
  the skill in `~/Documents/claude-skills` and push.

### Where things are written
- `.claude/crud-profile.md`, `.claude/review-profile.md`: project decisions. Commit them.
- `docs/reviews/<date>-<scope>.md`: reports from path or whole-project reviews.
- `docs/frontend-api-changes.md`: every breaking API change, if the project has a frontend.

---

## Playbook

### Day 0: create the project
1. **Spring Initializr** (start.spring.io): Maven, Java 25, Spring Boot 4.x, packaging Jar.
   Dependencies:
   - Spring Web;
   - Spring Data JPA;
   - Validation;
   - Spring Security;
   - Actuator;
   - Liquibase Migration;
   - PostgreSQL Driver;
   - Lombok;
   - Docker Compose Support (optional).

   Not Testcontainers: the bootstrap brings its own setup (PostgreSQL 18, Redis, the media store),
   and Initializr's `TestcontainersConfiguration` would be kept in its place.
2. Unzip into your workspace, `git init`, and make the first commit (the untouched Initializr
   project).
3. Check the machine: JDK 25 (`java -version`), Docker running (`docker ps`; Testcontainers
   needs it), and the skills installed (`ls -l ~/.claude/skills` shows the two symlinks; if not,
   run `~/Documents/claude-skills/scripts/install.sh`).
4. Open the project root in Claude Code.

### Day 0: bootstrap
5. Run `/crud-gen bootstrap`. Answer the questions above, and approve or decline each
   dependency.
6. Read the summary it prints: what it created, and the decisions. Open both profiles and check
   them. Fix anything wrong now: later runs trust them.
7. Bootstrap ends with a build and `/house-review all`. **Expected result: no findings** (a few
   "pending business code" notes are fine). If something is reported, fix it before going on.
8. *(Optional)* Run `/init` so the project gets a `CLAUDE.md` describing it. Add one line saying
   that code is reviewed with `house-review` and generated with `crud-gen`.
9. Commit: "Bootstrap house infrastructure".

### Every new entity
10. Write the entity in its module's `domain/` package:
    - extends `AuditableEntity`;
    - fields with real column lengths;
    - `@ManyToOne(fetch = LAZY)` relations;
    - `@DropdownCandidate(...)` if screens will pick it from a list.
11. Run `/crud-gen src/main/java/.../domain/Entity.java`.
    - **Pre-flight** reports anything off in the entity or migration (no changeset yet, a
      missing index, a column too short) and offers the fix. Accept, and it writes a new
      Liquibase changeset.
    - It then generates the filter, payload / response records, mapper, repository + Criteria
      impl, service, API interface with OpenAPI docs, controller, ErrorCodes and messages,
      dropdown (if annotated) and the integration test class.
    - It builds, runs the tests, then reviews its own output, which must be clean.
12. Add the **business rules** the generator can't know (state transitions on the entity,
    domain validations), each with a positive and a negative test asserting the `errorCode`.
13. Run `/house-review uncommitted` and fix what it finds.
14. Commit.

### Before every merge
15. Run `/house-review diff` on the branch. Fix Critical / High. For each Medium / Low, choose
    fix or accept (with a reason).
16. If the API changed shape, add the entry to `docs/frontend-api-changes.md`.
17. Merge when the verdict is READY.

### Now and then (monthly, or before a release)
18. Run `/house-review all`. It adds the architecture pass (module map, cycles, ownership) and
    the resilience pass (timeouts, safeguards), and re-checks earlier reports. Work through the
    report like a backlog.
19. If a rule keeps being accepted as a deviation (the "pattern is forming" alarm), decide: change
    the project's convention in the profile, or refactor the code.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `/crud-gen` stops with "house infrastructure is missing" | Run `/crud-gen bootstrap` first |
| Tests fail to start: "Could not find a valid Docker environment" | Start Docker; your user must be able to run `docker ps` |
| Build fails on the Java version | Use JDK 25 (`JAVA_HOME`) |
| A rule doesn't fit this project | Don't ignore it silently: accept a deviation with a reason, or record a project convention in `.claude/review-profile.md` |
| The skills seem out of date | `~/Documents/claude-skills/scripts/sync.sh`, then check `~/.local/state/claude-skills/sync.log` |
