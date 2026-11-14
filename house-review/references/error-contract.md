# House error contract (ERR-xx)

Source: the user's DynamicForm implementation:
- `shared/exception/GlobalExceptionHandler`
- `ErrorCode`
- `ErrorCodeHttpStatusMapper`
- `custom/AppException`
- `TransientDatabaseFailure`
- `config/MessageSourceConfig`
- `messages/message_en.properties`

This file is the reference shape that REST-05, REST-06 and DATA-18 check against. A project
that hasn't adopted it yet gets one finding per missing piece, not one per endpoint.

---

## The pieces

| Piece | Shape |
|---|---|
| `ErrorCode` | One protocol-agnostic enum, grouped by module (`AUTH_…`, `FORM_…`). Each constant is one well-defined failure mode. Constants in a module's `api/` package carry Javadoc. |
| `ErrorCodeHttpStatusMapper` | `final` utility class with a private constructor, holding a static `EnumMap<ErrorCode, HttpStatus>`. It is the **only** place an ErrorCode meets an HttpStatus. `resolve()` throws `IllegalStateException` for an unmapped code, so a new code can't reach production silently. Other protocols (gRPC) would get their own mapper. |
| `AppException` | One `RuntimeException` carrying `ErrorCode`, `Object[] args`, optional `failedValidations` and response `extensions`. Private constructor, with static factories: `of(code, args…)`, `withCause(code, cause, args…)`, `withFailedValidations(code, failed)`, `supply(code, args…)` (for `orElseThrow`). Also `withExtension(name, value)`, which rejects the reserved names `type`, `title`, `status`, `detail`, `instance`, `errorCode` and `failed`. |
| Messages | `messages/message_<locale>.properties`, keyed by `ErrorCode.name()`, with `{0}` arguments. `ReloadableResourceBundleMessageSource` set to `UTF-8` **and** `setAlwaysUseMessageFormat(true)`. Without that setting a message with no arguments skips MessageFormat, and `''` / `{0}` reach the client raw (MESSAGE-PLACEHOLDERS-LEAK). The bean validator uses the same MessageSource. Locale comes from `AcceptHeaderLocaleResolver` **with its supported locales set**, and the MessageSource has **`setDefaultLocale(ENGLISH)`**: otherwise any `Accept-Language` without its own file (`de`, `pa`) throws `NoSuchMessageException` inside the error handler and breaks every error response (verified 2026-11-13). |
| `GlobalExceptionHandler` | `@RestControllerAdvice extends ResponseEntityExceptionHandler`, so Spring MVC's own errors pass through it too. |

## ERR-01: Every error response has the same members
- `Content-Type: application/problem+json`
- `type`: `urn:<app>:error:<ERROR_CODE>`, a URN and not an https URL. It is a stable category id
  that nobody will try to fetch. `<app>` comes from `spring.application.name`, never a literal.
- `title`, `status`
- `detail`: a message from the MessageSource for client errors; a fixed generic sentence for
  every 5xx.
- `instance`: the request path, with Spring's `uri=` debug prefix stripped.
- `errorCode`: the same code as in `type`.
- `failed`: the field-level validation failures (validation errors only).
- extension members from `AppException.withExtension` (client errors only).
- the request id: in the response header (`X-Request-ID`) and the log context.

**Severity:** Medium for a missing member; High for a 5xx body that contains anything other than
the generic text.

## ERR-02: Mapping table

| Exception | Status | errorCode | Log |
|---|---|---|---|
| `AppException`, 4xx code | from the mapper | the code | DEBUG (WARN for 401/403) |
| `AppException`, 5xx code | from the mapper | the code | ERROR with stack; body generic |
| `MethodArgumentNotValidException`, constraint failures | 400 | `VALIDATION_FAILED` + `failed` | DEBUG |
| `MethodArgumentNotValidException`, **binding failures** (`FieldError.isBindingFailure()`: an unknown enum, a malformed date or UUID in a query object) | 400 | `REQUEST_PARAMETER_INVALID` + `failed: ["<field>: invalid value"]`, **never the default message** (it names Java classes) | DEBUG |
| `HandlerMethodValidationException` (constraints on `@RequestParam` / `@PathVariable`) | 400 | `VALIDATION_FAILED` naming the parameter | DEBUG |
| `HttpMessageNotReadableException` | 400 | `REQUEST_UNREADABLE` | via `handleExceptionInternal` |
| `MissingServletRequestParameterException` | 400 | `REQUEST_PARAMETER_MISSING` | |
| `TypeMismatchException` | 400 | `REQUEST_PARAMETER_INVALID` | |
| `HttpRequestMethodNotSupportedException` | 405 | `REQUEST_METHOD_NOT_ALLOWED` (keeps `Allow`) | |
| `HttpMediaTypeNotSupportedException` | 415 | `REQUEST_MEDIA_TYPE_UNSUPPORTED` | |
| `AccessDeniedException` | 403 | `ACCESS_DENIED` | WARN |
| `AuthenticationException` | 401 | `AUTHENTICATION_REQUIRED` | WARN |
| `DataIntegrityViolationException`, unique violation | 409 | `DUPLICATE_RECORD`, or **the domain code** when the constraint name maps to one | DEBUG |
| `DataIntegrityViolationException`, other | 500 | `DATA_INTEGRITY_ERROR` | ERROR |
| `ObjectOptimisticLockingFailureException` (or a known version constraint) | 409 | `OPTIMISTIC_LOCK_CONFLICT` | DEBUG |
| `IllegalArgumentException` | 400 | `ILLEGAL_ARGUMENT` | DEBUG |
| Transient database failure (ERR-03) | 503 + `Retry-After` | per kind | WARN (ERROR + stack for query timeout) |
| A downstream service didn't answer (SDK client error or 5xx, AI provider, circuit breaker open) | 503 + jittered `Retry-After` | `<DEPENDENCY>_UNAVAILABLE` | WARN without stack, and counted on a metric |
| Any other exception `ResponseEntityExceptionHandler` handles (406, a missing header, an async timeout, `ResponseStatusException`, ...) | its status | a specific code where one exists, else by status class: 404 `RESOURCE_NOT_FOUND`, other 4xx `REQUEST_INVALID`, 5xx `UNEXPECTED_ERROR` with the generic detail; `ConversionNotSupportedException` (a 500) before its parent `TypeMismatchException` | 5xx: ERROR with stack |
| Anything else | 500 | `UNEXPECTED_ERROR`, generic detail | ERROR with stack |

