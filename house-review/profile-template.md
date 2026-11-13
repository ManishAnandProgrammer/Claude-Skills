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
| `mainBranch` | `main` | Base for diff mode |
| `buildCommand` | `./mvnw -B verify` | Compile + full tests (run every review). Include env/JDK prefixes the project needs |
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
| `modules` | one line each: `story: owns the story lifecycle` | ARCH-01. A module whose line can't be written is a finding |
| `moduleContracts` | `api-facades` | `api-facades` (modulith style: entities stay inside, other modules use `<module>.api`) or `service-entity-methods`. The project owner decides |
| `layerPackages` | `controller, service, repository, domain, mapper, dto` | ARCH-03 |
| `repositoryAccess` | `service` | Layers allowed to use repositories, e.g. `service, mapper` (mapper `getReferenceById`) |
| `instances` | `multiple` | `single` or `multiple`. Changes the CONC / scheduler / rate-limit severities |
| `hotPaths` | `none` | Endpoints that answer PERF-01's four questions on every change |
| `archTests` | `none` | Existing ArchUnit classes; their carve-outs go in the deviation register |
| `publishedContracts` | `none` | Non-OpenAPI contracts and consumers: webhook docs, frontend changelog, k6 scripts |
| `multiTenant` | `false` | Enables the profile-optional tenant rules |

## API

| Key | Value | Notes |
|---|---|---|
| `urlPrefix` | `/api/v1` | REST-07 |
| `DropdownItem` / `DropdownFilter` | FQNs | REST-13 house dropdown standard |
| `ObjectWrapper` / `ListWrapper` / `PaginationWrapper` | FQNs | REST-11; paginated lists use `PaginationWrapper` (explicit `pageInfo`) |
| `exceptionHandler` | FQN | REST-06 |
| `errorCodeType` | `none` | FQN of the ErrorCode enum once it exists |
| `cursorLists` | `none` | Lists that must use cursor pagination (feeds, history, growing tables) |
| `offsetLists` | `admin tables` | Lists where offset paging with a cap is fine |
| `NotXss` | `none` | FQN of the anti-XSS validator, or `escape-on-output` with a link to the policy |
| `noEmojiFields` | `none` | Fields where `@NoEmoji` applies (codes, identifiers) |
| `tokenRevocation` | `short-lived(15)` | `blacklist` (jti blacklist with a TTL, any store, no DB connection on a cache hit) or `short-lived(<max minutes>)` |
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
Step 6). Reports list these under "Notes the profile overrides".
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
