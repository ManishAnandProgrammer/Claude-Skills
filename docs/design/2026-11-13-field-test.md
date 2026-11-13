# Skill gaps found by the todo field test (2026-11-13)

What `/crud-gen bootstrap`, `/crud-gen` and `/house-review` did **not** get right the first time,
found by running them on a fresh Spring Initializr project. Source of the findings:
`docs/reviews/2026-11-13-all.md`. Each row names the skill file that needs the change. This list
becomes skill commits in `~/Documents/claude-skills` once the fixes are done and proven here.

Cause:
- **missing**: the skill doesn't say it at all;
- **vague**: it says it, but not concretely enough to get right;
- **no reference code**: the skill describes a piece in prose only, and the writer had to invent
  or copy it. It works only on a machine that has DynamicForm or news-pulse (the user's
  observation: a friend with only the zip has nothing to copy from);
- **reviewer only**: generation is fine, but house-review should check it.

## Bootstrap (`crud-gen/references/bootstrap.md`)

| # | Finding (review) | Cause | Skill change |
|---|---|---|---|
| B1 | Bad filter values leak class names and answer `VALIDATION_FAILED` (REST-06, High) | vague | ERR-02 table: binding failures (`FieldError.isBindingFailure()`) answer `REQUEST_PARAMETER_INVALID`, never the default message. Ship the handler as reference code |
| B2 | Non-English `Accept-Language` breaks every error (REST-06, High) | missing | MessageSource: `setDefaultLocale(ENGLISH)` and the resolver's supported locales; a test with `Accept-Language: de` |
| B3 | Unmapped MVC exceptions get no errorCode; some 5xx aren't logged (ERR-01/02) | vague | Handler: every `ResponseEntityExceptionHandler` type is mapped, by status class as a fallback; 5xx logged at ERROR |
| B4 | A `TaskScheduler` bean disables Boot's async executor, so the MDC decorator is never applied | missing | `spring.task.execution.mode: force`, plus a test that the executor exists |
| B5 | API docs on by default; no profile allow-list (SEC-03) | vague | Docs off in the base file, on in dev; a startup check of the active profile |
| B6 | `JWT_ACCESS_TTL` unbounded; refresh TTL not checked (REST-15) | missing | Validate the TTLs against `tokenRevocation` in the properties record |
| B7 | Unsalted SHA-256 for IPs and emails (SEC-04) | missing | HMAC with a pepper for personal data; SHA-256 only for random tokens |
| B8 | Sign-in rate limits configured in `common` (ARCH-08) | vague | The generic limiter in common, the sign-in limits in the user module |
| B9 | The fail-open counter mixes in reconnect attempts (OPS-07) | missing | Separate counters for "let through" and "reconnect failed" |
| B10 | Logout not rate limited; per-account lockout trade-off undocumented (REST-14) | missing | Every public sign-in endpoint is limited, and the per-account trade-off is stated |
| B11 | A benign duplicate refresh revokes every session (CONC-01) | missing | Refresh rotation with a revocation reason and a grace window for a just-rotated token |
| B12 | The pool stays at 10 in prod; readiness depends on an unchecked admission ≥ 2× pool ratio (CFG-01) | vague | Prod pool values in `application-prod.yml`; validate the ratio at startup |
| B13 | Sign-in/user API docs without 503; wrong `@Tag` claims; "bytes" wording vs `@Size` (REST-20) | vague | The bootstrap API interfaces follow the same doc template as generated ones (503 on every operation) |
| B14 | No failure-injection tests for admission, fail-open, a database 503, or readiness (TEST-18) | missing | Bootstrap ships these tests |
| B15 | No query-count capability in tests (TEST-06) | missing | Hibernate statistics in the test profile, and a helper on the test base |
| B16 | Unsafe `X-Request-ID` echoed (only CR/LF refused) | vague | An allow-listed character set |
| B17 | Dropdown `status` silently ignored on an entity without an active flag | vague | dropdown.md: 400 when `status != ACTIVE` and there is no active field |
| B18 | `page` unbounded: the offset wraps (JAVA-07) | missing | `PageFilter.page` gets `@Max` |
| B19 | Shutdown phase shorter than the longest request (REL-13) | missing | Derive `timeout-per-shutdown-phase` from pool wait + statement timeout |
| B20 | Refused logins invisible in prod (INFO under WARN) (OPS-04) | missing | A refused-login counter; job summaries at INFO for their own logger |
| B21 | Compose publishes on 0.0.0.0; no `log_lock_waits` (CFG-03) | missing | The compose template binds to 127.0.0.1 and sets the server options |
| B22 | No container definition, so no JVM flags (CFG-08) | missing | Bootstrap asks how the app is packaged and generates the Dockerfile or image config |
| B23 | Signup reveals accounts; no email verification (SEC-02) | missing | Bootstrap asks the signup policy (verified email or not) before generating sign-in |
| B24 | No breached-password check (REST-17) | missing | Bootstrap proposes `CompromisedPasswordChecker` as a dependency decision |
| B25 | No Prometheus registry; the metrics endpoint open to any user (CFG-06) | vague | Bootstrap's dependency list includes the registry; the metrics chain depends on `metricsAuth` |
| B26 | `commons-collections4` proposed but never used | vague | Propose a dependency only when generated code uses it |
| B27 | My own slip: test classes named `*Rules` were not run by Surefire | missing | ArchUnit classes end in `ArchTest`; bootstrap checks the test count after the first build |
| B28 | Found while fixing: with `auto-commit: false` (CFG-01), a bare `jdbc.update` fixture in a test is rolled back when its connection returns, so the test runs on data that isn't there | missing | The test base ships a transactional `sql(...)` helper, and tests.md says fixture writes go through it |
| B29 | Found while fixing: `PESSIMISTIC_READ` (`FOR SHARE`) lookups need the caller's write transaction | missing | Such service methods are `Propagation.MANDATORY` |
| B30 | Bootstrap needs a second secret, the personal-data pepper, next to the JWT secret | missing | The bootstrap questions and `.env.example` include it |

