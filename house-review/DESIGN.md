# house-review: design log

Working log of the decisions taken while building the skill with the user, step by step.
Sources: the user's notes in `~/Downloads/Send-To-MIS/`.

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
    window.

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
