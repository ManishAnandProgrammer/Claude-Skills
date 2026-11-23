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
  the child table on both sides) to new placeholder-free `*_DELETED_MEANWHILE` codes (404). Chosen over services
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
- `BoundedListWrapper` is only for lists bounded by nature (dropdowns, small fixed sets).
- Both skills follow it:
  - REST-11 and REST-08 (house-review);
  - TEST-11 and TEST-12 (house-review);
  - crud-gen SKILL, profile template, bootstrap, tests and dropdown.
- Implemented in news-pulse first. Breaking change: `extraDetails.page` → `pageInfo`.
- Cursor lists will get an equivalent explicit cursor `pageInfo` when the first one is built.

## Step 9: dropdown standard (2026-11-13)

The user standardised dropdowns in another project; the frontend then needed one component for
all of them. Shared reference: their `DropdownItem` class (`Object value`, `String label`) and
`BoundedListWrapper<DropdownItem>` endpoints at `<resource>/dropdown`.

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

## Constructors and visibility (JAVA-26, JAVA-27; 2026-11-17)

The owner's decisions, from news-pulse: beans take their collaborators through Lombok's
`@RequiredArgsConstructor` over `private final` fields, not hand-written constructors; a public bean that
injects a package-private collaborator gets a package-private constructor
(`@RequiredArgsConstructor(access = AccessLevel.PACKAGE)`), and the collaborator stays package-private.
Before the rules, neither skill said either, and the reference mixed both styles (24 Lombok classes,
about 30 hand-written constructors), so generated and reviewed code drifted.

Decided:
- **A constructor that does work stays only when the work can't be a `@Bean` method or a bean of its
  own**, with a why-comment. In the reference that is a `super(...)` call, meters registered at startup
  (a series reads 0 before its first event; a meter registered on first use has no series until then),
  a Resilience4j instance looked up once with a startup failure when missing, a bcrypt decoy hash only
  that bean uses (one several beans share is a `@Bean` of its own, 2026-11-18), a
  `@Value` parameter, the modules' constraint claims merged with a conflict check, and a helper that must
  stay the bean's own (the schema overlay's owner `JdbcTemplate`: as a bean, Boot's `JdbcTemplate` backs
  off and every class injecting one gets the owner role's). Reading a value out of injected properties is not work: the
  properties are injected and read where used. A startup check is an `InitializingBean`.
- **`lombok.config` lists `@Qualifier` as copyable**, and bootstrap copies it with core. Checked with
  Lombok 1.18.48: without the line the qualifier never reaches the generated constructor's parameter and
  is silently ignored (the primary or the wrong bean). High severity for that case only; the rest is Low.
- **`@Value` is not made copyable.** On a field it is field injection, which the house `ConventionArchTest`
  refuses (ArchUnit's `NO_CLASSES_SHOULD_USE_FIELD_INJECTION` lists `@Value`; the first verify run of this
  change failed on exactly that in `ProblemTypes`). Values come from `@ConfigurationProperties` records; a
  value that can only be a `@Value` (`spring.application.name`) keeps a hand-written constructor.
