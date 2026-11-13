# Bootstrap (`/crud-gen bootstrap`)

One-time setup of the shared house infrastructure that generated CRUD depends on. Run it on a
new project, or on an existing one missing pieces. After bootstrap, a house-review of the new
project should find nothing but its business code.

**Rules:**
- Inspect first and skip what already exists (report it as present).
- **Never add a dependency without asking** (libraries-first rule): list each one with its
  current version from Maven Central and its Boot compatibility, and wait for the decision.
- Every piece follows the house-review references named below; read them first.
- No `var`, text blocks, the house conventions.

## 0. Ask, then write the profiles
With **AskUserQuestion**:
- `rootPackage`;
- the modules and their one-line owners;
- `moduleContracts` (suggest `api-facades`, modulith style);
- `instances` (single or multiple);
- `idStrategy` (UUIDv7 or TSID);
- `tokenRevocation`;
- the log client-IP mode;
- whether the project is multi-tenant.

Write both `.claude/crud-profile.md` and `.claude/review-profile.md` from their templates.

## 1. Error contract (error-contract.md)
`<root>.common.exception`:
- `ErrorCode` (general codes: `VALIDATION_FAILED`, `REQUEST_*`, `ACCESS_DENIED`,
  `AUTHENTICATION_REQUIRED`, `DUPLICATE_RECORD`, `OPTIMISTIC_LOCK_CONFLICT`, `ILLEGAL_ARGUMENT`,
  `DATABASE_CONTENTION`, `QUERY_TIMEOUT`, `DATABASE_UNAVAILABLE`, `SERVER_BUSY`,
  `UNEXPECTED_ERROR`);
- `ErrorCodeHttpStatusMapper` (`EnumMap`, throws for unmapped codes);
- `AppException` (`of`, `withCause`, `withFailedValidations`, `supply`, `withExtension`);
- `TransientDatabaseFailure`;
- `GlobalExceptionHandler` implementing the ERR-02 table, with a constraint-name → ErrorCode map;
- `MessageSourceConfig` (UTF-8, `alwaysUseMessageFormat`, validator uses it,
  `AcceptHeaderLocaleResolver`);
- `messages/message_en.properties`;
- a unit test that every `ErrorCode` has a mapping and a message.

## 2. API primitives
`<root>.common`:
- `ObjectWrapper`, `ListWrapper` (bounded lists) and `PaginationWrapper` + `PageInfo`
  (paginated lists: `response` + an explicit `pageInfo` with number, size, totalElements,
  totalPages, hasNext and hasPrevious, all with `@Schema`); never raw arrays;
- `PageFilter` + `PageRequests` (size cap 100);
- `CursorFilter` / `CursorPage` only if the profile lists cursor lists;
- the dropdown standard in `{{dropdownPackage}}` (references/dropdown.md): `DropdownItem`
  record, `DropdownFilter`, `DropdownStatus` and the `@DropdownCandidate` annotation (the user's
  contract, with its full LLM-contract Javadoc updated to the house rules);
- `CriteriaPages` (count skipped on a partial page);
- `OpenApiConfig` (springdoc; disabled in prod).

## 3. Persistence base
- `AuditableEntity` (`@MappedSuperclass`: application-assigned time-ordered id generator per
  `idStrategy`, `createdAt` / `updatedAt` / `createdBy` / `updatedBy` via Spring Data auditing,
  `@Version Long version`; audit fields without public setters);
- `JpaAuditingConfig`;
- the Liquibase master and a baseline changeset;
- *optional:* the generic audit trigger function plus `audit_log` and the per-transaction
  `set_config('app.user_id', …, true)` hook (DATA-08).

## 4. Configuration (config-baseline.md CFG-01…06, CFG-09)
`application.yml` + `application-{dev,prod}.yml` + `.env.example`:
- every environment-dependent value from env vars, no secret defaults, **no default active
  profile** (SEC-03);
- the Hikari / JPA / server / Jackson / actuator / metrics values from the baseline;
- graceful shutdown;
- virtual threads with a semaphore-bulkhead admission control (libraries-first: Resilience4j or
  `@ConcurrencyLimit`);
- a `TaskScheduler` bean;
- a readiness pool-queue indicator;
- a `RequiredSettingsCheck` that stops startup naming missing settings.

## 5. Logging and request context (CFG-07)
- `logback-spring.xml` (house pattern);
- `LogContext`;
- `RequestLogContextFilter` (`X-Request-ID`, endpoint, client IP per `app.logging.client-ip`);
- `RequestCompletionLogFilter` (`app.logging.request-completion.enabled`, slow threshold);
- a `TaskDecorator` that copies the MDC to async executors.

## 6. Security skeleton
- `SecurityConfig`: stateless, CORS from configuration, `DispatcherType.ERROR` permitted, method
  security with the role hierarchy, security errors in Problem Details;
- JWT per `tokenRevocation`;
- an auth rate limiter on a shared backend when `instances: multiple`.

## 7. Tests and architecture
- The integration-test base (Testcontainers image matching production, **not alpine**; one
  shared context; `loginAs(Role)` and user helpers);
- ArchUnit rule classes generated from the profile (architecture.md), after the dependency is
  approved.

## 8. Verify
Build and test, then run house-review on the whole project. Expected result: no findings outside
pending business code. Report what was created, what was skipped as present, and the decisions
taken.
