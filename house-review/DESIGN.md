# house-review: design log

Working log of the decisions taken while building the skill with the user, step by step.
Sources: the user's notes in `~/Downloads/Send-To-MIS/`.

## Sources

The rule files once named their sources in their headers; the notes live outside this repository,
so the list is kept here.
- **rest-api.md:** `rest-api-best-practices.md`, plus the decisions in this log.
- **code-smells.md:** `Coding_Standards.md` (23 smells), `Refactoring Book Summary.md` (22 smells),
  `Clean Code Book Summary.md`, merged without duplicates.
- **java.md:** `Effective Java Summary.md`, plus the house conventions stated while designing this
  skill.
- **persistence.md:** `Soft-Deletes-Are-A-Pitfall.md`, `AFTER_COMMIT-REQUIRES_NEW-Pool-Deadlock.md`;
  DynamicForm `notes/transactional-deep-dive.md`; DynamicForm reports
  `hibernate-persistence-context-and-fetching-review.md`,
  `database-concurrency-locking-and-transaction-review.md`, `erp-practices-2026-11-09.md`. The
  unique-text shapes (DATA-05), `updatable = false` ids (DATA-09), typed JSON content (DATA-27) and
  lower-case package names (SMELL-20) came from HelpBook.
- **lenses.md:** `eight-lenses.md`; DynamicForm `notes/testing-blind-spots.md`,
  `.claude/skills/monitoring`, `playbooks/jvm-observability-playbook.md`,
  `reports/resilience-review-2026-11-09.md`.
- **architecture.md:** DynamicForm `playbooks/low-level-architecture-review-playbook.md` (the
  severity principle), its ArchUnit tests (`LayerArchTest`, `ModuleLayerArchTest`,
  `ModuleBoundaryArchTest`, `NamingConventionArchTest`, `TenantBypassArchTest`) and its CLAUDE.md
  "Architecture Rules". The reference application's ArchUnit classes are `LayerArchTest`,
  `ModuleBoundaryArchTest`, `AggregateArchTest` and `ConventionArchTest`.
- **error-contract.md:** the user's DynamicForm implementation (`shared/exception/GlobalExceptionHandler`,
  `ErrorCode`, `ErrorCodeHttpStatusMapper`, `custom/AppException`, `TransientDatabaseFailure`,
  `config/MessageSourceConfig`, `messages/message_en.properties`).
- **config-baseline.md:** DynamicForm `application.properties`, `application-prod.properties` and
  `logback-spring.xml`, plus the reviews behind them (DynamicForm prod turns compression off
  because Nginx compresses).
- **dropdown standard (REST-13):** the user's `DropdownCandidate` contract (Espranza), merged with
  the house decisions of 2026-11-13.
- **Incidents behind some whys:** DATA-32 (a payment during a session switch answered a false 400
  "no current session"), ARCH-16 (`ObjectProvider<MetadataUpdateRule>` around a real service
  cycle) and TEST-05 (a "make current" race test red in 1 of 2 runs) come from run 5 of a field
  test.

## Rule merges (2026-11-14)
- DATA-18 (transient database failures → 503) merged into REL-10; the classification is ERR-03.
- CFG-09 (outbound clients) merged into REL-01, REL-04 and OPS-06.
- New: DATA-33 (composite owner FK). Lessons that lived only in crud-gen text became rule text:
  DATA-29 (`FOR KEY SHARE` for link rows), DATA-30 (`@OptimisticLock(excluded = false)`), JAVA-16
  (starting values, booleans), REST-09 (request booleans), REST-25 (a new role), TEST-07 (MockMvc
  traps).

## Run 6 fixes (2026-11-14)
From the review of run 6 (a leave desk) and its gap list G1–G30:
- New: ERR-06 (messages survive MessageFormat: doubled apostrophes, `{0,number,#}`, no arguments
  for constraint codes), DATA-35 (no overlapping ranges: an exclusion constraint), with golden code
  (`planning/`, changeset 0011, which also shows a parent in another module through
  `todo.api.Todos`).
- Changed: DATA-29 (one lock vocabulary by purpose: `FOR KEY SHARE` delete guards, `FOR SHARE` only
  for a re-checked column, `FOR NO KEY UPDATE` for the owner of a write; lock after visibility),
  MEDIA-04/06/07/11 (purpose role, the approval gate's code and delay, the owner locked first, record
  rules on the count, single-file purposes, who reads URLs), REST-09 (element strings, bounded
  patterns, NUL), REST-16 (records two parties see), REST-25 (role inclusion), ARCH-04 (the facade
  for a parent in another module; who maps a cross-module foreign key), ARCH-15 (lists scoped
  through another module, a Manager's shape), DATA-10 (a unique sort column is its own
  tie-breaker), DATA-12 (a microsecond clock), TEST-07 (company-wide lists, cached accounts not
  tokens, cheap bcrypt in tests).
- After the review of those fixes: a facade's view is never a response field (ARCH-06, enforced by
  `ModuleBoundaryArchTest`); plans got a cursor list filtered by the todo's id; dates carry
  `@EarliestDate` (an extreme year was a 500); exclusion-constraint writers are serialised per scope
  (overlapping concurrent inserts deadlocked); member rows a cascade removes are deleted set-based;
  upload policies are restored after each test; every test wait has a deadline.

## Lock audit (2026-11-14)

