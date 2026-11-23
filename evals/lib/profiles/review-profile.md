# Review profile: @APP@

A project copied from the house reference application with the pieces @PIECES@ (written by scripts/eval.sh as
/crud-gen bootstrap would leave it; the owner confirmed it).

## Project

| Key | Value | Notes |
|---|---|---|
| `mainBranch` | `main` | |
| `buildCommand` | `./mvnw -o -B verify` | JDK 25 (`JAVA_HOME` is set), Docker reachable; offline: every dependency is in the local repository |
| `testCommand` | `same` | |
| `sourceRoots` | `src/main/java` | |
| `testRoots` | `src/test/java` | |
| `migrationsDir` | `src/main/resources/db/changelog` | |
| `reportDir` | `docs/reviews` | |
| `subagentThreshold` | `25` | |

## Architecture

| Key | Value | Notes |
|---|---|---|
| `rootPackage` | `@ROOT_PACKAGE@` | |
| `modules` | @MODULE_LINES@; `common`, `config`: infrastructure | |
| `moduleContracts` | `api-facades` | |
| `layerPackages` | `controller, service, repository, domain, mapper, dto` | |
| `repositoryAccess` | `aggregate-service` | ARCH-15, enforced by `AggregateArchTest` |
| `aggregates` | `@AGGREGATES@` | |
| `requestSuffix` | `Request` | |
| `instances` | `multiple` | |
| `hotPaths` | `none` | |
| `archTests` | `ModuleBoundaryArchTest`, `LayerArchTest`, `ConventionArchTest`, `AggregateArchTest` | No carve-outs |
| `publishedContracts` | `none` | |
| `tenancy` | `none` | |
| `media` | `@MEDIA@` | |
| `metadata` | `@METADATA@` | |

## API

| Key | Value | Notes |
|---|---|---|
| `urlPrefix` | `/api/{version}` | |
| `DropdownItem` / `DropdownFilter` | `@ROOT_PACKAGE@.common.dropdown.*` | Dropdowns are cursor-paged (`CursorWrapper`) |
| `ObjectWrapper` / `BoundedListWrapper` / `MapWrapper` / `PaginationWrapper` / `CursorWrapper` | `@ROOT_PACKAGE@.common.wrapper.*` | |
| `exceptionHandler` | `@ROOT_PACKAGE@.common.exception.GlobalExceptionHandler` | |
| `errorCodeType` | `@ROOT_PACKAGE@.common.exception.ErrorCode` | |
| `cursorLists` | `@CURSOR_LISTS@` | |
| `offsetLists` | `@OFFSET_LISTS@` | |
| `unpagedLists` | `none` | |
| `NotXss` | `escape-on-output` | JSON API; clients render text |
| `NoEmoji` | `none` | |
| `noEmojiFields` | `none` | |
| `dropdownSecurity` | `@SignedIn` + `for` | |
| `authorization` | `@AUTHORIZATION@` | |
| `tokenRevocation` | `@TOKEN_REVOCATION@` | |
| `edgeRateLimiter` | `none` | |
| `metricsAuth` | `network-only` | `/actuator/prometheus` is served on the management port only |
| `apiDocsLocation` | `controller/api/<Name>Api` | |

## Persistence

| Key | Value |
|---|---|
| `idStrategy` | `uuidv7` |
| `shippedRelease` | `none` |
| `auditedTables` | `@AUDITED_TABLES@` |
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
| Text columns are `text`; request Strings carry `@MaxLength("<key>")` with the number in `app.limits.text` | DATA-12 | One length contract, configurable |
| The house kit keeps methods the project doesn't call yet: `MapWrapper`, `BoundedListWrapper`, `addExtraDetails`, `AppException.withCause` | SMELL-18 | Bootstrap copies the kit and crud-gen's generated code calls it |
| Signup creates the account at once (201) and so reveals whether an email is registered | REST-14 (enumeration) | The owner accepted the enumeration risk at bootstrap: the sign-in piece implements immediate accounts only |
@EXAMPLE_CONVENTIONS@
## Deviation register

| Rule | File / symbol | Reason | Accepted on | Accepted by |
|---|---|---|---|---|
| | | | | |