- **Enums are not a finding** (an enum constant table's constructor; Lombok on enums reads worse), beside
  the owner's list (records, entities, exceptions, `@ConfigurationProperties`, tests).
- **JAVA-27 narrows the member, never widens the type.** Spring calls a package-private constructor as
  well; a member another package truly needs is a module-contract decision (ARCH-04).

The reference after the change: 39 hand-written constructors became Lombok (30 in the reference, 9 in the
overlays), 16 stay hand-written with a why-comment (9 and 7), and 12 constructors became package-private
for JAVA-27 (6 and 6: 8 Lombok `access = AccessLevel.PACKAGE`, 4 hand-written).

## news-pulse gaps, AI contract to phase 6b (2026-11-18)

69 Low rows from news-pulse's work with the skills (`skill-e2e/gaps/backlog-low.md`), folded in as
sharper Detect / Fix / Not-a-finding lines; no new rule ID. Decided:
- **DATA-09: `@UuidGenerator(style = VERSION_7)`**, not `algorithm = UuidVersion7Strategy.class`. Read in
  Hibernate 7.4.13's sources: `algorithm` instantiates the strategy per generator through its `@Internal`
  no-arg constructor, `VERSION_7` returns the shared `UuidVersion7Strategy.INSTANCE`. Both are
  `@Incubating`, so the gap's "stable equivalent" was only half right; the choice rests on not calling
  an internal constructor. Guard: `TimeOrderedIdsIntegrationTest`.
- **Translated field details stay a use case.** The reference is English with Hibernate's `{max}`
  templates; MessageFormat (ERR-06) can't read them, so a project that translates them uses a second
  bundle without MessageFormat (news-pulse's shape). The skills ship the parity test for the error
  messages (every `message_*.properties`), not a second bundle.
- **Parked as features:** a request-body cap filter (CFG-04 now names the proxy as the bound and turns
  Boot's `FormContentFilter` off), 5xx bodies with their own message or extensions (a 502 refusal, a 503
  naming what it left behind: both change the generic-5xx contract), a thrower-known `Retry-After`, the
  build's quality gates (coverage, static analysis), and the shapes (an operation, a singleton
  resource, an RPC module, an agreed-breaking update mode, dropdowns over a status or in the reader's
  language).
- **ArchUnit rule scopes are proven on fixtures** (`ArchRuleScopeTest`): a narrowed rule must still
  refuse what it is for. It found that ArchUnit's `NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING` checks field
  writes only; the house rule is now a plain dependency rule.

## Build quality gates (CFG-11; 2026-11-18)

The owner lifted the feature freeze for this one item (news-pulse phases 6b and 6e: SpotBugs found six real
null-path bugs there; a coverage gate set within run-to-run noise failed at random; a batch that ran only `test`
per commit left eight commits failing `verify`). Decided, the recommended options taken:
- **Libraries** (libraries-first, the same choice news-pulse made on 2026-11-17): `spotbugs-maven-plugin`
  4.10.5.0 with `findsecbugs-plugin` 1.14.0, effort max, threshold medium (low priority waits for JSON logs, as
  news-pulse C61 decided), and `jacoco-maven-plugin` 0.8.16. Both run on JDK 25 and Boot 4.1.2 in the reference
  (class files of major version 69 analysed and instrumented, no unsupported-version warning). Not chosen: Error
  Prone (a compiler plugin set up next to Lombok's annotation processor on JDK 25), PMD (weak security rules),
  a SonarQube server or SonarCloud (a service to run; revisit for a team). SonarLint is fine as an IDE aid and
  never the gate.
- **Both gates run in `verify`**, static analysis first, so CI and every "green" mean one command. "Green at
  every commit" is that command, never `test` alone (house-review Step 2, crud-gen Step 7, CFG-11).
- **The coverage minimum is measured, never guessed:** three clean runs at least, each minimum a full point
  below the lowest run, rounded down, a comment on the property saying so; only ever raised. Lombok's code
  (`lombok.addLombokGeneratedAnnotation`) and the metamodel don't count. A copy's minimums are its own: the
  reference's measure the reference's suite (bootstrap step 4 measures the copy's).
- **The exclude file holds false positives one class and pattern at a time,** each with its reason; the
  metamodel's naming pattern is the one pattern entry. A new entry in a diff is reviewed like a deviation;
  `verify.sh` refuses a `<Match>` without a class and a pattern, or with a package, a category, a priority or
  a rank.
- **Records copy their collections** (Effective Java item 50), which is what SpotBugs' `EI_EXPOSE_REP` asks
  and what generated records must do for crud-gen's output to pass the gate with no new entry: a response or
  an internal value `List.copyOf` / `Map.copyOf` in its compact constructor (the house wrappers already did);
  a request body `Collections.unmodifiable*`, null-safe, because Jackson builds it before validation and a null
  element must reach the constraint that names it. Checked: SpotBugs 4.10.5 accepts both shapes.
- **What stays an entry** (the kinds, in the reference): the metamodel; `CT_CONSTRUCTOR_THROW` on Spring
  singletons that refuse a missing setting at startup (open for proxies); `EI_EXPOSE_REP2` on a bean keeping an
  injected singleton; `PREDICTABLE_RANDOM` on `Retry-After` jitter; `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` on a
  managed entity held by a JPA relation or handed between the halves of one call.
- **The entity's relation method is the one entry generated code brings** (the session owner's decision):
  `EI_EXPOSE_REP2` on a method that points a JPA relation at its argument (golden `Todo.moveTo`) is inherent to
  JPA (an entity is mutable and the relation holds it, never a copy), so crud-gen generates that entry with the
  entity, per class and method, with one fixed reason. Any other finding in generated code is a reference bug.
- **Each copy stage of `verify.sh` has its own coverage minimums**: a copy holds fewer tests than the reference
  (core and sign-in alone measured 0.89 / 0.76 against the reference's 0.95 / 0.84), so it is measured and set
  by the same rule, and the tenancy overlays' code, which only the tenancy copies run, is gated there.
- **Each overlay carries its own exclude entries** the way it carries its code: an overlay's
  `spotbugs-exclude.xml` replaces the reference's in a copy of that mode, pinned in `overlays/replaced.sha256`
  like any replaced file.
- **Cost:** the reference's `verify` went from 1 min 55 s to 2 min 38 s - 3 min 00 s; `verify.sh` from about
  29 to about 50 minutes, measured with another project building on the same machine (the image stage, which
  runs no analysis, also took twice as long). Static analysis still runs in every copy: each copy's code is
  its own (the markers, the overlays), and the tenancy overlays' findings showed up only there.

## Data retention and low-priority static analysis (2026-11-18)

The owner asked whether crud-gen and house-review cover "scheduled jobs delete expired sign-in tokens", and if not
to add it; the recommended options were taken. news-pulse had no purge for any of its accumulating tables (tokens,
codes, sign-in challenges, daily quotas, finished operations, an audit log) until its phase 6g built them.
- **OPS-03 sharpened, no new ID**: Check (every accumulating kind named; each new table gets the question), the
  purge's shape, *by the data's end of life* (a rotated refresh token stays until it expires, or REST-15's reuse
  detection stops working; a code until its expiry plus its attempt window), personal data, Detect, Not a finding,
  and Severity Medium with **High when callers who aren't signed in can make the table grow** (signup codes,
  challenges, anonymous submissions, webhook deliveries). Gate 2 now loads OPS-03 for an entity, a changeset and a
  scheduler, and the `data` focus does too: before, a diff adding a log table never read it.
- **No new profile key**: `retentionExceptions` (DATA-07's legal retention) already held "kept instead of deleted",
  and news-pulse already used it for its kept AI runs; its note now covers OPS-03's kept-by-decision tables.
- **The period is the developer's** (skills ask, never guess): crud-gen Step 1 asks kept or accumulating, and for
  accumulating rows the period and the column; only an expiry's own lifetime gives a default (the token's day). A
  non-interactive run generates no purge and lists the question, since a period has no golden value.
- **The golden purge** stays the sign-in piece's `RefreshTokenPurgeJob`, reviewed against the new rule. Fixed: its
  batch was `id IN (SELECT … LIMIT …)`, which DATA-29's own Detect names (the planner may rerun the subquery per
  outer row and delete more than a batch in one transaction): now `= ANY (ARRAY(… FOR UPDATE SKIP LOCKED))`, a
  repository constant whose shape and plan `RefreshTokenIndexIntegrationTest` checks, and instances purging at once
  take disjoint rows instead of waiting on each other's locks (the `@MultiInstanceSafe` reason said "disjoint" before
  it was true). The cutoff is taken once per run from the injected `Clock` (it was per batch), the cron is UTC
  (`zone = "UTC"`: otherwise it follows the host's time zone), `keepAfterExpiry` can't be negative (`@DurationMin(days = 0)`;
  a negative period deletes live tokens and the rotated ones' reuse detection), and the test profile switches the
  cron off (`"-"`): a test run crossing 03:15 UTC could delete other tests' fixtures mid-run.
- **Not added to the golden purge:** a deleted-rows counter (OPS-07: a mitigation metric needs a consumer and an
  alert; none would read this one, and Spring's `tasks.scheduled.execution` timer already counts each run, its
  duration and its outcome, the signal for a purge that fails or hangs); a per-job log context
  (`ScheduledRunLogContext` already gives every `@Scheduled` run its own request id and endpoint, where news-pulse
  wraps each job in `LogContext.runWithNewId`); a shared loop helper (news-pulse's `BatchedDeletes.untilShort(int,
  IntSupplier)` serves its five purges, but the golden loop is six lines and a lambda-taking helper is what SMELL-29
  keeps out of generated code: each job is a small class of its own).
- **No second purge in the reference.** Its tables are business records (todo lists, todos, tags, time entries,
  plans, their links and attachments), bounded by their key (upload policies, metadata, counters), or already
  purged: `media_files` by its cleanup job, a status-based purge with leases (removed and never-attached files), the
  case a "finished N days ago" purge would show; `refresh_tokens` by the token purge. No reference table accumulates
  rows with children through a foreign key, so that case is rule text (OPS-03, crud-gen Step 2 and 4.12) until a real
  table needs it (KISS). The tenancy overlays' `platform_audit` and `tenant_status_changes` are a trail of staff
  actions, low in volume: their retention is the project's decision, asked by bootstrap's new *Tables that only
  accumulate* item.
- **crud-gen generates the purge** like the golden one (4.12: the job, the service's one-batch method, the
  repository constant, the index in the entity's changeset, `app.jobs.<entity>-purge.*`, the test profile's `"-"`,
  tests.md row 34 and the batch in row 28), per tenant under `TenantScope.runAs` with tenancy, as the overlay's media
  jobs run. `verify.sh` generates no entity, so the golden purge is what its copies exercise (every copy with
  sign-in runs both purge tests).

**SpotBugs at low priority** (CFG-11's "low waits for JSON logs": the reference already writes JSON in its deployed
profile, and news-pulse gated low in phase 6g, its C82). Measured before deciding, per copy, with the exclude files of
the time: the reference 88 low findings, the every-piece schema copy 128, the row copy 123. In the reference:
`SPRING_ENDPOINT` 45 in 9 controllers, `CRLF_INJECTION_LOGS` 33 in 14 classes, `UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR`
3 (one filter), `SE_TRANSIENT_FIELD_NOT_RESTORED` 3 (`AppException`), and one each of `DB_DUPLICATE_SWITCH_CLAUSES`,
`SERVLET_HEADER`, `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION`, `THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION`; the overlays
added the platform's controllers and logging classes, `SQL_INJECTION_JDBC` 2 (schema mode's constant statements) and
`SE_NO_SERIALVERSIONID` 1 per mode (Hibernate's connection provider). No security defect among them, as in news-pulse
(3 fixes of 208). Gated anyway, as the owner decided for news-pulse: the gate's value is the next low finding, which
must now be looked at (a low `SQL_INJECTION_*` or `PATH_TRAVERSAL_*` is exactly what a review wants to see), and the
recurring cost is one entry per new controller, generated, and one per new class that logs a value.
- **Fixed:** the duplicate switch clause (`MvcExceptionCodes`: `MissingServletRequestParameterException` is a
  `ServletRequestBindingException`, one case covers both); the range filter's fields (`TimeEntryFilter` compared its
  bounds in its own method: a bean bound through setters, whose fields SpotBugs reads as never null-checked, since it
  doesn't follow `Objects.isNull`). Ranges now go through `DateRanges.isOrdered` and the new `NumberRanges.isOrdered`,
  the filters and the upload policy's request alike, so a generated range filter brings no entry.
- **Excluded per class and pattern, with reasons:** `SPRING_ENDPOINT` per controller (informational; who may call
  what is the role annotation and its tests), `CRLF_INJECTION_LOGS` per class that logs a value (the deployed profile's
  encoder escapes line breaks; a new logging class still gets looked at for SEC-04), the rest each by class and
  method. Each piece's entries sit in its markers, so a copy gets exactly its own; the overlays' files carry the
  platform's.
- **Generated code now brings two kinds of entry:** the relation method's (before) and each controller's
  `SPRING_ENDPOINT` (crud-gen 4.10, Step 7). Golden services and mappers log nothing, so generated code brings no
  `CRLF_INJECTION_LOGS`; a generated purge logs an `Instant` and a count, which FindSecBugs doesn't taint.

## Typed settings (CFG-12; 2026-11-18)

The owner's decision: "I want to utilise the flexibility of configuration properties and mold application logic
properly". Settings are bound as typed values (`Duration`, `Period`, `DataSize`, enums, `URI`, maps keyed by an enum,
small value records), never numbers with the unit in their name or text parsed later; a test profile can then set
`200ms` or `2s` and exercise an expiry, a lease or a retention for real. No rule said it before: house-review named
`Duration` only for date math (JAVA-12), crud-gen only in its purge job, so a `long ttlMinutes` passed review. The field
evidence: news-pulse's `schedulerTickMs`, `reconcileIntervalMs`, `staleAfterMinutes`, `runTimeoutMinutes`,
`accessTokenTtlMinutes`, `refreshTokenTtlDays`, `ttlMinutes`, a purge's `keepDays` over `LocalDate` rows (a `Period`),
two URLs and six crons as `String`, environment variables with the unit in the name (`JWT_ACCESS_TTL_MINUTES=15`).
- **CFG-12 in config-baseline.md**, beside CFG-10 (layout and startup checks) and CFG-11: a setting is configuration
  whichever module holds it. Its Check names the type per meaning; Bounded; the unit in the value; the migration trap;
  Detect; Not a finding (counts, a library's number-only property with a unit comment, a bound in the API's own
  unit, invariants, business data, time zones); Severity Low, Medium where a wrong unit weakens a safeguard, High for
  the trap. SMELL-17's Fix and JAVA-12 point to it.
- **It loads where settings change.** Gate 2 loaded config-baseline.md for yml files and for classes in `config/`, but
  a job's `Settings` or a module's `@ConfigurationProperties` record is Java of a feature module, which loaded the
  core only: a new row loads CFG-10 and CFG-12 for a settings record or a startup check, besides its own row. `--focus
  config` loads the whole file, CFG-12 with it.
- **The migration trap is the rule's reason for High.** A bare number bound to a `Duration` is milliseconds (a
  `Period`, days; a `DataSize`, bytes), so a `ttlMinutes` turned into a `Duration` under the same environment variable
  silently becomes 15 ms. The fix is a rename of the property and its variable, the startup check refusing the old
  names while set, and a minimum the stray number fails. Not chosen: `@DurationUnit(ChronoUnit.MINUTES)` as the
  default migration (it keeps a bare number's old meaning, but the name still carries the unit and the next reader
  writes `15m` beside it); it stays for a setting that can't be renamed yet, with its reason.
- **Proven before claimed** (`SettingsBindingTest`, on Boot's own `Binder` and `ApplicationConversionService`, no
  Spring context, with test-only records so no setting exists to show a type): the `Duration` formats (`500ms` to
  `7d`, ISO), `Period` (`7d`, `2w`, `1y`), `DataSize` (`512KB`, 1024 bytes a KB), the bare numbers' units (ms, days,
  bytes), a minimum refusing a stray bare number through the validation bind handler, `Map<Enum, Duration>` with
  kebab-case keys, an enum, a `Locale`, a `MediaType`, a `URI`, `Cron` through its public constructor, a value through
  its `of(String)` alone, malformed values refused. Two claims turned out other than expected and the rule says what is
  true: a factory method is found only when it is **public** (a package-private `of` gives "no converter found"); a `URI` is not refused for what its
  grammar forbids, Spring encodes it (`http://bad host/` binds as `http://bad%20host/`, absolute, with no host), so a
  record checks what it relies on (absolute, a host). An empty `${VAR:}` binds a `URI` as null, which the media
  endpoint relies on (AWS). The AWS SDK's `Region` binds through its `Region.of` (`MediaPropertiesTest`, in the media
  piece: core copies have no AWS SDK).
- **Cron:** `common/scheduling/Cron`, a record whose constructor runs `CronExpression.parse` (or accepts Spring's
  `"-"`); no `of(String)`: the record's public constructor is what Spring's converter binds, so a factory would be a
  second way to build one. The jobs keep `@Scheduled(cron = "${app.jobs.<job>.cron}")`: the annotation is
  what Spring's scheduler observes (`tasks.scheduled.execution`), what gives each run its log context, and what
  `ConventionArchTest`'s `@MultiInstanceSafe` rule finds. Not chosen: scheduling through the record
  (`SchedulingConfigurer.addCronTask`), which would lose all three for a value Spring parses anyway; the record makes
  the setting typed and names its key when the expression is wrong (before Spring's post-processor names only the
  method).
- **The reference, made to pass its own rule:** the three jobs' `cron` a `Cron` (and the tenancy-schema overlay's
  provisioning job); `BreachedPasswordProperties.apiUrl` a `URI` (Spring's `RestClient.Builder.baseUrl(URI)`) checked
  absolute with a host, since a relative one would fail every lookup and the check fails open;
  `MediaProperties.endpoint` a nullable `URI` (the SDK's `endpointOverride(URI)`; the `URI.create` in the config class
  went), checked the same way, and `region` the SDK's `Region`; `app.limits.not-after-today.tolerance-days` (a number of
  days read by key at each request) the `Period` `app.limits.not-after-today.tolerance: 1d` in its own
  `NotAfterTodayProperties`, days only, 0 to 2 (`1m` would be a month). **Bounds** where a zero, negative or too-large
  value breaks something: the access-token lifetime's minimum is now really `1s` (it said "between 1s and 15m" but
  checked only "positive", so `JWT_ACCESS_TTL=15`, 15 ms, passed), the refresh lifetime at least `1h` (a bare number of
  seconds binds as minutes' worth of milliseconds), the rotation grace at least zero and below the refresh lifetime
  (the brief's example of a rule across fields), both presigned-URL expiries within SigV4's 1 s to 7 days (only the read
  URL's maximum was checked: anything else failed every presign, at each request), the impersonation token at most 15m
  like an access token (nothing ends it early), `@DurationMin(millis = 1)` on the timeouts, leases, the lock timeout, the rate
  limit's refill period and the slow-request threshold (a timeout or lock timeout of zero often means none at all, and a
  zero-length refill period refills nothing), `@DurationMin(days = 0)` on the waits and the claim grace period; the overlays'
  copies of those records alike. db.yml's Hikari
  and PgJDBC numbers got comments naming their units (milliseconds, seconds): the libraries' setters take a number.
- **Enforcement: ArchUnit**, `ConventionArchTest.settingsAreTypedByWhatTheyMean`, so it travels with every copy through
  bootstrap: every `@ConfigurationProperties` class and the application's records it holds (a nested group, a value
  such as `RateLimit`, a map's values), refusing `int`/`long`/`double` (boxed too) named `*Ms|Millis|Seconds|Minutes|
  Hours|Days|Bytes|Kb|Mb` (and those words alone) and `String` named `*Url|Uri|Endpoint|Cron` (a host name alone is no
  URI). Proven red twice: on fixtures (`ArchRuleScopeTest`: eight planted violations, a nested record's and a map
  value's included, none on the typed twin or a count; the fixtures carry `@ConfigurationProperties`, so the
  application's scan binds them, empty, in the test contexts too: harmless) and on
  the real code (the token purge's `cron` set back to `String` fails the rule naming it). Not chosen: a shell lint in
  verify.sh (it would check the skills repository only, never a project, and grep can't see a record's components or
  follow a nested record). What it can't see stays review: a mode kept as text, a conversion in code, a missing bound.
  A bound in the API's own unit (`time-entry.max-minutes`) is a `Limits` key read by key, so it never meets the rule.
- **The startup check of renamed names is rule and bootstrap text, not reference code:** the reference has no
  deployment carrying an old name (its one rename, `tolerance-days`, is a yml key with no environment variable, and a
  `Period`'s bare number is days, the old meaning), and a refusal of `tolerance-days` would ship into every new copy,
  which never had the key: the reference's review profile records it as a convention (CFG-10, CFG-12). bootstrap's *Adapt* tells an existing project how to migrate (new names, the old ones refused at startup,
  the environment files and manifests in the same commit).
- **The test profile is unchanged:** no reference test relies on a configured short lease or lifetime; the lease,
  grace and expiry tests move time in the database (`lease_until = now() - interval '1 second'`, a rotated token's
  `revoked_at` an hour back), which is exact and needs no wait. A short value in the test profile would be invented.
- **crud-gen:** its conventions say the type rule once; 4.12's `Settings` takes a `Cron`, a `Period` for a table ending
  on a `LocalDate`, the yml value with its unit; the final checklist gains a line.

## Authorization: permissions, roles and policies (AUTHZ; 2026-11-19)

The owner's ERP pain: code checked role names (`@RolesAllowed("TEACHER")`), the frontend copied the role →
permission lists, and once roles became admin-built data and two people with one role needed different
capabilities, the two copies drifted (an endpoint shipped without its permission on the frontend); amount rules
("an accountant gives concessions up to 2,000, an admin any amount") ended up in the roles. The decision
(2026-11-19), the default of every house project, is `references/authorization.md`:
- **Level 1 (default):** `Permission` (an enum of capabilities in `common/security`, each a Spring authority of its
  name, with its menu `Area`, its `Reach` and its prerequisites), `Role` listing its permissions in code, one role
  per account (per membership with tenancy), the token carrying the role and the converter mapping it to its
  permissions. Every handler declares exactly one of `@Requires(Permission.X)`, `@SignedIn`, `@Public` (ArchUnit).
  Record rules (reach, state, amounts, dates, relationships, the module's limits) are a `<Resource>Policy` of plain
  `if`s returning a `Decision`, which also owns the list's condition. `GET /users/me/access` answers the areas and
  permissions; `allowedActions` for every action whose answer differs between the caller's records, each with
  every reason. Dropdowns are shared per resource and
  named by the action they serve (`for=<Permission>`).
- **Level 2 (the `roles` piece, later):** `roles`, `role_permissions`, `user_roles (valid_until)`,
  `user_permissions (ALLOW|DENY)`, effective = roles + ALLOWs − DENYs, read per request from PostgreSQL. Written down
  now as rules *with the roles piece*, so moving changes no endpoint, policy or client.

**Why this shape** (evaluated against two LLM designs and the industry): Spring Security's own group tables
(`groups`, `group_members`, `group_authorities`) are Level 2's shape, so Level 2 is Spring's model, not ours.
Laravel Policies, Pundit (with its `Scope`), ASP.NET resource handlers and Symfony voters all split a yes/no
permission from a per-resource policy: the `@Requires`/`<Resource>Policy` split. Dynamics 365's access levels (User,
Business Unit, Parent: Child BU, Organization) are the reach suffixes. SAP keeps approval amounts in the release
strategy, never the role: limits are the module's settings. AWS IAM and Azure: an explicit deny wins (Level 2).
Jira's `GET /mypermissions` and GitHub's `permissions` on a repository are `/me/access` and `allowedActions`.
**Rejected:** Spring ACL (a row per record per person duplicates the business relationships the policy already
reads, and breaks paging: a list can't be one query); Keycloak authorization services, SpiceDB/OpenFGA (a second
system holding a copy of the relationships); Casbin (a policy language of its own, rules as data); Redis permission
caches and revocation keys (the session-version piece already bounds staleness; Level 2 reads PostgreSQL); `ltree`
scope trees; role-level DENY (a role only allows, so "what may this role do" stays a list); limits in role tables.

**Decided while building it:**
- **Annotation templates, proven first:** a probe test (deleted after) showed Spring Security 7.1's
  `SecurityAnnotationScanners.requireUnique` resolves `{value}` templates by default and converts an enum attribute to
  its name: `@PreAuthorize("hasAuthority('{value}')")` on `@Requires(Permission value())` grants the holder and refuses
  another authority with no `AnnotationTemplateExpressionDefaults` bean; a literal `{value}` authority never matches.
  So no `AuthorizationManager` of our own. **Later (leave-desk's design):** a detail several reaches read needed
  any of several permissions. The scanner's conversion service writes an enum array as its names joined by commas,
  so `hasAnyAuthority('{value}')` would ask for one authority named `A,B`; `Permission[] value()` with
  `hasAnyAuthority('{value}'.split(','))` turns it back into the arguments, the single form unchanged
  (`RequiresAnyOfIntegrationTest` pins both renderings). Rejected: `Permission` implementing Spring Security's
  `ExpressionTemplateValueProvider` to render each name quoted (a framework interface on the domain's enum for one
  annotation), a custom expression root (a bean and a handler to replace for a split), and the door pattern (one
  reach's permission made the others' prerequisite, a reach nobody chose).
- **`TokenPermissions`** (`common/security`), implemented by sign-in's `RolePermissions`: the converter in `config`
  can't see `user.domain.Role` (config reaches a feature only through its api, and core copies without sign-in), and
  it is Level 2's seam: the same interface then reads the tables. A core copy without sign-in implements it for its
  identity provider (bootstrap warns).
- **Spring's `FACTOR_BEARER`:** Spring Security 7's `JwtAuthenticationConverter.convert` (final) adds it to every
  token; it is how the caller signed in, not a role or a permission, so the rule says "nothing else but Spring's factor
  authority" and the converter test checks that.
- **Validation runs before method security** on a controller (arguments are resolved before the proxied method), so
  an invalid body is 400 before a caller without the permission gets 403; the generated 403 test sends a valid body
  from the schema's examples (a plan status example changed from `DONE` to `CANCELLED`, since `DONE` needs a date).
- **Platform staff** (tenancy) have one role and their own chain, so platform handlers are `@SignedIn` (a staff
  token is the only one that reaches them); a second staff role brings platform permissions. Staff tokens map to no
  permission (an unknown role grants nothing).
- **Member and account changes** keep reading the caller's role under the lock (TEN-08) and ask it
  `role.grants(TENANT_MEMBERS_MANAGE)` / `ACCOUNT_STATUS_CHANGE`; the last-ADMIN rules stay about the role.
- **`TodoPolicy`**: the todo has no state rule, so the golden shows reach only: the owner edits, the requester reads
  (`TODO_NOT_YOURS` on a change), anyone else 404; the list's condition is a `PredicateSpecification` the repository
  adds to its Criteria query (the policy lives in `service`, and a repository can't call up). A write locks through
  the policy's `editScope` (the round-1 review: a lock by id before the policy let any caller queue behind the owner,
  DATA-29), then, on an empty result, reads unlocked for the policy's reason. `allowedActions.edit` is the same
  method.
- **Capability DTOs: every reason at once** (the user, 2026-11-19, after a design note on capability DTOs): each
  `allowedActions` entry is an object, `{"allowed": false, "reasons": [{"errorCode", "message"}]}`, not a boolean.
  `reasons` lists every unmet precondition at once: shown one at a time, a person goes to another screen to fix
  one, comes back and meets the next. `errorCode` is the code the write answers, `message` its existing message in
  the request's language with the same arguments (no new texts). A reach refusal stands alone (telling someone who
  may never act what else is missing is noise and can leak). The write keeps one Problem Details with one
  `errorCode` (`Decision.orThrow` throws the first, the policy's order: most fundamental first): a list in error
  bodies was rejected. `Decision` holds a `List<Refusal>` a policy fills with plain `if`s (KISS: no builder, no
  rules as lambdas), and `AllowedActionMapper` is the one place a decision becomes a response.
  **AUTHZ-07 tightened** the same day: the exemption "the answer follows from a status alone, the same for every
  caller" was dropped, so state rules get flags too and the UI never re-derives one from a record's fields; the
  reference's plans got `PlanPolicy` and `PlanAllowedActionsResponse` (`reschedule`, `markDone`, `cancel`), and
  `PlanService.update` now asks the policy before the entity's guard (the frozen plan's PUT takes its todo's lock
  and rolls back: one row of the owner's, negligible). Kept without a flag: a refusal only the database decides at
  the write (`*_IN_USE`), answered when it happens. Also decided: what a reason may say (values the caller already
  sees, a generic code for a sensitive rule), and the authorization log, one logger (`<rootPackage>.authorization`),
  off by default, codes only. Level 2's per-request TRACE line of the effective set followed (2026-11-20, below).
- **Options a rule can refuse, availability, the Level 2 log line** (the user, 2026-11-20, the
  `field-authz-capabilities` backlog entries): a picker whose options a domain rule can refuse right now (a done todo,
  a withdrawn or lent book, a booked room) gets a second item type, `SelectableDropdownItem {value, label,
  selectable: AllowedAction}`, with its own schema; `DropdownItem` stays `{value, label}` (an optional flag whose
  absence meant allowed was rejected). The alternative, a plain filter leaving the refused ones out, stays the
  developer's choice per screen, and crud-gen asks. **Where it lives** was the design question: its flags are the
  write's policy, which the resource's module can't call (the write's module depends on it, ARCH-02), and a statement
  joining both modules' tables is ARCH-04's to refuse. So the options are the write's: `GET /plans/todo-options`
  beside the create, `@Requires(PLAN_CREATE_OWN)`, no `for` (one action), reading the todos through the facade's
  batch (`Todos.getOwnedTodoViewBatch`, the dropdown's own query returning views) and flagging each with
  `PlanPolicy.create`, which the create now calls too: one statement. A rule on the write's own table over another
  module's candidates (library-desk: a book's overlapping loans) costs one more statement for the whole batch,
  never one per option. The shared dropdown with `for` (AUTHZ-08) stays for every screen that only picks. The
  availability read (`GET /plans/availability?from&to`, the caller's own: plans overlap per owner) answers every day
  as free and taken spans, a taken one with the create's `PLAN_OVERLAPS` reason, at most `plan.max-availability-days`
  (92), one statement on the starts_on index with the list's overlap bound (the constraint's GiST index would serve
  it too, but only through native SQL: Criteria has no range operator). `ex_plans_owner_id_dates` is named the golden
  of DATA-35, so a booking or an assignment is an exclusion constraint, never check-then-insert. Level 2's TRACE
  line: `PersonAccessService.effectivePermissions`, the read every request makes (every tenancy mode goes through
  it), `Effective access: roles [...], allowed [...], denied [...], held [...]`, built only under
  `isTraceEnabled()`.
- **The 403 documentation is generated** from `@Requires` (`PermissionOpenApiCustomizer`, an `OperationCustomizer`
  beside the CORS one), never hand-written per operation: the round-1 review found every todo, list, tag and plan
  operation's 403 silent about its permission, and a generated line can't drift.
  The todo list keeps its owner condition in its queries (its only rule): AUTHZ-04's *Not a finding*.
- **Shared dropdowns:** `for` is a `@RequestParam` beside the filter (`for` is a Java keyword, so no filter
  property), checked in memory by `DropdownFor.requireReach`; the todo-list and tag dropdowns list `OWN` only, so an
  all-records permission is 400 `DROPDOWN_REACH_UNSUPPORTED`. A dependent dropdown is a rule and crud-gen's
  generation (its parent a plain filter of the one query); its golden came with Level 2's batch (below).
- **Permission names** for the reference: `TODO_LIST_*`, `TODO_*`, `TAG_*`, `PLAN_*` with `READ`, `CREATE`, `EDIT`,
  `DELETE` and `_OWN` (each module is per owner, the create included: you create your own), EDIT/DELETE/CREATE
  needing READ; `ACCOUNT_STATUS_CHANGE`, `TENANT_MEMBERS_MANAGE`, `METADATA_MANAGE`, `UPLOAD_POLICY_MANAGE` by
  capability (the brief's examples). Areas `TODOS`, `ACCOUNTS`, `SETTINGS`.

**Level 2 batch, Phase 0 (2026-11-19): two Level 1 gaps closed first.**
- **The golden dependent dropdown:** `GET /todos/dropdown?for=PLAN_CREATE_OWN&todoListId=`. The screen that
  honestly picks a todo is a new plan's (a plan is for one of your todos), and the plans' list filters by one
  (`PLAN_READ_OWN`): both reach your own, so the dropdown lists `OWN`. The list is a `@NotNull` filter and a plain
  condition beside the policy's `listScope`; another owner's list and an unknown id both answer an empty batch in
  the same one statement (no 404 to tell them apart). Todos have no active flag, so done todos are offered too and
  the plan's create refuses one (409 `PLAN_TODO_DONE`): a status rule for one screen stays that write's. Titles go
  up to 200 characters, so the cursor limit grew to 1800 with the example (`(6 x 200 + 100) x 4/3`).
- **`ACCOUNT_READ`:** the account lookup (`GET /users?email=`, a personal-data read) had been guarded by
  `ACCOUNT_STATUS_CHANGE` because it only served the suspension. Level 2's assignment screens find people without
  the right to suspend them, so the lookup is its own permission and the suspension's prerequisite; ADMIN holds both.
  With tenancy there is no tenant-plane lookup (accounts are global; the member list is `TENANT_MEMBERS_MANAGE`'s),
  so the constant exists without tenancy only.

**Level 2 batch, Phase 1 (2026-11-19): the `change-history` piece (DATA-08).** AUTHZ-11 audits the four role
tables, and the reference had no change history (`auditedTables: none`), so DATA-08 got a piece of its own,
opt-in like the session version, which `roles` requires and any project can take alone. The reference runs with
it (its golden stays live code); every copy without it is checked for none of its traces. Built from the owner's
news-pulse (changeset 0048, its actor listener and purge job), in the reference's own names:
- **One generic function**, `audit_row_change()`, its arguments the columns never copied (`password_hash`): a
  function per table drifts, and a column list to copy would let a new secret column in by default. An UPDATE
  keeps the changed columns only, both sides: whole rows on every update would make it the largest table.
- **Who, once per new write transaction**, from a `TransactionExecutionListener`'s `afterBegin` (Spring's own
  hook, no AOP of ours), transaction-local: a session-level setting on a pooled connection would name the
  previous caller in the next borrower's writes (the red proof). It never throws: an exception out of a listener
  leaves the transaction's resources bound to the thread; the failure is rethrown at the commit instead.
- **Retention:** 365 days by default and a floor of 90 (a slip such as `7d`, or a bare number read as
  milliseconds, stops the startup instead of deleting the history the next night); compliance sets the value.
  The purge is the refresh-token purge's shape (batches of ids computed once, SKIP LOCKED, `@MultiInstanceSafe`).
- **Tenancy:** one platform table per database rather than one per tenant schema: the purge stays one job, and a
  tenant's rows carry their table's schema (schema per tenant) or their row's `tenant_id` (row mode). The
  function names `platform.audit_log` in schema per tenant (a tenant's transaction has its own schema alone on
  the search path) and the tenant changesets' triggers name `platform.audit_row_change()`. Row mode lists the
  table as a platform table in the catalogue guard. UPDATE stays granted to the application's role: the trigger
  refuses it, and the purge's `FOR UPDATE SKIP LOCKED` needs the privilege (a revoke failed the purge in row mode).
- **What it audits:** `users` (with sign-in; `password_hash` excluded), `tenant_memberships` with tenancy, and with
  the example its todo lists and todos, so the golden shows a real table. Personal data (an email, a name) is
  copied: a project that must erase a person also erases their history rows, or excludes the columns.

**Level 2 batch, Phase 2 (2026-11-19): the `roles` piece, built.** Decided while building it:
- **An overlay, like tenancy, not a reference piece like the session version:** the reference stays the Level 1
  golden (the default of every project), so Level 2's own files live in `overlays/roles`, and its changes to shared
  files are commented `roles` blocks in the reference (bootstrap uncomments them with the piece). `verify.sh` 2n
  copies every piece with it and runs every Level 1 test unchanged beside the piece's own, but the ones that asserted
  the role column or claim (the account's `role` field, the converter's two token tests, the `newUserWithRole`
  fixture), which are `roles:else` blocks with Level 2 counterparts. The reference holds no live roles code (a step 0 check), and
  every other copy none of its traces.
- **One source:** the changeset copies each account's role onto its built-in role and drops `users.role`; the token
  carries no role claim; `UserResponse` loses `role` (with tenancy it never had one). Signup gives the built-in USER
  in the account's own transaction: `UserService.signup` runs a `TransactionTemplate` that saves the account and
  publishes `AccountCreated`, whose listener (`PersonAccessService`) writes the assignment, because the two services
  would otherwise depend on each other.
- **The per-request read** is `TokenPermissions` (the Level 2 `RolePermissions`, replacing sign-in's) over plain JDBC
  (`NamedParameterJdbcTemplate`) in a read-only transaction, one `UNION ALL` statement of the role rows and the
  personal rows of active accounts, `valid_until` in SQL. JDBC, not Hibernate: Hibernate's statistics, which every
  list's statement-count test reads, don't see it, so those tests pass unchanged; the read's own count is a test
  component counting the template's queries (`StatementCounter`, scanned into the shared context). Its outage is an
  `AuthenticationException` subclass the entry point answers with the failure's 503 code (a plain exception escaped
  the filter chain as a 500, and an `AuthenticationServiceException` is rethrown by Spring's failure handler); the
  unchanged Level 1 `PoolExhaustionIntegrationTest` proves it.
- **Names:** the enum `Role` stays (the built-in roles, and `RoleTest`); the table's entity is `AccessRole`, a
  person's rows `RoleAssignment` and `PersonalGrant`, all in the user module, which owns the accounts the one read
  joins. Two aggregates: a role with its permissions (`AccessRoleService`), a person's access (`PersonAccessService`);
  a role's edit reads both, so it is a Manager (`AccessRoleManager`).
- **Endpoints:** `GET /permissions` is `@SignedIn`: it is the Permission enum the API document already publishes,
  with each constant's area, reach and prerequisites (what a role editor needs), never who holds what. Roles at
  `/roles` (`ROLES_MANAGE`); a person's access at `/users/{id}/access`, `/users/{id}/roles/{roleId}` and
  `/users/{id}/permissions/{permission}` (`ACCESS_GRANT`, which needs `ACCOUNT_READ`, Phase 0's): one assignment or
  grant per PUT or DELETE, idempotent, so two admins never overwrite each other's lists (no version to send).
  Creates answer 201 without `Location`, as the golden todo creates do (REST-05).
- **Safety rule 3, why:** with personal DENYs, an admin holding less could sabotage one holding more (deny them the
  permission they would use to undo it). "You manage only people who hold nothing you don't" closes it, and with rule
  2 it keeps the caller of any person's change a holder of what they used, so rule 6 can't be tripped that way.
- **Safety rule 6, why and how:** without an active account holding `ROLES_MANAGE` and one holding `ACCESS_GRANT`,
  nobody could ever change access again (rule 1 forbids giving what you don't hold), so both are kept, for good:
  an expiring grant never counts as the last holder, since it would lapse without any write to refuse. Counted
  after the change in its transaction; in practice a role's edit trips it (a person's change keeps its caller, rules
  2 and 3). Serialised by the account rows: a person's change locks caller and person, a role's edit the caller and
  every holder for good, so any two changes that could each remove a last holder share a locked row. The runbook
  names the operator's SQL for the case it happened outside the application.
- **Rule 7:** `version` on the role; every change locks, in id order, the caller's, the person's and every for-good
  holder's account rows and re-reads under the lock. Proven by a test that holds both admins' rows while they deny
  each other: one 200, one 403 `ACCESS_DENIED`, one DENY written.
- **Review round 1** found three ways past rules 1 and 3, now closed and tested: a suspended superior read as holding
  nothing (the rules now read a person's grants whatever their status), taking a DENY away handing back a dependent
  the caller lacks (rule 1 is also checked on the result), and a role's edit stripping a permission the editor lacks
  from everyone above them (an edit needs the old permissions too). Person changes now also lock the for-good
  holders, so two callers holding through expiring grants can't each remove a different last holder.
- **Built-in roles** can't be deleted (409 `ROLE_BUILT_IN`): signup gives the USER one. Their seed is literal SQL in
  0037, written from the enum on the day; `BuiltInRolesIntegrationTest` compares them on the test database (tests
  that edit a built-in role restore it).
- **Tenancy:** built in the next batch, below.

**Level 2 with tenancy (2026-11-19): the `roles` piece with either tenancy piece, built.** Bootstrap no longer refuses
the combination; `verify.sh` 2o (row mode) and 2p (schema per tenant) copy every piece with it, each on the upgrade
path. Decided while building it:
- **Where the access lives:** roles are the tenant's, a person's roles and grants are on their `members` row (TEN-16),
  so a tenant's data stays a closed graph. Row mode: the four tables carry `tenant_id` under forced policies, keys
  per TEN-33 (the tenant leading where no owner or parent column does), member-tenant dependency statistics, and
  the per-request read names its tenant beside the policy (TEN-36, TEN-37). Schema per tenant: a tenant changeset,
  and the per-request read names the platform's account, membership and tenant tables (its one exception to TEN-21,
  allowed by name in `TenancyArchTest`): one statement still, joined with all three statuses.
- **One source per tenant, expand then contract:** the membership's role column goes, and the account's (with tenancy it
  never decided anything). Row mode copies and drops in one changeset (one transaction; the copy lifts `FORCE` on
  every tenant table it reads, members and roles too: with only `user_roles` lifted, the forced policies hid every
  member and role from the owner and the copy inserted nothing, which the upgrade test caught). Schema per tenant
  can't: the tenant schemas migrate one after another, after the platform. So the platform's `0038` makes the column
  nullable, each schema's `0037` copies its own tenant's memberships and empties them there in the same
  transaction, and `0039` drops the column behind a precondition (no membership of a tenant with a schema still holds
  a role), skipped and tried again at every start: a deploy after the migrations ran drops it, a FAILED schema holds
  it. A new schema's `0037` copy is marked ran once the column is gone. The previous release reads the column, so
  neither mode deploys across it rolling (as Batch B's no-tenancy changeset).
- **The members' endpoints:** the role PUT goes (one source: the assignment endpoints); a member's add gives the
  tenant's built-in `USER` (safety rule 1 checked: the caller holds all of it); a removal is an access change
  (rules 2, 3 and 6) and takes every role and grant of the person in the tenant, so a re-add never hands back what
  someone else gave. The members' responses lose `role` and `version` (nothing sends it back now): a breaking
  change, announced in the API texts. `TENANT_MEMBER_LAST_ADMIN` goes with the piece: the rule is rule 6.
- **Rule 6 per tenant keeps `TENANT_MEMBERS_MANAGE` too:** without it nobody could add a member again (rule 1), which
  is what Level 1's "every tenant keeps an ADMIN" protected.
- **The lock is the tenant's row** (gap 11 of Batch B): one fixed row every access change in the tenant locks first,
  so the holders a change counts can't move before it commits; the members' own rows need no lock. Without tenancy
  there is no such row, so the account rows stay the lock and the holders for good are read again once locked,
  locking any newcomer, until the set is stable (a newcomer locked after a lower id is held could meet another
  change the other way: PostgreSQL's deadlock detection answers one of them 503 `DATABASE_CONTENTION`, rare and
  retryable, against two changes that would have locked disjoint sets).
- **The cycle between the two services** (`MembershipService` would need the access for a member's add and
  removal, `PersonAccessService` the membership for a person's check): a Manager, `TenantMemberManager`, holds the
  add, the removal and the provisioning listener; the two services never call each other the other way.
- **Built-in roles of a new tenant:** schema per tenant from the tenant changelog's literal seed; row mode copied from
  the enum at its activation, only what is missing (the changeset seeded every tenant that existed, PENDING
  included, TEN-34), and the owner's `ADMIN` given there too (the owner is the tenant's oldest membership: nobody
  else can join before its first activation). `BuiltInRolesIntegrationTest` compares every tenant's copies.
- **Staff's suspension per tenant:** a platform transaction can't read a tenant's tables (the tenant is fixed when a
  transaction begins), so the suspension locks the account's row, then each open tenant's of the person in id
  order, and counts each tenant's other active managers for good in a short read-only transaction under that
  tenant's scope (`requireManagersRemainWithout`): the tenant rows it holds are the ones every access change there
  locks first, so nothing counted can change before the suspension commits. A second connection for a moment, on a
  staff-only path.
- **Impersonation's default member:** the oldest member who manages the members for good (Level 1's oldest ADMIN),
  read under the tenant's scope; a platform token holds no tenant permission and reads nothing.
- **The roles dropdown** (Batch B's gap 2): `GET /roles/dropdown?for=`, reach `ALL`, rule 1 as a `NOT EXISTS` in the
  one statement, so a picker offers what the assignment accepts.
- **A guard narrowed:** row mode's `BackfillRollbacksIntegrationTest` flagged any rollback write without a lifted
  `FORCE`, a platform table's too (the memberships' role restored); it now checks the tables under a forced policy.
- **The change history's guards** (Batch B's gap 4): every audited table carries the trigger and every secret-like
  column is among its arguments (each piece names its tables), the purge goes past one batch, and its batch is an
  init plan on `idx_audit_log_changed_at` (the statement takes named parameters so the test explains it).


## A flag's permission proven equal to its endpoint's: `AllowedActionContract` (AUTHZ-07; 2026-11-23)

A flag's permission is written twice: in its `toAllowedAction` call and in the endpoint's `@Requires`. The
library-desk review found tests that checked one flag each, with the permission copied into the test, so a changed
endpoint left both the flag and its test agreeing on the old permission.
- **Decision:** the test base's `support/AllowedActionContract` (core, so it compiles without the example).
  - A resource's test names each flag and its endpoint (`.flag("markDone", "PATCH /api/{version}/plans/{id}/status")`),
    never the permission. The helper finds the handler in `RequestMappingHandlerMapping` by method and exact path
    pattern and reads its `@Requires`.
  - The rows must equal the record's `AllowedAction` components.
  - Through the base's `holding()` (which works the same with the roles piece and with tenancy), a caller holding
    every permission but the endpoint's must see `ACCESS_DENIED` alone. A caller holding one accepted permission,
    its prerequisites and the read's must never see it.
  - One readable test stays beside it: the message equals the endpoint's 403 `detail`.
- **Coverage:** `archunit/AllowedActionContractArchTest` fails naming each record that main code builds and that has
  an `AllowedAction` component, when no test passes it to `AllowedActionContract.of` on the call's own line.
  - A record nothing builds is skipped, so a copy without the example passes with `SelectableDropdownItem` unused.
  - The check is per record type: a second endpoint that answers a record already contracted elsewhere is not
    forced into a second contract. That stays a review finding (tests.md row 28l).
- **Rejected:**
  - Per-flag tests that write the permission: they copy it a third time and drift with the other two.
  - Deriving the flag's permission from `@Requires` at runtime: the service and its policy would depend on the web
    layer's handlers.
  - "The test class mentions both types" as the coverage check: an import or another assertion passes it. The
    ArchUnit check pairs the class literal with the `of` call on the same line; the bytecode keeps no link between
    an argument and its call, and a split line fails the test, never passes it wrongly.
  - Spring's repackaged ASM, to read the exact argument: an internal package.
  - Checking every main record with a flag whether or not it is built: it fails copies that keep a shared shape unused.
- **Approximated:** whether a contract covers every endpoint answering the record (the check is by type).
