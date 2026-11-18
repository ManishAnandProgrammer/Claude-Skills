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
| `modules` | `todo: a user's todo lists and todos (the golden crud-gen module)`, `planning: days set aside for a todo (a parent in another module, no overlapping ranges)`, `user: accounts and signing in`, `metadata`, `media` (the optional pieces); `common`, `config`: infrastructure | |
| `moduleContracts` | `api-facades` | |
| `layerPackages` | `controller, service, repository, domain, mapper, dto` | |
| `repositoryAccess` | `aggregate-service` | ARCH-15, enforced by `AggregateArchTest` |
| `aggregates` | `User (UserService); RefreshToken (TokenService); TodoList (TodoListService); Todo (TodoService): TimeEntry, TodoTag, TodoAttachment; Tag (TagService); Plan (PlanService); ConfigurationMetadata (MetadataService); Counter (CounterService); MediaFile (MediaFileService); UploadPolicy (UploadPolicyService)` | TimeEntry is a member of Todo: nested `/todos/{todoId}/time-entries`, no service of its own. TodoTag (the `todo_tags` rows of the Todo–Tag many-to-many) is a member of Todo, the side that writes it (`PUT /todos/{id}/tags`, `Todo.replaceTags`); Tag stays its own aggregate, reached through `TagService.getOwnedTags` (owner's decision 2026-11-14) |
| `requestSuffix` | `Request` | |
| `instances` | `multiple` | |
| `hotPaths` | `none` | |
| `archTests` | `ModuleBoundaryArchTest`, `LayerArchTest`, `ConventionArchTest`, `AggregateArchTest` | No carve-outs |
| `publishedContracts` | `none` | |
| `tenancy` | `none` | The reference runs without tenancy; the tenancy pieces are the skills repo's overlays/, proven in bootstrap copies (verify.sh 2g, 2h) |

## API

| Key | Value | Notes |
|---|---|---|
| `urlPrefix` | `/api/{version}` | Class-level `@RequestMapping("/api/{version}/<resource>")`; methods relative with `version = "v1+"`; served versions in `spring.mvc.apiversion.supported` |
| `DropdownItem` / `DropdownFilter` | `com.example.app.common.dropdown.*` | Dropdowns are cursor-paged (`CursorWrapper`) |
| `ObjectWrapper` / `ListWrapper` / `MapWrapper` / `PaginationWrapper` / `CursorWrapper` | `com.example.app.common.wrapper.*` | |
| `exceptionHandler` | `com.example.app.common.exception.GlobalExceptionHandler` | |
| `errorCodeType` | `com.example.app.common.exception.ErrorCode` | |
| `cursorLists` | `Todo`, `TimeEntry`, dropdowns | Keyset; `pageInfo {nextCursor, hasMore}`; no counts |
| `offsetLists` | `TodoList`, `Tag` | Admin-style screens that need page numbers and a total |
| `unpagedLists` | `MembershipService.mine` (tenancy overlay, `GET /users/me/tenants`) | Developer's statement: the tenant switcher shows every tenant a person belongs to, and a person belongs to a few; no code caps it |
| `NotXss` | `escape-on-output` | JSON API; clients render text |
| `noEmojiFields` | `none` | |
| `tokenRevocation` | `session-version` | The reference runs with the session-version piece on (`short-lived(15)` stays its access-token bound); copies without it are `short-lived(15)` |
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
| Algorithm invariants stay in code with a why-comment (bcrypt's 72 bytes, the 8-character password floor, 256-bit tokens, the bcrypt cost `StartupChecks.PASSWORD_HASH_STRENGTH`) | O6 no-constants rule, SMELL-17 | They aren't tunables: changing them breaks security. The bcrypt cost is a security decision taken with a load test (it sets sign-in CPU and the `sign-in` admission cap), never a per-deployment setting: `app.security.password-hash-strength` exists only so the test profile alone can run at 4 (each test account costs two hashes), and startup refuses any value but 12 elsewhere |
| The house kit keeps methods the reference itself doesn't call: `MapWrapper`, `ListWrapper`, `ObjectWrapper.setResponse`, `addExtraDetails`, `AppException.withCause` | SMELL-18 | Bootstrap copies the kit and crud-gen's generated code calls it; deleting a method because the reference doesn't use it would break generated modules. Dead code elsewhere is still a finding |
| Graceful shutdown drains for 30 s (`spring.lifecycle.timeout-per-shutdown-phase`), stop grace 40 s: enough for the normal request, not for a rare worst-case signup or login (about 60 s) | REL-13 | Owner's decision (2026-11-13): the load balancer times out around 60 s anyway, so a longer drain would finish requests whose client is already gone; a short, predictable drain is safer for deployments |
| A tag's delete cascades to its `todo_tags` rows (`fk_todo_tags_tag_id_owner_id ... ON DELETE CASCADE`, the tag side only): it is never blocked, and the todos that carried it keep their version | error-contract `*_IN_USE`, DATA-06 (a change without a version bump) | Owner's rule (2026-11-14): only the label is lost, never a todo or its content; documented in `TagApi`. A link written while its tag is deleted is refused by that foreign key: 404 `TAG_GONE` (nothing locks the tags) |
| The todo list costs two statements per batch, not one: the batch, then one query for the tags of all its rows | crud-gen tests.md row 21 (cursor lists: 1) | DATA-26's two steps: list rows carry a many-to-many without a collection fetch, and no `@Formula` puts the tag subquery on every other load of a todo |
| The rename of `app.limits.not-after-today.tolerance-days` to `tolerance` (a `Period`) is not refused at startup | CFG-10, CFG-12 (a renamed setting's old name refused while set) | No environment variable ever named it, and a `Period`'s bare number is days, the old meaning; a refusal would ship into every new copy, which never had the key (2026-11-18) |
| The breached-password `RestClient` is built with `RestClient.builder()`, not Boot's injected `RestClient.Builder`, with the observation registry, a constant-URI `RangeLookupObservationConvention`, timeouts and a failure-counting interceptor set by hand (`BreachedPasswordConfig`) | OPS-06 | Boot's `RestClient.Builder` lives in a module the application doesn't otherwise need; the manual wiring gives the same `http.client.requests` observation and `traceparent` |

## Deviation register

| Rule | File / symbol | Reason | Accepted on | Accepted by |
|---|---|---|---|---|
| | | | | |
