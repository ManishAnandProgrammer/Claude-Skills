# Review profile

<!--
Per-project configuration for the house-review skill. Save it as
<project-root>/.claude/review-profile.md. Every {{key}} in references/*.md comes from here.
Fill values from the codebase and CLAUDE.md, not from memory. Use `none` for anything the
project doesn't have.
-->

## Project

| Key | Value | Notes |
|---|---|---|
| `mainBranch` | `main` | Base for diff mode; read from git when the profile is written (`git symbolic-ref --short refs/remotes/origin/HEAD`, else the current branch of a new repository), never left at `main` unchecked |
| `buildCommand` | `./mvnw -B verify` | The full gate command, run every review: compile, every test, ArchUnit and the quality gates (coverage minimum, static analysis: CFG-11). Never `test` alone: green means this command. Include env/JDK prefixes the project needs |
| `testCommand` | `same` | Separate test command, if any |
| `sourceRoots` | `src/main/java` | |
| `testRoots` | `src/test/java` | |
| `migrationsDir` | `src/main/resources/db/changelog` | Liquibase / Flyway location, or `none` |
| `reportDir` | `docs/reviews` | Where path/whole-codebase reports are saved |
| `subagentThreshold` | `25` | Files above which the review is split across parallel reviewers |

## Architecture

| Key | Value | Notes |
|---|---|---|
| `rootPackage` | `com.example` | |
| `modules` | one line each: `order: owns the order lifecycle` | ARCH-01. A module whose line can't be written is a finding |
| `moduleContracts` | `api-facades` | `api-facades` (modulith style: entities stay inside, other modules use `<module>.api`) or `service-entity-methods`. The project owner decides |
| `layerPackages` | `controller, service, repository, domain, mapper, dto` | ARCH-03 |
| `repositoryAccess` | `aggregate-service` | Who may use a repository: the service of its aggregate only (ARCH-15). `service, mapper` only for a project that hasn't adopted ARCH-15 |
| `aggregates` | `none` | ARCH-15: `Root (OwningService): Member, Member`, e.g. `Order (OrderService): OrderLine, Shipment`; one entry per aggregate root, even without members (`Tag (TagService)`), separated by `;`. Mirrors `AggregateArchTest.AGGREGATES` |
| `requestSuffix` | `Request` | Suffix of HTTP input bodies (`TodoCreateRequest`), or `Payload` / `Command`: one per project (SMELL-20). GET parameter objects are always `*Filter`, responses `*Response` |
| `instances` | `multiple` | `single` or `multiple`. Changes the CONC / scheduler / rate-limit severities |
| `hotPaths` | `none` | Endpoints that answer PERF-01's four questions on every change |
| `archTests` | `none` | Existing ArchUnit classes; their carve-outs go in the deviation register |
| `publishedContracts` | `none` | Non-OpenAPI contracts and consumers: webhook docs, frontend changelog, k6 scripts |
| `tenancy` | `none` | `none`, `row` or `schema` (the bootstrap piece `tenancy-schema`). Anything but `none` loads `references/tenancy.md` (TEN-xx) and the profile-optional tenant rules; `[schema]` / `[row]` rules apply to their mode only |
| `media` | `none` | `on` when the project has the bootstrap piece `media`: the code has `<rootPackage>.media.api.Uploads` (the crud profile's `mediaApi` names that package). `on` loads `references/media.md` (MEDIA-xx) |
| `metadata` | `none` | `on` when the project has the bootstrap piece `metadata`: the code has `<rootPackage>.metadata.api.Metadata` and `Counters`. `on` loads `references/metadata.md` (META-xx) |

## API

| Key | Value | Notes |
|---|---|---|
| `urlPrefix` | `/api/{version}` | REST-07: class-level paths start with it; versions are Spring API versioning in the path segment (`v1`) |
| `DropdownItem` / `DropdownFilter` | FQNs | REST-13 house dropdown standard (cursor-paged) |
| `ObjectWrapper` / `ListWrapper` / `MapWrapper` / `PaginationWrapper` / `CursorWrapper` | FQNs | REST-11 |
| `exceptionHandler` | FQN | REST-06 |
| `errorCodeType` | `none` | FQN of the ErrorCode enum once it exists |
| `cursorLists` | `all lists not in offsetLists` | Cursor (keyset) paging, the default, by REST-08's consumer rule; dropdowns always |
| `offsetLists` | `none` | Offset lists by REST-08's consumer rule, as **entity class names** (`TodoList`), each with its consumer |
| `unpagedLists` | `none` | REST-11: unpaged (`ListWrapper`) lists the code doesn't bound (not an enum's values, a configured maximum or a fixed relation), each with the developer's statement |
| `NotXss` | `escape-on-output` | A JSON API whose clients render text as text; or the FQN of the anti-XSS validator. Never `none`: REST-10 then finds every free-text field |
| `NoEmoji` | `none` | FQN of the no-emoji constraint, for `noEmojiFields` |
| `noEmojiFields` | `none` | Fields where `@NoEmoji` applies (codes, identifiers) |
| `dropdownSecurity` | `@PreAuthorize("isAuthenticated()")` | REST-13: the dropdowns' read check; a role only when the resource's reads need one |
| `tokenRevocation` | `short-lived(15)` | `blacklist` (jti blacklist with a TTL, any store, no DB connection on a cache hit), `short-lived(<max minutes>)`, or `session-version` (the bootstrap piece: a version per account or membership in every token, checked per request through Redis; SEC-07) |
| `edgeRateLimiter` | `none` | e.g. `nginx`: the edge proxy is the IP limiter in prod (REST-14) |
| `metricsAuth` | `pending` | How the metrics scrape is secured (CFG-06); health stays unauthenticated |
| `apiDocsLocation` | `controller/api/<Name>Api` | Where OpenAPI annotations live (REST-20) |

## Persistence

| Key | Value | Notes |
|---|---|---|
| `idStrategy` | `uuidv7` | DATA-09: `uuidv7`, `long-sequence`, ... |
| `auditedTables` | `none` | DATA-08: tables with trigger-based change history |
| `auditMechanism` | `pg-trigger` | DATA-08 |
| `retentionExceptions` | `none` | DATA-07: tables kept for legal retention instead of deleted |

## Thresholds

| Key | Default | Notes |
|---|---|---|
| `maxMethodLines` | `30` | Blank and comment lines not counted |
| `maxParams` | `4` | Records and canonical constructors exempt |
| `maxClassLines` | `300` | |
| `deviationAlarm` | `3` | Accepted deviations per rule before "a pattern is forming" |

## Logging

| Key | Value | Notes |
|---|---|---|
| `logPattern` | `house` | CFG-07 house pattern (async; MDC context; text on dev, JSON on prod) |
| `clientIpLogging` | `truncated` | `raw`, `truncated`, `hashed` or `none`: set in `app.logging.client-ip`, with its reason |
| `requestCompletionLog` | `on` | One line per request; switched by `app.logging.request-completion.enabled` |

## Conventions

<!--
The project's deliberate choices where they differ from, or refine, the reference rules. Each
needs a reason. Code that follows them is fine; code that breaks them is a deviation (SKILL.md
Step 7). Reports list these under "Notes the profile overrides".
Bootstrap and crud-gen record here: the signup policy and its accepted risk (immediate accounts
reveal whether an email is registered), and per shared resource (no owner column) who writes it
and who reads it, personal fields included (SEC-01).
-->

| Convention | Overrides / refines | Why |
|---|---|---|
| | | |

## Deviation register

<!--
One row per accepted deviation. Each row must match a marker comment in the code:
// DEVIATION(<RULE-ID>, accepted <date>): <reason>. Exception, do not copy.
-->

| Rule | File / symbol | Reason | Accepted on | Accepted by |
|---|---|---|---|---|
| | | | | |
