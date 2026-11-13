# Review profile: reference-app

The house reference application (see README.md). Reviewing it with `/house-review all` must find
nothing: it is the code the skills copy.

## Project

| Key | Value | Notes |
|---|---|---|
| `mainBranch` | `main` | |
| `buildCommand` | `./mvnw -B verify` | JDK 25, Docker; or `../scripts/verify.sh` for the copy smoke test too |
| `testCommand` | `same` | |
| `sourceRoots` | `src/main/java` | |
| `testRoots` | `src/test/java` | |
| `migrationsDir` | `src/main/resources/db/changelog` | |
| `reportDir` | `docs/reviews` | |
| `subagentThreshold` | `25` | |

## Architecture

| Key | Value | Notes |
|---|---|---|
| `rootPackage` | `com.example.app` | Renamed by `bootstrap-copy.sh` |
| `modules` | `todo: a user's todo lists and todos (the golden crud-gen module)`, `user: accounts and signing in`; `common`, `config`: infrastructure | |
| `moduleContracts` | `api-facades` | |
| `layerPackages` | `controller, service, repository, domain, mapper, dto` | |
| `repositoryAccess` | `aggregate-service` | ARCH-15, enforced by `AggregateArchTest` |
| `aggregates` | `User (UserService)`, `RefreshToken (TokenService)`, `TodoList (TodoListService)`, `Todo (TodoService; members: TimeEntry)`, `Tag (TagService)` | TimeEntry is a member of Todo: nested `/todos/{todoId}/time-entries`, no service of its own |
| `requestSuffix` | `Request` | |
| `instances` | `multiple` | |
| `hotPaths` | `none` | |
| `archTests` | `ModuleBoundaryArchTest`, `LayerArchTest`, `ConventionArchTest`, `AggregateArchTest` | No carve-outs |
| `publishedContracts` | `none` | |
| `multiTenant` | `false` | |

## API

| Key | Value | Notes |
|---|---|---|
| `urlPrefix` | `/api/{version}` | Class-level `@RequestMapping("/api/{version}/<resource>")`; methods relative with `version = "v1+"`; served versions in `spring.mvc.apiversion.supported` |
| `DropdownItem` / `DropdownFilter` | `com.example.app.common.dropdown.*` | Dropdowns are cursor-paged (`CursorWrapper`) |
| `ObjectWrapper` / `ListWrapper` / `MapWrapper` / `PaginationWrapper` / `CursorWrapper` | `com.example.app.common.wrapper.*` | |
| `exceptionHandler` | `com.example.app.common.exception.GlobalExceptionHandler` | |
| `errorCodeType` | `com.example.app.common.exception.ErrorCode` | |
| `cursorLists` | `Todo`, `TimeEntry`, dropdowns | Keyset; `pageInfo {nextCursor, hasMore}`; no counts |
| `offsetLists` | `TodoList`, `Tag` | Bounded per user; page numbers and counts |
| `NotXss` | `escape-on-output` | JSON API; clients render text |
| `noEmojiFields` | `none` | |
| `tokenRevocation` | `short-lived(15)` | |
| `edgeRateLimiter` | `none` | |
| `metricsAuth` | `network-only` | `/actuator/prometheus` has no token: served on the management port only, never published; `StartupChecks` refuses a missing or shared management port (test profile alone excepted) |
| `apiDocsLocation` | `controller/api/<Name>Api` | |

## Persistence

| Key | Value |
|---|---|
| `idStrategy` | `uuidv7` |
| `auditedTables` | `none` |
| `auditMechanism` | `pg-trigger` |
| `retentionExceptions` | `none` |

## Thresholds

| Key | Value |
|---|---|
| `maxMethodLines` | `30` |
| `maxParams` | `4` |
| `maxClassLines` | `300` |
| `deviationAlarm` | `3` |

## Logging

| Key | Value |
|---|---|
| `logPattern` | `house` |
| `clientIpLogging` | `truncated` |
| `requestCompletionLog` | `on` |

## Conventions

| Convention | Overrides / refines | Why |
|---|---|---|
| Create calls `saveAndFlush`; update changes the managed entity and calls `flush()` before mapping | DATA-25 | The response carries the new `version` and audit fields |
| Filters are mutable classes | JAVA-03 | Query-parameter binding goes through setters |
| Text columns are `text`; request Strings carry `@MaxLength("<key>")` with the number in `app.limits.text` | DATA-12 | One length contract, configurable (owner's decision 2026-11-13) |
| Algorithm invariants stay in code with a why-comment (bcrypt's 72 bytes, the 8-character password floor, 256-bit tokens, the bcrypt cost `SecurityConfig.BCRYPT_STRENGTH`) | O6 no-constants rule, SMELL-17 | They aren't tunables: changing them breaks security. The bcrypt cost is a security decision taken with a load test (it sets sign-in CPU and the `sign-in` admission cap), never a per-deployment setting: a lowered value would silently weaken every new hash |
| The house kit keeps methods the reference itself doesn't call: `MapWrapper`, `ListWrapper`, `ObjectWrapper.setResponse`, `addExtraDetails`, `AppException.withCause` / `withFailedValidations` | SMELL-18 | Bootstrap copies the kit and crud-gen's generated code calls it; deleting a method because the reference doesn't use it would break generated modules. Dead code elsewhere is still a finding |
| Graceful shutdown drains for 30 s (`spring.lifecycle.timeout-per-shutdown-phase`), stop grace 40 s: enough for the normal request, not for a rare worst-case signup or login (about 60 s) | REL-13 | Owner's decision (2026-11-13): the load balancer times out around 60 s anyway, so a longer drain would finish requests whose client is already gone; a short, predictable drain is safer for deployments |
| The breached-password `RestClient` is built with `RestClient.builder()`, not Boot's injected `RestClient.Builder`, with the observation registry, a constant-URI `RangeLookupObservationConvention`, timeouts and a failure-counting interceptor set by hand (`BreachedPasswordConfig`) | OPS-06 | Boot's `RestClient.Builder` lives in a module the application doesn't otherwise need; the manual wiring gives the same `http.client.requests` observation and `traceparent` |

## Deviation register

| Rule | File / symbol | Reason | Accepted on | Accepted by |
|---|---|---|---|---|
| | | | | |