The owner adopted a principle for locks (DATA-29): a lock names the rule it protects that no
constraint, foreign key or `@Version` already holds. A lock that only turns a rare race's 503 or 409
into a nicer code is not added, provided the data stays correct, the answer is never 500 and a retry
is safe; races that are normal traffic are the exception. The audit removed each of the reference's
locks in turn on a scratch copy (repeated 6-way races and trigger-gated read-to-insert windows):
- **Removed:** the plans' owner advisory lock (without it 1 answer in 6 of a simultaneous overlap race
  is a 503 deadlock in the exclusion constraint, one row stored, its retry 409); the three
  `FOR KEY SHARE` delete guards (list, todo, tags): the foreign key's own check holds the data, and a
  delete that wins the read-to-insert window only changes the create's answer. That answer was the
  wrong code (409 `*_IN_USE`, or 500 for the unmapped tag-link keys), so foreign keys now map by side
  (`ConstraintErrorCodes.byForeignKeyOnWrite`, decided from `JDBCException.getSQL()`: PostgreSQL names
  the child table on both sides) to new placeholder-free `*_GONE` codes (404). Chosen over services
  catching the violation and passing the id: one declarative map entry per key, no try/catch in every
  child-writing service, and no guessing which of two parents (a todo's list or its tags) was gone.
- **Kept:** the todo's `findForUpdate…` (without it, double submits answered `DUPLICATE_RECORD` or 503
  deadlocks, and tag replaces racing the delete 500).