A downstream **4xx** answered to *our* request (for example S3's 403 for our credentials) is our
fault, so it answers 500, not 503.

**Constraint names come from the driver.** The domain code for a constraint violation is looked up
by the constraint's name, read from the driver in one place
(`PSQLException.getServerErrorMessage().getConstraint()`, reference:
`common/exception/ConstraintViolationCodes`). Hibernate names the constraint only for SQLSTATE
23505, 23514, 23503 and 23502; an exclusion constraint (23P01) gets no name from it. A custom
Hibernate dialect or anything on `org.hibernate.internal` to recover the name is a finding (Medium:
internal API, switched on globally for one constraint), and so is parsing the message text (it is
localised).

## ERR-03: Transient database failures
A small enum classifies the exception by walking the **cause chain**, because a timeout can
arrive wrapped as a bare `PersistenceException`:

| Kind | Matches | errorCode | Retry-After (jittered) |
|---|---|---|---|
| CONTENTION | `LockTimeoutException`, `LockAcquisitionException`, `PessimisticLockingFailureException` (SQLSTATE `55P03`, `40P01`) | `DATABASE_CONTENTION` | 1–3 s |
| QUERY_TIMEOUT | `QueryTimeoutException` (both packages; SQLSTATE `57014`) | `QUERY_TIMEOUT` | 3–8 s |
| UNAVAILABLE | `SQLTransientConnectionException` (pool timeout), `JDBCConnectionException`, `DataAccessResourceFailureException`, `TransientDataAccessResourceException`, `CannotCreateTransactionException`, any SQLSTATE class `08`, a `TransactionException` caused by a connection loss | `DATABASE_UNAVAILABLE` | 4–8 s |

The catch-all handler checks this classification before answering 500. `Retry-After` is
jittered, picked fresh within the kind's range on every response, so retries after one outage
don't arrive as one wave. The ranges are configuration (`app.retry-after.*`), not code.

## ERR-04: Rules for code that throws
- Throw `AppException.of(ErrorCode.X, args)`. Never `new RuntimeException("…")`. Never a new
  exception class per error case: a new class only when a caller must catch it specifically.
- Lookups: `repository.findById(id).orElseThrow(AppException.supply(X_NOT_FOUND, id))`.
- `IllegalStateException` is for **impossible states** (bugs): it answers 500 and is never caught
  as a domain signal. Anything a client can cause is an `AppException` with an ErrorCode (JAVA-09,
  JAVA-20).
- A new `ErrorCode` needs, in the same change:
  - a mapper entry;
  - a message in every `message_*.properties`;
  - an OpenAPI `@ApiResponse` on every endpoint that can return it (REST-20);
  - a negative test asserting the **errorCode**, not just the status (TEST-08).

## ERR-05: 409 for a conflict with state, 400 for wrong values
- **Check:** a request that is valid in itself but conflicts with the current state of its target
  or of a related resource is **409** with a domain code (`SCHOOL_SESSION_ENDED`,
  `RECEIPT_ALREADY_CANCELLED`, `STUDENT_NOT_ACTIVE`). A request whose own values are wrong is
  **400** (`FEE_MONTH_OUTSIDE_SESSION`: the month is outside the session's range, whatever the
  state). The test: is it refused because of a status, a flag or existing records, things that
  change over time? Then 409. Is it wrong against fixed rules or fixed attributes of the target (its
  date range, a format, a bound)? Then 400.
- **Detect:** a `…_NOT_ACTIVE`, `…_ALREADY_…`, `…_ENDED`, `…_IN_USE` code mapped to 400; a range or
  format check mapped to 409.
- **Severity:** Medium (the client can't tell "fix your input" from "reload and retry").

## Detect
- An `ErrorCode` constant missing from the mapper or the messages file. Grep both against the
  enum; a test can iterate `ErrorCode.values()`.
- Messages files not read as UTF-8, or no `setAlwaysUseMessageFormat(true)`.
- `ProblemDetail` / `ResponseEntity` error bodies built outside the handler. `e.getMessage()`
  copied into `detail` for a 5xx or a database exception.
- Exception classes that differ only by name.
- No transient-failure classification: pool exhaustion and timeouts answer 500.