## crud-gen (`crud-gen/SKILL.md`, `references/tests.md`)

| # | Finding (review) | Cause | Skill change |
|---|---|---|---|
| G1 | Pre-flight passed an index that doesn't serve the **default** sort (DATA-10) | vague | Pre-flight: one index per offered sort, leading with the owner column and ending with `id` |
| G2 | A child's owner and its parent's owner linked only in the service (SMELL-16) | missing | When `ownerField` is set on parent and child: a `moveTo` method that checks the owner, and a composite FK |
| G3 | The insert-side FK violation answers the delete-side `*_IN_USE` (DATA-05) | missing | Lock the parent `FOR SHARE` on child writes, and map the FK only for deletes |
| G4 | Sort tests use data where every order gives the same result (TEST-01) | vague | tests.md: fixtures where each sort and direction gives a different order |
| G5 | Refusal tests assert only the status on PUT/PATCH/DELETE (TEST-08) | vague | tests.md row 14: assert the errorCode on **every** call |
| G6 | No test of a range filter's inclusive edge (TEST-12) | missing | tests.md: a boundary row for every range filter |
| G7 | Missing refusals: taken name on update, unknown id on PUT/PATCH, update payload validation, bad body enums | vague | tests.md: the refusal rows apply to update and action endpoints too |
| G8 | No lost-update race test (TEST-05) | missing | tests.md: a latch race on update with the same version; assert one 200 and the version in the database |
| G9 | Length literals in DTOs repeat the entity constants (SMELL-01) | missing | Payload `@Size(max = Entity.X_LENGTH)` |
| G10 | `like(lower(), ContainsPattern…)` copied per repository (SMELL-01) | missing | A `ContainsPattern.predicate(builder, path, query)` helper in bootstrap |
| G11 | Filter parameter `listId` vs `todoListId` everywhere else (SMELL-24) | vague | The filter's FK parameter uses the same name as the payload field |
| G12 | Deletes' possible 409 undocumented; PATCH action's 400 for a bad id undocumented (REST-20) | vague | The API doc template lists 409 on delete and the bad-id 400 on every `{id}` operation |
| G13 | My own test bug: MockMvc's `get(String)` re-encodes `%25` | missing | tests.md: send special characters with `.param(...)` |