- **Replaced:** the refresh token's `PESSIMISTIC_WRITE` by a conditional `UPDATE … WHERE revoked_at IS
  NULL` with a rows-affected check (identical answers to the lock, measured); logout uses the same
  statement, so a logout racing a refresh never answers 409.

## Temporal rules (2026-11-14)

Harvested from the owner's three data-modeling notes (temporal modeling, data modeling, PostgreSQL),
then checked by experiment on the house stack (PostgreSQL 18.6, Boot 4.1.2, Hibernate 7.4.13,
JDK 25; scratch project, not the notes' demo). Where the experiments differed from the harvest plan,
the experiments won.
- **Adopted:** DATA-35's version-chain shape (`[from, to)`, `CHECK (from < to)` because `EXCLUDE`
  admits empty ranges, NULL open end because `'infinity'`, `LocalDate.MAX` and `Instant.MAX` all
  break under Hibernate 7, expression `EXCLUDE` as the default form, a STORED generated range only
  for `WITHOUT OVERLAPS`); DATA-36 (versioned documents, explicit `draftFrom` copies because
  `toBuilder` overwrote the published row, the `to_jsonb` drift test with its no-NULL fixture
  check); DATA-37 (no clock-driven stored status); DATA-38 (dated lookups name their date);
  JAVA-25's one clock read per boundary (never mixed with `now()`); DATA-05's NULLs-distinct unique
  key; DATA-10's `updated_at` index killing HOT updates (0% HOT; same-value rewrites keep HOT, so
  no `@DynamicUpdate`).
- **Not adopted from the notes:** `'infinity'` or `9999-12-31` as an open end; `OffsetDateTime`;
  no `@Version` with an `assert_to` guard; locking "the open version" by a mutable column (DATA-32);
  DB-clock assertion time; `bigint identity` + public uuid keys; `DEFAULT now()` audit columns; soft
  delete with views; range columns mapped in JPA.
- **DATA-36 has no golden code yet.** Planned shape: a per-owner `billing_rates` referent with
  `billing_rate_versions` (`[effective_from, effective_to)`), time entries gaining
  `billing_rate_version_id` and `rate_applied`; the module, its tests and a crud-gen variant come
  together, when a project needs it.
- **Considered (tier 2, a rule once a project needs it):** events are append-only rows with
  `occurred_at`, a mistake is a reversing row; two values of one enum true at once (`sub_status`)
  are two timelines; balances are sums of movement rows, not a mutable column; a retroactive change
  of a published version is its own privileged action with a reason; a child's period inside its
  parent's (PostgreSQL 18 `PERIOD` foreign key: coverage by adjacent parent rows, takes `FOR KEY
  SHARE`, NO ACTION only, needs `WITHOUT OVERLAPS` on the parent); events about versioned data carry
  `effectiveFrom` and say change or correction; temporal reads take `?on=` and return the period;
  personal data in its own erasable table, never on history rows; history a feature shows is
  modelled (DATA-36), never read from `audit_log` (DATA-08).
- **Considered (tier 3, know it exists):** full bitemporal tables (assertion time, withdraw /
  replace / supersede), episodes, future assertion time and semantic locks, tritemporality, 6NF
  satellites and warehouse SCDs, bitemporal joins, partitioning by `assert_to`, fillfactor and
  per-table autovacuum tuning (steady state reached about 70% HOT at the default fillfactor), a
  data-driven transition table.

## Step 1: shape (decided 2026-11-13)

- **Name:** `house-review`. The built-in `/code-review` already exists, so this name avoids
  clashing with it.
- **Scope:**
  - Supported modes: branch diff vs main, a path or module, the whole codebase, and uncommitted
    changes.
  - Invoked with no argument, it asks which mode to use and offers those four.
  - An argument (a path, `diff`, `all`, `staged`) selects the mode directly.
- **Output:** a verified findings report, then an offer to fix chosen findings. It never changes
  code without asking.
- **Profile:** its own `.claude/review-profile.md` per project, built on the first run from
  CLAUDE.md and crud-profile, like crud-gen. It holds accepted deviations from the notes (never
  reported) and thresholds.
- **Layout:**
  - `SKILL.md`
  - `profile-template.md`
  - `references/`: `rest-api.md`, `code-smells.md`, `java.md`, `persistence.md`, `lenses.md`
- **Rule format:** each rule has:
  - an ID;
  - the check;
  - how to detect it;
  - a default severity;
  - when it doesn't apply.

## Step 2: rule catalogues (in progress)

### 2a: rest-api.md

Draft rules REST-01 … REST-19, taken from rest-api-best-practices.md.

Decided:
- **Actions:** PATCH for a state change on the resource (activate, approve, reject, dismiss).
  POST for an action that creates something or starts processing (copy, regenerate, retry,
  replay).
- **Status codes beyond the note:**
  - 204 for any success with no body (not only DELETE);
  - 202 for accepted asynchronous work;
  - 429 + `Retry-After` on rate limits.
  - A `Location` header on 201 is **not** required.
- **Ownership:** a record that exists but belongs to someone else answers **404**, not the
  note's 403, so strangers can't enumerate ids. 403 is only for a missing role.
- **Pagination:** cursor for growing or feed lists; offset (with a size cap) is fine for admin
  tables. The profile lists which lists are which.
- **OpenAPI:**
  - Docs live on API interfaces (`controller/api/XApi`) carrying `@Tag`, `@Operation` and
    `@ApiResponse`; the package-private controller implements the interface. DTO fields carry
    `@Schema`.
  - **Doc-drift rule (every run):** for each endpoint in scope, compare what the code does with
    what the interface documents:
    - parameters and request body fields;
    - response type and fields;
    - status codes returned;
    - exceptions thrown by the service, and so the error responses;
    - validation constraints.

    Any mismatch is a finding, and the fix updates the docs. In diff mode, a changed DTO,
    controller method or service exception with no matching change to the API interface is
    flagged.
- **Multi-line strings use text blocks** (`"""`). This is a project-wide rule, not only for
  docs; it goes in java.md.
- **Hostile text:** `@NotXss` (or a documented output-escaping policy) on every free-text field.
  `@NoEmoji` only on the fields the profile lists (codes, identifiers), never on content.
- **Token revocation:** a profile key, `tokenRevocation`:
  - `blacklist`: a jti blacklist with a TTL in any store. DynamicForm uses PostgreSQL behind
    Caffeine, because it accepts no vulnerable window at all;
  - `short-lived(<max minutes>)`: news-pulse uses `short-lived(15)`, accepting the 15-minute
    window;
  - `session-version` (2026-11-15, the bootstrap piece of that name, opt-in): one number per account
    (per membership with tenancy) in every token, compared per request through Redis, raised by every
    access change in its transaction (SEC-07). No token is stored, so nothing in the store is a live
    login; the blacklist's per-token rows and its missed-token race don't exist. Redis down: one
    indexed database read per request behind a circuit breaker, never "accepted".

  The rule checks the code against the chosen mode.
- **Passwords:** both checks: the composition `@Pattern` (upper, lower, digit, special) **and**
  length 8–72 bytes (the bcrypt limit) plus a breached-password check.

Small REST defaults (stated to the user; no objection yet):
- `POST /x/filter` with a body is allowed when a query string can't express the filter.
- Wrapper key names come from the profile.
- Rate-limit IP: `getRemoteAddr()`; `X-Forwarded-For` only through a trusted-proxy
  configuration.

### 2b: code-smells.md

SMELL-01 … SMELL-26 merge Coding_Standards (23), Refactoring (22) and Clean Code. The
Coding_Standards §10 "fix" (reuse the entity as a DTO, reflection-based mapping) is dropped: it
contradicts the note's own "DTOs at API boundaries" rule.

Decided:
- **Thresholds** (overridable in the profile):
  - a method over 30 lines (blank and comment lines not counted);
  - more than 4 parameters (records and canonical constructors exempt);
  - a class over 300 lines.
- **Entities are rich, DTOs are plain:**
  - entities own their state transitions and invariants (no public setters for status or
    timestamps);
  - DTOs and records are pure data;
  - an anemic entity whose rules live in services is a finding.
- **Static methods** only for factories (`of`, `from`, ...) and final stateless utility classes
  with a private constructor. A static method that hides dependencies, state or I/O is a
  finding.

### Deviations and conventions (decided)

- **A deviation is an exception, never a pattern** (the broken-window concern). When the review
  finds code that breaks a guideline (a note rule, or a profile convention), it shows the code
  and asks the author to **accept or reject** it:
  - **Rejected:** it becomes a finding to fix.
  - **Accepted:**
    1. A marker comment goes right at the code, so no person or LLM copies it:
       `// DEVIATION(<RULE-ID>, accepted <yyyy-mm-dd>): <reason>. Exception, do not copy.`
    2. An entry goes in the profile's deviation register (rule, file, reason, date, who
       accepted).
- Marked code is not reported again. Every run lists the markers in a footer, and flags stale
  ones (a marker or register entry whose code is gone or changed).
- **Broken-window alarm:** when one rule reaches **3** accepted deviations, the review flags
  that a pattern is forming: either change the rule in the profile or refactor the deviations.
- **Project conventions** that differ from the notes are recorded once in the profile, each
  with a *why*. Code that follows them is fine; code that breaks them is a deviation (ask and
  mark). The footer lists which notes the profile overrides.

User conventions (apply to every project, so the skill encodes them):
- **Streams:**
  - Streams are good for data pipelines: map, filter, collect, reduce.
  - A finding is a **violation of functional style**: side effects inside the stream, such as
    `forEach` that mutates, `peek` with side effects, or lambdas that change outer state or do
    I/O. When a side effect is needed, use a `for` loop so the intent is explicit.
  - **Readability:** complex work in a stream step goes into a named method used as a method
    reference. Multi-line lambdas that pack logic into the pipeline are a finding.
  - This replaces the old crud-gen / CLAUDE.md rule "never streams, always for loops"
    (sync pending).
- **Interfaces** only when a type has (or truly needs) more than one implementation. No
  interface per service or class.
- **Sync:** after the skill is done, update the crud-gen SKILL.md coding conventions and
  news-pulse CLAUDE.md to this stream rule.
- **Test fakes don't count** as a second implementation (Mockito mocks classes). What does
  count: a real production implementation, or an external-system boundary with a dev or mock
  implementation (HTTP client, SMS/email sender).
- **Optional only as a return type.** Never as a field, a parameter, or a wrapper around a
  collection. Never `.get()` without a check; use `orElseThrow`.
- **Lombok on entities:** class-level `@Getter` + `@SuperBuilder`. `@Setter` only on fields
  that are freely editable. Never `@Data`, class-level `@Setter`, or
  `@EqualsAndHashCode` / `@ToString` that touch relations.
- **Exceptions** follow the REST note:
  - one `ErrorCode` enum (protocol-agnostic);
  - one `ErrorCode` → HTTP mapper as the only bridge to HTTP;
  - i18n messages through a `MessageSource`;
  - a small exception hierarchy;
  - a new exception class only when callers must catch it specifically.

  The user will share the exact implementation (with i18n). Until then, the rule checks the
  principles: no copy-paste exception classes, a stable code on every error.
- **Null checks:** `Objects.isNull` / `Objects.nonNull`. A plain `== null` is a Low finding.
- **No `var`.** Always explicit types, because reviews on GitHub and other tools can't hover to
  see the type.
- **Comments:** only intent, warnings and non-obvious contracts. No mandatory Javadoc on
  services: the REST contract is documented on the API interfaces. Javadoc is required only on
  library-style public APIs (shared modules).
- **Tests check one behaviour each.** Several asserts are fine if they verify one behaviour. A
  finding is a test that checks unrelated behaviours (a name joined with "And", or several
  action-then-assert sequences). Fix: split, or use a parameterized test.
- **Test names:** a camelCase sentence describing the behaviour, plus a `@DisplayName` with a
  readable sentence, because long method names are hard to read.
- **Logging:**
  - no personal data or secrets (emails, phone numbers, tokens, OTPs, passwords): log ids, and
    mask anything that can't be avoided;
  - `{}` placeholders, never string concatenation, with the exception passed as the last
    argument;
  - level policy:
    - ERROR: a person needs to act now;
    - WARN: handled, but abnormal;
    - INFO: business events;
    - DEBUG: diagnostics;
    - no INFO inside loops or hot paths.

  The user will share their log pattern; the `[TAG]` prefix was not chosen, so it waits for
  that.
- **Visibility:**
  - package-private unless another package uses the type;
  - controllers, mappers, repository implementations and helpers are package-private;
  - fields are `private final` where possible.
- **Mappers are hand-written, never MapStruct.** Complex mappings need code anyway, it is one
  less framework to learn, it is more flexible and the bundle is smaller.

### 2c: persistence.md

Decided:
- **Deletes follow the soft-delete note:**
  - `deleted` / `deleted_at` / `@SQLRestriction` / `@Where` flags are findings;
  - business states are a status enum;
  - true removal is a hard `DELETE`, with an archive table when recovery or audit needs it;
  - personal data must be truly deletable.
- **Change history is captured at database level with PostgreSQL triggers, not Envers:**
  - one generic trigger function;
  - one append-only `audit_log` table with old and new rows as `jsonb`.

  It captures bulk updates, native SQL and manual fixes too. The rule requires the app to pass
  the acting user to the database (`set_config('app.user_id', …, true)` at the start of each
  transaction); without it, history records *what* changed but not *who*. The profile lists
  the audited tables. Replaces the Envers recommendation in the soft-delete note: the user
  wrote that note before knowing about the trigger approach.
- **Primary keys are time-ordered UUIDv7** (sequential inserts, no index scatter). On
  PostgreSQL 18, use `DEFAULT uuidv7()` or Hibernate's v7 generator. Random v4 on a new table
  is a finding. news-pulse switches right away (it is dev-only).
- **Lenses per mode:**
  - diff and uncommitted: lenses 1, 2, 6, 7, plus 8 when concurrency-related code is touched
    (locks, versions, schedulers, shared state);
  - path or module, and whole codebase: all 8.

### 2d: lenses.md and the run process

Decided:
- **Tests required with new or changed code** (missing ones are findings):
  - positive **and** negative cases, not just happy paths;
  - a wrong-role (403) and no-token (401) test per protected endpoint, plus a 404-for-another-user
    test where there is ownership;
  - a hostile-input test (`<script>`, oversized) per new free-text input shape;
  - a concurrency test (parallel requests) for new state transitions, dedupe or uniqueness
    logic;
  - a query-count test for new paged list endpoints.
- **Every run builds and tests:** compile, plus the full test suite. Compiler warnings and test
  failures are reported as findings.
- **Report:**
  - diff and uncommitted: in the terminal;
  - path, module and whole codebase: saved as `docs/reviews/<yyyy-mm-dd>-<scope>.md`.
- **Severity:**
  - Critical: exploitable now, or data loss;
  - High: bug, security, integrity or scaling risk;
  - Medium: maintainability cost;
  - Low: polish.

  The verdict is "not ready to merge" while any Critical or High finding is open and not
  accepted as a deviation.

### 2e: java.md

JAVA-01 … JAVA-15 come from Effective Java plus the conventions above.

Decided:
- **Entity `equals` / `hashCode`:** either no override (identity), or an id-based override done
  right (constant `hashCode`). Never Lombok-generated on entities.
- **Entity collections:** the getter returns an unmodifiable view; changes go through
  `add…` / `remove…` methods that keep invariants (Refactoring: Encapsulate Collection).
- **`final`** is required only on fields (`private final` where possible). Not required on
  local variables, parameters or classes (Spring proxies need non-final classes).

## Step 3: write the files (done 2026-11-13)

Written: SKILL.md, profile-template.md, and references/ (rest-api, code-smells, java, persistence, lenses).

## Step 4: trial run and tuning (next)

## Step 5: sync crud-gen and CLAUDE.md (streams rule, no var) (after the trial)

## Step 6: DynamicForm knowledge merged (2026-11-13)

The user pointed to `~/IdeaProjects/DynamicForm` as the source for:
- the GlobalExceptionHandler;
- HikariCP, JPA and Tomcat tuning;
- the log pattern;
- "much more".

Three parallel readers extracted rules from:
- the transactional deep dive;
- the Hibernate, concurrency and resilience reports;
- `testing-blind-spots.md`;
- the monitoring skill;
- the JVM playbook;
- the review playbook;
- the ArchUnit tests;
- CLAUDE.md.

New files:
- `references/error-contract.md` (ERR);
- `references/config-baseline.md` (CFG);
- `references/architecture.md` (ARCH plus ArchUnit).

Rules added:
- DATA-15…31;
- PERF-01…04;
- REL-04…14;
- OPS-04…09;
- TEST-08…20;
- JAVA-18…24;
- SMELL-27/28;
- REST-22.

SKILL.md gained:
- Step 3a, the architecture pass;
- Step 3b, the resilience pass;
- verifying framework claims and re-checking earlier findings;
- the "where the fixes collide" check;
- the ArchUnit offer.

Decisions (user, 2026-11-13):
- **403 vs 404:** 404 when the record isn't visible to the caller; 403 when it is visible but the
  action is refused.
- **No `var` anywhere, tests included.** DynamicForm's `var` is legacy, not the pattern.
- **Interfaces:** a single implementation is allowed for a published module contract that breaks
  a cycle. Not for "might switch the store later".
- **Structural problems are High**, and never downgraded because the code still works.
- **Module contracts are the project owner's choice** (`moduleContracts`). The user's preference
  is modulith style: the speed of a monolith, with services extractable later.
- **Optional:** `orElseThrow`, `map`, `ifPresentOrElse`; `isPresent()` + `get()` only around side
  effects.
- **Client IP in logs:** configurable in application properties, with the reason documented.
- **Request-completion log line:** must exist, with an on/off property.
- **DynamicForm's log setup is the house pattern.**
- **Health is unauthenticated** (the load balancer needs it). **The metrics scrape is secured**;
  the mechanism is still being decided (`metricsAuth`).
- **ArchUnit:** recommended in every project, and the skill offers to generate the rule classes.
- **All four process additions accepted:** the architecture pass, the resilience pass,
  re-checking earlier findings, and runtime experiments with the fixes-collide check.

Technical calls (no question needed):
- **DATA-02 reworded:** the deadlock is REQUIRES_NEW **without** `@Async`; `@Async` + AFTER_COMMIT +
  REQUIRES_NEW is the user's documented fix.
- **DATA-09:** Hibernate 7.4.13 ships `UuidVersion7Strategy`. Generate in the application, not
  with a database default, to keep insert batching.
- **JAVA-03:** mappers and services are public in the layered layout (different packages); only
  controllers, `*RepositoryImpl` classes and same-package helpers are package-private.
- **Fail-fast `IllegalStateException`** is for bugs only (500); errors a client can cause are
  `AppException(ErrorCode)`.
- **Hand-written infrastructure from DynamicForm is not copied:** the admission-control filter
  becomes a Resilience4j bulkhead or `@ConcurrencyLimit` (libraries-first); the transient-failure
  classifier stays plain code in the exception handler.

## Step 7: crud-gen aligned with house-review (done 2026-11-13)

The user's goal: on a new project, code crud-gen generates gets almost no house-review findings.
crud-gen now:
- reads the house-review references as its source of truth (a reference wins over crud-gen's
  own text);
- runs house-review on its own output as its final step (Step 8);
- treats any finding in generated code as a defect in the crud-gen template.

Decided:
- `/crud-gen bootstrap` sets up the shared infrastructure once per project
  (`references/bootstrap.md`);
- the integration test class is always generated (`references/tests.md`);
- the entity and migration pre-flight reports, offers the fix (entity change plus a new
  changeset), then generates.

What changed in generated code:
- records for Payload and Response;
- an API interface with OpenAPI docs;
- 201 / 204 statuses;
- ErrorCodes, mapper entries, messages and constraint-name mappings instead of not-found strings;
- delete loads first (404), and an FK violation answers 409;
- lists always paged, with a sort enum, an id tie-breaker and the count skipped on a partial page;
- `@Size` on every String, and `@NoEmoji` only on profile-listed fields;
- `version` in the update payload, and update flushes the managed entity (no merge);
- Lombok only where used;
- a package-private RepositoryImpl;
- stream mappers;
- nested child resources;
- ownership answers 404;
- media: no storage call inside a transaction;
- dropdowns capped and documented.

## Step 5 (sync) done: CLAUDE.md and crud-gen conventions now carry the stream rule and no `var`.

## Step 8: PaginationWrapper (2026-11-13)

The user asked for an explicit paginated wrapper instead of page details hidden in `extraDetails`.
- `PaginationWrapper<T>` holds `response`, an explicit `pageInfo` (`PageInfo`: number, size,
  totalElements, totalPages, hasNext, hasPrevious, all with `@Schema`) and optional
  `extraDetails`.
- `ListWrapper` is only for lists bounded by nature (dropdowns, small fixed sets).
- Both skills follow it:
  - REST-11 and REST-08 (house-review);
  - TEST-11 and TEST-12 (house-review);
  - crud-gen SKILL, profile template, bootstrap, tests and dropdown.
- Implemented in news-pulse first. Breaking change: `extraDetails.page` → `pageInfo`.
- Cursor lists will get an equivalent explicit cursor `pageInfo` when the first one is built.

## Step 9: dropdown standard (2026-11-13)

The user standardised dropdowns in another project; the frontend then needed one component for
all of them. Shared reference: their `DropdownItem` class (`Object value`, `String label`) and
`ListWrapper<DropdownItem>` endpoints at `<resource>/dropdown`.

Decided:
- **`DropdownItem` is a record** with the same JSON `{value, label}`; null-checked; `@Schema`
  documents `value` as a UUID string or a number.
- **The service builds the wrapper**, as for every endpoint (the user's snippet wrapped in the
  controller; the house rule wins).
- **Query options are one generic parameter object** (`DropdownFilter`: status, sort, limit,
  search, with ACTIVE / LABEL_ASC / 500 as defaults). Anything beyond it comes from a subclass
  with extra parameters (the user: "If there is need of anything beyond the basic features … then
  new params can be used").
- **Entities opt in with a house `@DropdownCandidate(value, label, active)` annotation**, created
  by bootstrap. The original Espranza annotation can be shared later to align attribute names.

Encoded in:
- house-review REST-13;
- the crud-gen references (dropdown, bootstrap, tests), SKILL Step 4.11 and the profile templates.

**Aligned to the user's original `DropdownCandidate.java` (same day).** Kept from it:
- the annotation's attribute names: label, displayField, valueField, mandatoryFilters,
  optionalFilters, activeField, sortField, sortDirection, searchable, cacheable;
- the constructor-expression projection;
- dot-notation filters;
- the `/dropdown` path;
- `isAuthenticated()` as the default security, asking when unclear;
- cacheable only for static master data;
- the `query` parameter name.

How they combine: the annotation sets each dropdown's defaults, and the generic `DropdownFilter`
adjusts them per request (`status`, `sortDirection`, `limit`, `query`). Mandatory and optional
filters go in a `DropdownFilter` subclass.

Deliberate differences from the original doc (house rules):
- the service wraps;
- always the Criteria API (the generic params make predicates conditional);
- no loose `@RequestParam`s or named sub-paths (a parent in the path only for a child resource);
- a fixed naming: `findDropdownItems` / `dropdown` / `dropdown`;
- cacheable needs eviction, and a shared cache when there are several instances.

## Pending inputs from the user

- ~~GlobalExceptionHandler, JPA and HikariCP tuning, log pattern~~: received from DynamicForm.
- How the metrics scrape is secured (`metricsAuth`).
- (Historical) Their time-tested `GlobalExceptionHandler` (ProblemDetails) and JPA / HikariCP
  tuning, to be shared later. Expected effect: `Retry-After` also on 503s for connection-pool exhaustion and
  similar transient errors, handled centrally in the exception handler. When it arrives,
  update REST-05/REST-06 and `persistence.md`.
- Their exact exception implementation (ErrorCode enum, ErrorCode → HTTP mapper, i18n
  messages). When it arrives, turn the exception principles into concrete checks.
- Their log pattern (format, structured fields). When it arrives, add concrete logging-format
  rules.

## Step 10: first field test, `/house-review user` on news-pulse (2026-11-13)

Report: news-pulse `docs/reviews/2026-11-13-user.md` (16 findings; 11 already tracked, 5 new).
What the run showed about the skill, and what changed:
- **"Already tracked" had no answer.** Step 7 (decisions) now offers **fix / track / accept**.
  A tracked finding is planned work, not an exception, so it gets no marker. Findings the tracker
  fully covers aren't asked about again. Tracked findings are written to the tracker as a note or
  a new task. Every finding carries a `Tracked:` line.
- **The questions came before the report.** The order is now Step 6 report, Step 7 decisions,
  Step 8 fixes, so the author decides with the findings in front of them. The report gained a
  **Decisions** section. "Tracker only" is a valid Step 8 answer.
- **A module review met project-wide causes** (no database timeouts, lenient Jackson). These are
  now labelled `(project-wide)`, reported once, and listed only under "Previous findings" once a
  report or tracker holds them.
- **REST-04 contradicted REST-03.** `PATCH /x/{id}/role {role}` is the prescribed state-change
  shape, not a disguised PUT. REST-04 gained a "Not a finding when" clause.
- Not a skill issue: the build needs Docker. In a shell without the `docker` group, the
  profile's `buildCommand` notes `sg docker -c "…"`.
- **`diff <base>`** added: a branch stacked on another feature branch (news-pulse's
  `chore/user-cleanups` on `chore/pre-work-cleanup`, 36 commits ahead of `staging`) is reviewed
  against its own base, not the whole stack.

## Step 11: second field test (a fresh todo project) and the reference application (2026-11-13)

Full record: `docs/design/2026-11-13-field-test.md` (the gaps found, the owner's observations
O1–O15 and how each was verified, and sessions 1–3).

What changed:
- **Reference code instead of prose.** `reference-app/` (built and tested) is what bootstrap copies
  (`scripts/bootstrap-copy.sh`) and what crud-gen generates like; `scripts/verify.sh` and the
  pre-push hook keep it green (the repository is private: no CI).
- **Data access:** list pages are Criteria projections; cursor (keyset) paging by default, offset
  only for bounded admin tables; no counts on cursor lists; nullable sort keys through a stored
  generated column; no collection fetch on list pages (Hibernate 7.4 pages them in SQL, so the old
  in-memory-pagination guard never fires). Spike numbers in the record.
- **No tunables in code; configuration split by concern** (`config/*.yml` with profile variants);
  `spring.application.name` drives the names; `@MaxLength` / `@ConfiguredMax` validators read
  `app.limits.*`; over a limit is a 400, never a clamp.
- **`text` columns**, the length contract on the request; a request String without a limit is High
  unless justified.
- **Naming:** `*Request` bodies, `*Filter` GET parameters; why-only comments; `@MultiInstanceSafe`.
- **Error contract:** binding failures answer `REQUEST_PARAMETER_INVALID` without class names; a
  default locale; a status-class fallback for every MVC exception.
- **HelpBook:** unique `lower(trim())` indexes (partial when nullable), ids not updatable, JSON keys
  as constants or typed records, lower-case packages.
- **Rate limiting:** decided in part (own layer over Bucket4j, per-policy store-failure mode with
  a local fallback, every scope with owner-chosen defaults); placement (transport-neutral for
  GraphQL) is the last open session.

## Step 12: the owner's design notes (2026-11-13)

The owner's notes on path placement, DTO naming and layering, with the decisions taken:
- **Paths on the controller class, versions through Spring Framework 7's API versioning.** The
  owner's trade-off: class-level paths force SRP and read cleaner, but path versioning used to mean
  a second controller per version; method-level paths allow versions side by side but need
  discipline. Spring 7 (Boot 4: `spring.mvc.apiversion.*`) matches a `version` attribute on each
  mapping (`"1+"` = from 1 on), resolved from a path segment, header, query or media type, so
  versions coexist in one controller whatever the placement. Decision: class-level
  `@RequestMapping("/api/{version}/<resource>")`, **path segment** transport (URLs unchanged,
  visible in proxy and CDN logs), the resolved version (`HandlerMapping.API_VERSION_ATTRIBUTE`) in
  the request-completion log as `apiVersion` and as a metric tag, so it shows whatever the
  transport.
- **DTO suffix** is a profile key, `requestSuffix`, default `Request` (the owner's notes prefer
  `Payload`; run 1 chose `Request`; both are allowed, one per project). Responses `*Response`, GET
  parameters `*Filter`.
- **Layering:** no interface for a single implementation; no middle man; no Manager service for a
  pattern's sake. ARCH-15: a repository belongs to its aggregate's service (the root's service also
  owns the repositories of member entities, and enforces their rules); another aggregate only
  through its root's service (validation, ownership, locking, caching aren't bypassed); a Manager
  service only spans several aggregates, to own their transaction. Enforced by `AggregateArchTest`
  from the profile's `aggregates`.

## Measurements behind the rules (moved out of the rule texts, 2026-11-16)

The rules say why in general terms; the numbers that first showed each cost live here, so no generated
text copies them as a claim. Each was one run on the reference's shapes, not a bound a project can rely on.

- DATA-10, a status filter left to the order's index: an order under which the wanted rows come last walked
  the owner's rows past every other one, 165–202 ms against under 0.1 ms with the filter's own index
  (crud-gen 4.1 quoted 165 ms against 0.06 ms).
- DATA-10, a seek in OR form inside a tie group: 172 rows read for a page of 21.
- DATA-10, an index on `updated_at` or `version` (2026-11-14, a todos-shaped table): 0% HOT updates with the
  index, about 72% without it at the default fillfactor, 97% at fillfactor 80.
- DATA-11, list pages as entities instead of projections: 22 ms against 7 ms per page (2026-11-13).
- DATA-11, OFFSET against keyset: 20,020 rows read for row 20,000, against 20.
- DATA-26, a collection fetch on a list page (2026-11-13, Hibernate 7.4): 20 lists × 1,000 todos, 111.6 ms
  against 7.2 ms for two steps.
- DATA-29, a same-version double submit without the owner's row lock: 409 `DUPLICATE_RECORD` in 84 of 120
  runs, a deadlock's 503 in 16 of 120.
- TEN-36, experiment E3 (200 tenants × 1,000 rows): a jsonb `@>` with a GIN index read 1,016 buffers as the
  app role (a filter over the tenant's rows) and 26 as a superuser (`BitmapAnd` with the GIN index); `ILIKE`
  became a filter after the tenant index; the plan overlap check (`&&`) left the exclusion constraint's GiST
  index for a btree walk of the owner's plans.
- `TextSearch`, a contains search on 50,000 rows: 54 ms as `position()`, 1 ms with a trigram index and an
  escaped `LIKE`.
- TEN-32, Hibernate's `tenant_id = ?` makes the policy's predicate a one-time filter (the todo index shapes).
- DATA-17, the security filter chain under pool saturation: 1,004 of 1,275 requests answered 401, and the UI
  signed everyone out.
- DATA-19 / CFG-01, `statement_timeout` and `socketTimeout` both at 30 s: they raced, and 29 of 35 requests
  answered 500.
- DATA-24, a machine-written stamp written through the entity: a membership at version 3 after three
  sign-ins, and every ADMIN's role change then answered 409.
- DATA-25, a one-row write that loaded the whole aggregate read-write: 21.6 ms against 8.8 ms, about 16× the
  allocation.
- DATA-27, one extra key in one stored document under Hibernate's own Jackson 2 mapper: the application
  didn't boot.
- DATA-28, a backfill at startup: 38 s before the application was up.
- DATA-29, the lock audit (every lock of the reference removed in turn): the owner-scope advisory lock and
  three `FOR KEY SHARE` delete guards protected nothing the database didn't already hold and were removed;
  the todo's `findForUpdate…` and the token's single-winner rotation hold the error contract and stayed.
- DATA-29, `IN (SELECT … LIMIT … FOR UPDATE SKIP LOCKED)`: a lease with a batch of 2 took 5 rows.
- DATA-05, an expression index `lower(trim(email))` beside a lookup on the bare column: the login read the
  whole `users` table; `UNIQUE (email, deleted_at)` accepted two `('a@x', NULL)` rows.
- DATA-08, `set_config` at session level: 61% of all statements were redundant `set_config` calls.
- DATA-35, a 6-way race of overlapping inserts under an exclusion constraint: about 1 answer in 6 a
  deadlock's 503, never 500, never two rows.
- PERF-03, virtual threads without admission control: a load test left 1,995 requests waiting on the pool.
- PERF-04, exact counts on every page: a p50 of 3.4 s.
- CFG-01, `readOnly` transactions without `LazyConnectionDataSourceProxy`: an active connection before any
  SQL; with the proxy none.
- JAVA-25, two clock reads for one boundary: a 1.3 ms gap with the microsecond `Clock`.
- TEST-08, status-only negative tests: 36 passed with value validation switched off.
- TEST-10, fixtures that never checked their own 2xx: six specs posted `formFieldIds` instead of
  `fieldIds`, got 400, and passed.

## Versioned documents (DATA-36): the shape kept out of the rule

DATA-36 is a use case (no golden code, not generated); the rule keeps only what a review checks. A project
that builds versioned documents can start from this shape:
- the stored state is the decision only, `DRAFT` / `PUBLISHED`, with `CHECK ((status = 'PUBLISHED') =
  (published_at IS NOT NULL))`; `CURRENT`, `SUPERSEDED`, `SCHEDULED`, `EXPIRED` come from the dates (DATA-37);
- the record's response carries the version id (and `versionNo` or `effectiveFrom`); its copied values sit in
  columns commented `SNAPSHOT at <event>, never synced`;
- correcting a published version's past is its own privileged action that records why, never the update;
- superseding locks the document row by id first (never "the open version" by a mutable column: DATA-32),
  then closes the open version and inserts the next with one clock read (JAVA-25). Without that lock two
  supersedes deadlocked in the exclusion constraint, even with one insert per transaction; with it the loser
  waits, fails the document's version check and answers 409;
- a new draft is one explicit entity method (`RateVersion.draftFrom(previous)`, each field named, children
  through `add…`), never `toBuilder()`: `@SuperBuilder(toBuilder = true)` copied the id, the version and the
  audit fields and shared the collections, and `save(copy)` overwrote the published row (with the ids reset
  by hand, persist failed on "Found shared references to a collection");
- the copy's drift test compares `to_jsonb(row)` of source and copy minus the keys that must differ (`id`,
  `version_no`, `status`, `published_at`, `version`, `created_*`, `updated_*`), for the version row and each
  child table, and first asserts the source fixture has no NULL column (except `created_by` / `updated_by`):
  a column NULL or default in both rows compared equal, a false green;
- tests: superseding keeps the earlier slice; both sides of a boundary; a stored result answers byte for byte
  the same after a new version; an overlapping version 409; concurrent supersedes store one version and
  answer 409, never 503; the drift test;
- an example: a per-owner `billing_rates` referent with `billing_rate_versions`, time entries gaining
  `billing_rate_version_id` and `rate_applied`.