## house-review

| # | Gap | Skill change |
|---|---|---|
| R1 | The reviewer prompts worked. The "framework claims need proof" rule paid off: three claims were confirmed by a real request (the binding leak, the locale, the executor) | Keep. Add the probe-test technique to Step 5 as the preferred proof |
| R2 | The test reviewer counted an absent failure-injection test as High; lenses.md says only SEC-related missing tests are High | Clarify in lenses.md: "High for SEC-related tests" means tests of a refusal or secret, not resilience |

## Owner's review of the generated code (2026-11-13)

Verified against the code and the framework (two experiments: `javap` on spring-web 7.0.10 for
HTTP QUERY; a Boot config-import probe with profiles).

| # | Observation | Verified | Decision / skill change |
|---|---|---|---|
| O1 | Comments explain *what* | Yes: most of 36 comments in the generated module; one is wrong (`Todo.changeStatus` "Moves the todo…"), one stale (the old FK name in `TodoListService.delete`) | Comments only for *why* in logic the code can't explain; no routine class or method Javadoc; OpenAPI annotations allowed. house-review SMELL-19 flags *what*-Javadoc too |
| O2 | `*Payload` vs `*Request` | Yes (crud-gen generated `*Payload`, sign-in has `*Request`) | HTTP input bodies are `*Request` (`TodoCreateRequest`, `TodoUpdateRequest`, `TodoStatusRequest`); GET parameters are `*Filter` |
| O3 | HTTP QUERY | Not in Spring 7.0.10 (`HttpMethod` has no QUERY). RFC 10008 (June 2026); support merged for Spring 7.1 (milestone), not GA | Keep `POST /x/search` for bodies a query string can't carry; adopt QUERY when Boot ships Framework 7.1 GA and the proxies and OpenAPI tooling support it. A REST rule note |
| O4 | Dropdown capped at 500, no next batch | Yes | **Search + cursor:** a small default batch, type-ahead `query`, keyset `after` cursor, response `pageInfo {nextCursor, hasMore}` (a cursor wrapper) |
| O5 | Split property files | Works: `spring.config.import` files honour both `on-profile` documents and `<name>-<profile>.yml` variants | **Split by concern:** `application.yml` imports `config/{db,server,security,observability,limits}.yml`; prod overrides as `db-prod.yml` etc. |
| O6 | No hard-coded constants; one source of truth | Yes: `ACTUATOR_PATH_PREFIX` (ignores `management.endpoints.web.base-path`), `MAX_PAGE`, `PageRequests.MAX_SIZE`, `DropdownFilter.MAX_LIMIT`, the rotation grace, the purge batch | Every limit and path from properties (`app.limits.*`, the actuator base path from `management.*`) |
| O7 | `PageFilter`: long values, no size cap; two representations | Partly: the fields are `int`, `page` is capped (`@Max`), size **is** capped at 100, but silently and by a constant, in a second class (`PageRequests`) | **Configurable constraints:** `@MaxPageSize`, `@MaxLength("todo.title")`, etc., whose validators are Spring beans reading `app.limits.*`; over the limit is a 400, never a silent clamp; one paging class; a springdoc customizer puts the limits into the schema |
| O8 | What is `ContainsPattern`? | It escapes `%`/`_` in search text | Replace with `strpos(lower(col), lower(q)) > 0` (no escaping needed) and drop the class; a `pg_trgm` index where contains-search must be fast |
| O9 | Criteria loads whole entities; one-to-many pagination; other pagination costs | Yes: list pages load full entities; a collection fetch can't be paginated in the database; OFFSET and count costs grow | **Separate design session** (projections, keyset scrolling, Blaze-Persistence or jOOQ as a library decision, count strategy), with a spike |
| O10 | Rate limiting design | — | **Separate brainstorming session** (per IP, per user, per endpoint and user, ...) |
| O11 | `@SingleInstanceSafe` name | Yes: it means the opposite of what it reads | **`@MultiInstanceSafe("why")`**; rename in house-review ARCH-12 and architecture.md |
| O12 | `MapWrapper` missing | Yes (bootstrap.md lists only three wrappers) | Bootstrap creates `MapWrapper`, from the news-pulse shape |
| O13 | `TEXT` everywhere (PostgreSQL Mistakes) | Agreed; it supersedes HelpBook point 5 | Columns `text`, no entity length constants; `@Size` (configurable, O7) on every request String and collection; house-review: a missing `@Size` is **High** unless justified |
| O14 | HelpBook wisdom | Read (6 points) | JSON keys as constants, or better a typed record (DATA-27); ids `updatable = false`; unique text index on `lower(trim(col))` (the note's `LOWER(LOWER(...))` is a typo), partial `WHERE col IS NOT NULL` when nullable (DATA-05); lowercase packages (naming rule) |
| O15 | (from earlier) The skills have no reference code | Yes | Ship a reference implementation inside the skill (to be designed) |

## Session 1: reference code inside the skills (decided 2026-11-13)

**Decision:** option A, a reference project plus a copy script. The repo is private, so there is
no CI: every skill change is verified locally before it is pushed.

| Piece | Design |
|---|---|
| `reference-app/` (repo root) | A real Maven project: the reviewed todo project, copied as a **new** copy (the old one stays for comparison), with the O1–O15 decisions and the session 2 and 3 outcomes applied there first. It holds the bootstrap layer (`common`, `config`, optional sign-in, test base, ArchUnit, split config files) and one golden crud-gen module (`todo`) |
| Fewer renames | The app name (error URN, metric prefix, pool name, `ApplicationName`) comes from `spring.application.name`; the root Java package is the only thing the script renames |
| `scripts/bootstrap-copy.sh --target <dir> --package <pkg> --with <pieces>` | Copies the chosen pieces (`core`, `sign-in`, `rate-limit`, `archunit`, ...); renames the package in sources, directories and resource references; **never overwrites**: an existing file is skipped and reported (partial adoption on old projects). Claude then adapts only the per-project parts (the bootstrap questions) |
| Golden example for crud-gen | crud-gen's SKILL.md stops carrying inline code templates that drift: it points at the golden module's files ("generate like `reference-app/.../todo/...`"). house-review's Fix guidance points at the same files as compliant examples |
| Stable path | Both installers link `~/.claude/skills-reference` → `<repo>/reference-app`, so the skills find it the same way on owner machines and on zip copies |
| Local verification | `scripts/verify.sh`: (1) `mvn verify` on `reference-app` (Testcontainers, so Docker is needed); (2) a copy-script smoke test into a fresh temp project, which must build green. A versioned `.githooks/pre-push` runs it when the push touches `reference-app/`, `scripts/` or a skill; `install.sh` sets `core.hooksPath` on owner machines; `--no-verify` is the explicit escape |
| Private-repo corrections | `setup-new-machine.md` (HTTPS pull needs credentials) and `share-as-zip.md` ("Download ZIP" isn't available to others): fix both |

Order: session 2 (data access and pagination), session 3 (rate limiting), then build
`reference-app` with every decision, then rewrite the skills around it, then re-run the field test
from a fresh Initializr project.

## Session 2: data access and pagination (decided 2026-11-13)

**Spike** (a throwaway copy; PostgreSQL 18; 50,000 todos for one owner plus 50,000 noise rows;
median of 15 runs; SQL captured with a Hibernate `StatementInspector`, plans from
`EXPLAIN (ANALYZE, BUFFERS)`):

| Approach | Page 1 | Row 20,000 |
|---|---|---|
| A: entities + offset (today) | 22.4 ms (14 columns + count) | 22.6 ms |
| B: Criteria projection + offset | 7.1 ms | 10.0 ms |
| C: Spring Data Specification + `.as()` + `.page()` / `.scroll()` | 18.3 ms (with count) | 1.9 ms (keyset) |
| D: Criteria projection + hand keyset | | 1.3 ms |
| E: plain SQL + keyset | | 0.5 ms |

- Plans: offset at row 20,000 reads 20,020 rows (3,317 buffers); keyset reads 20 (5 buffers). An
  exact `count(*)` scans all of the owner's rows: 9.1 ms at 50k, growing linearly.
- One-to-many (a page of 20 lists with their open-todo counts): fetch join + paging 111.6 ms (it
  loads 20,000 child rows); projection + count subquery 14.5 ms; two steps (page of ids, then a
  grouped count) 7.2 ms.
- **Hibernate 7.4 no longer paginates collection fetches in memory.** It pages the roots in a
  derived table and joins the children, so `fail_on_pagination_over_collection_fetch` never
  fires. It cost three rows of 1,000 children each, not a wrong page. DATA-26 and CFG-02 must be
  rewritten.
- Spring Data's fluent `.as()` really selects only the projected columns, and `.scroll()` emits
  correct keyset SQL (evaluated, not chosen).
- Blaze-Persistence: excluded; its newest integration targets Hibernate 7.2, and Boot 4.1.2 ships
  7.4.13. jOOQ 3.21.9 is Boot-managed; evaluated on paper (needs codegen and a second query stack).

**Decisions:**

| Topic | Decision | Skill change |
|---|---|---|
| Read mechanism | **Hand-written Criteria projections** (spike D): `*RepositoryImpl` keeps the Criteria API but selects `construct(Row.class, …)` into row records; never entities for list pages or dropdowns | crud-gen 4.7 rewritten around projections; DATA-11 / DATA-26: loading entities for a list page is a finding |
| Pagination | **Keyset by default** (user-facing and growing lists, dropdowns): `after` cursor, `pageInfo {nextCursor, hasMore}`, limit + 1 to detect more; **offset only for bounded admin tables** | A `CursorWrapper` + `CursorPageInfo`, a keyset helper (seek predicate over the sort keys + id, cursor encode/decode as opaque Base64url JSON); the profile lists which lists are offset |
| Counts | **None on cursor lists; exact on offset lists, skipped on a partial page**; a badge count is its own cheap endpoint | PERF-04 / DATA-11 aligned |
| Nullable sort keys | **A stored generated sort column**, e.g. `due_sort date GENERATED ALWAYS AS (coalesce(due_date, 'infinity')) STORED`, indexed with `id` | crud-gen pre-flight: a keyset sort key must be non-null; a nullable one gets a generated column and an index `(owner, sort_key, id)` |
| One-to-many on list pages | Never a fetch join; a projection with an aggregate subquery, or two steps (ids page, then one grouped child query) | DATA-26 rewritten; crud-gen pattern for parent lists with child data |
| Fetch-join pagination guard | Keep the setting (harmless), but don't rely on it | CFG-02 note: on Hibernate 7.4 the guard doesn't fire; the real rule is "no collection fetch on list pages" |

## Session 3: rate limiting (partly decided 2026-11-13; finished as the last skill change)

**Decided:**
- **Our own layer over Bucket4j + Lettuce**, shipped and tested in `reference-app` (no
  `bucket4j-spring-boot-starter`).
- **Store unavailable: decided per policy, with a local fallback.** Each policy declares
  `on-store-failure`: `LOCAL` (the default: an in-memory bucket at about limit ÷ instances),
  `OPEN`, or `CLOSED` (503, for cost-bearing endpoints).
- **Every scope is supported**: per IP, per account on sign-in, per user, per user + endpoint, and
  global per endpoint. **Bootstrap asks the owner which to enable.** The defaults: per IP on
  public endpoints, per user on authenticated ones, plus specific per-user rules for CPU- or
  RAM-heavy endpoints.
- Numbers only in configuration (`config/limits.yml`), never in code (O6).

**Open, for the deferred session:**
- **Where enforcement lives.** The owner's point: limits in the controller layer don't cover a
  second transport (e.g. GraphQL for the mobile app) without duplicating them. To explore: limits
  at the use-case (service) boundary, keyed from a transport-neutral caller context (user id,
  client address, policy), so REST and GraphQL handlers share one enforcement point; an edge proxy
  for the anonymous IP baseline; how body-derived keys (the login email) fit.
- How "CPU / RAM-intensive" endpoints are identified and declared (a policy per use case?), and
  whether such limits are a rate, a concurrency cap (like admission control, but per user), or both.
- The client contract beyond 429 + `Retry-After` (IETF `RateLimit` / `RateLimit-Policy` headers).
- IPv6 grouping (/64) and trusted-proxy handling of the client address.
- Until then `reference-app` keeps today's sign-in limiter unchanged.

## Build: reference-app (2026-11-13)

`~/Documents/claude-skills/reference-app/` built from a new copy of the reviewed todo project
(package `com.example.app`). Not committed yet: it lands together with the skill rewrite.

Applied:
- **O1:** what-comments removed.
- **O2:** `*Request` / `*Filter`.
- **O4:** cursor dropdowns.
- **O5:** config split into `config/{db,server,security,observability,limits,jobs}.yml` plus `-dev` /
  `-prod` variants.
- **O6:** no tunables in code. `spring.application.name` drives the error URN, the pool name,
  `ApplicationName`, the JWT issuer, the OpenAPI title and the logs' `app` field. Metric names are
  app-neutral. `Retry-After` ranges, limits and job settings come from configuration. The actuator
  path comes from `WebEndpointProperties`.
- **O7:** `@MaxLength("key")` / `@ConfiguredMax("key")` with Spring-bean validators reading
  `app.limits.*`. Over a limit is a 400; nothing is clamped. One `Paging` component (`PageRequests`
  is gone). A springdoc customizer writes the limits into the schema.
- **O8:** `TextSearch` (`position()`), so `ContainsPattern` is gone.
- **Session 2:** Criteria projections via `OffsetPages` and `KeysetPages` (opaque Base64url JSON
  cursors; a foreign or tampered cursor is a 400); `CursorWrapper` / `CursorPageInfo`;
  `TodoSummaryResponse` rows.
  - **Offset:** todo lists (bounded).
  - **Cursor:** todos, the dropdown.
  - The generated `due_sort` column, with an index per order.
- **O11:** `@MultiInstanceSafe`.
- **O12:** `MapWrapper`.
- **O13:** `text` columns; no entity length constants.
- **O14:** ids `updatable = false`; unique indexes on `lower(trim(...))`.
- **OPS-05:** the completion line logs a 500 when an exception escapes the chain.
- **Invariants stay in code** with a why-comment (bcrypt's 72 bytes, the 8-character floor,
  256-bit tokens).

Session 1 tooling:
- `scripts/bootstrap-copy.sh` (pieces `core`, `sign-in`, `example`; renames the package; never
  overwrites; prints the dependencies to propose; generates the Liquibase master);
- `scripts/verify.sh`;
- `.githooks/pre-push` (enabled by `install.sh` through `core.hooksPath`; disabled by
  `uninstall.sh`);
- the installers link `~/.claude/skills-reference`.

**Verified:**
- `scripts/verify.sh` is green: reference-app 224 tests, plus a fresh `bootstrap-copy` project
  under `com.acme.smoke` with 146 tests.
- The first smoke run caught three core tests that depended on the example module; they are now
  independent.
- Guards proven to fail without their fix: the limit-key test (a renamed key fails, naming it).

New gaps found while building:
- **B31:** core's tests need the sign-in piece (they sign users in). Documented and warned by the
  script.
- **B32:** a core test must never depend on the example module. `verify.sh`'s smoke copy enforces
  it.
