# House error contract (ERR-xx)

Reference implementation: the reference application's `common/exception/` (`ErrorCode`,
`ErrorCodeHttpStatusMapper`, `AppException`, `RequestError`, `JsonPointers`, `MvcRequestErrors`,
`TransientDatabaseFailure`, `GlobalExceptionHandler`, `ConstraintViolationCodes` with each module's
`ConstraintErrorCodes`), `common/web/ProblemSchemaOpenApiCustomizer`, `config/MessageSourceConfig`
and `messages/message_en.properties`.

This file is the reference shape that REST-05, REST-06 and REL-10 check against. A project
that hasn't adopted it yet gets one finding per missing piece, not one per endpoint.

---

## The pieces

| Piece | Shape |
|---|---|
| `ErrorCode` | One protocol-agnostic enum, grouped by module (`AUTH_…`, `ORDER_…`). Each constant is one well-defined failure mode. Constants in a module's `api/` package carry Javadoc. |
| `ErrorCodeHttpStatusMapper` | `final` utility class with a private constructor, holding a static `EnumMap<ErrorCode, HttpStatus>`. It is the **only** place an ErrorCode meets an HttpStatus. `resolve()` throws `IllegalStateException` for an unmapped code, so a new code can't reach production silently. Other protocols (gRPC) would get their own mapper. |
| `AppException` | One `RuntimeException` carrying `ErrorCode`, `Object[] args`, optional `errors` (`RequestError`s, ERR-07) and response `extensions`. Private constructor, with static factories: `of(code, args…)`, `withCause(code, cause, args…)`, `withErrors(code, errors)`, `supply(code, args…)` (for `orElseThrow`). Also `withExtension(name, value)`, which rejects the reserved names `type`, `title`, `status`, `detail`, `instance`, `errorCode` and `errors`. |
| `RequestError` | A record `(pointer, parameter, detail)`, `@JsonInclude(NON_NULL)`, with the factories `atPointer(pointer, detail)` and `atParameter(name, detail)`: exactly one of the two locations is set. `JsonPointers` builds the pointer from a property path (`items[0].name`) or Jackson's path; `MvcRequestErrors` gives the entries of the exceptions Spring MVC answers itself. The OpenAPI document describes `errorCode` and `errors` on the `ProblemDetail` schema (a customizer: springdoc can't see extension members). |
| Messages | `messages/message_<locale>.properties`, keyed by `ErrorCode.name()`, with `{0}` arguments, written for `MessageFormat` (ERR-06). `ReloadableResourceBundleMessageSource` set to `UTF-8` **and** `setAlwaysUseMessageFormat(true)`. Without that setting a message with no arguments skips MessageFormat, and `''` / `{0}` reach the client raw (MESSAGE-PLACEHOLDERS-LEAK). The bean validator uses the same MessageSource. Locale comes from `AcceptHeaderLocaleResolver` **with its supported locales set**, and the MessageSource has **`setDefaultLocale(ENGLISH)`**: otherwise any `Accept-Language` without its own file (`de`, `pa`) throws `NoSuchMessageException` inside the error handler and breaks every error response (verified 2026-11-13). |
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
- `errors`: the request values that failed, RFC 9457's own list (ERR-07): on `VALIDATION_FAILED`, and on
  `REQUEST_PARAMETER_INVALID`, `REQUEST_PARAMETER_MISSING` and `REQUEST_UNREADABLE` when the value is known.
- extension members from `AppException.withExtension` (client errors only).
- the request id: in the response header (`X-Request-ID`) and the log context.

**Severity:** Medium for a missing member; High for a 5xx body that contains anything other than
the generic text.

## ERR-02: Mapping table

| Exception | Status | errorCode | Log |
|---|---|---|---|
| `AppException`, 4xx code | from the mapper | the code | DEBUG (WARN for 401/403) |
| `AppException`, 5xx code | from the mapper | the code | ERROR with stack; body generic |
| `MethodArgumentNotValidException`, constraint failures | 400 | `VALIDATION_FAILED` + `errors` (a body's fields by pointer, a query object's by parameter) | DEBUG |
| `MethodArgumentNotValidException`, **binding failures** (`FieldError.isBindingFailure()`: an unknown enum, a malformed date or UUID in a query object) | 400 | `REQUEST_PARAMETER_INVALID` + `errors: [{"parameter": "<field>", "detail": "invalid value"}]`, **never the default message** (it names Java classes) | DEBUG |
| `HandlerMethodValidationException` (constraints on `@RequestParam` / `@PathVariable`, and the `@Valid` objects of the same method) | 400 | `VALIDATION_FAILED` + `errors` naming the parameter (`ParameterErrors`: their fields as above) | DEBUG |
| `HttpMessageNotReadableException` | 400 | `REQUEST_UNREADABLE`; + `errors` with the pointer Jackson's path gives (`"unknown field"` / `"invalid value"`), none for broken syntax | via `handleExceptionInternal` |
| `MissingServletRequestParameterException` | 400 | `REQUEST_PARAMETER_MISSING` + `errors` naming the parameter | |
| `TypeMismatchException` | 400 | `REQUEST_PARAMETER_INVALID` + `errors` naming the parameter, `"invalid value"` | |
| `HttpRequestMethodNotSupportedException` | 405 | `REQUEST_METHOD_NOT_ALLOWED` (keeps `Allow`) | |
| `HttpMediaTypeNotSupportedException` | 415 | `REQUEST_MEDIA_TYPE_UNSUPPORTED` | |
| `AccessDeniedException` | 403 | `ACCESS_DENIED` | WARN |
| `AuthenticationException` | 401 | `AUTHENTICATION_REQUIRED` | WARN |
| `DataIntegrityViolationException`, unique violation | 409 | `DUPLICATE_RECORD`, or **the domain code** when the constraint name maps to one | DEBUG |
| `DataIntegrityViolationException`, foreign key (23503), told apart by the statement that tripped it (`JDBCException.getSQL()`: the server's error names the child table on both sides) | a **delete**: 409; an **insert or update**: 404; unmapped: 500 | the delete: the module's `byConstraintName` code (`<PARENT>_IN_USE`); the insert or update: its `byForeignKeyOnWrite` code (`<PARENT>_GONE`, the parent deleted after the service read it, DATA-33) | DEBUG (500: ERROR) |
| `DataIntegrityViolationException`, other | 500 | `DATA_INTEGRITY_ERROR` | ERROR |
| `ObjectOptimisticLockingFailureException` (or a known version constraint) | 409 | `OPTIMISTIC_LOCK_CONFLICT` | DEBUG |
| Transient database failure (ERR-03) | 503 + `Retry-After` | per kind | WARN (ERROR + stack for query timeout) |
| A downstream service didn't answer (SDK client error or 5xx, AI provider, circuit breaker open) | 503 + jittered `Retry-After` | `<DEPENDENCY>_UNAVAILABLE` | WARN without stack, and counted on a metric |
| Any other exception `ResponseEntityExceptionHandler` handles (406, a missing header, an async timeout, `ResponseStatusException`, ...) | its status | a specific code where one exists, else by status class: 404 `RESOURCE_NOT_FOUND`, other 4xx `REQUEST_INVALID`, 5xx `UNEXPECTED_ERROR` with the generic detail; `ConversionNotSupportedException` (a 500) before its parent `TypeMismatchException` | 5xx: ERROR with stack |
| Anything else, `IllegalArgumentException` included (ERR-04) | 500 | `UNEXPECTED_ERROR`, generic detail | ERROR with stack |

A downstream **4xx** answered to *our* request (for example S3's 403 for our credentials) is our
fault, so it answers 500, not 503.

**Constraint names come from the driver.** The domain code for a constraint violation is looked up
by the constraint's name, read from the driver in one place
(`PSQLException.getServerErrorMessage().getConstraint()`, reference:
`common/exception/ConstraintViolationCodes`, which merges the `ConstraintErrorCodes` bean each module
declares). Hibernate names the constraint only for SQLSTATE
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
- `IllegalStateException` only for impossible states (JAVA-20); anything a client can cause is an
  `AppException` with an ErrorCode.
- Code that rejects a user-supplied value throws a client error on purpose: an `AppException` with
  its own ErrorCode, or bean validation (400 with a specific code). The handler doesn't map
  `IllegalArgumentException` to 400: it can't tell a bad payload from bad internal data, so an IAE
  reaching it is a programmer or data error and answers 500, logged at ERROR with the stack (at
  DEBUG as a 400, such bugs went unseen). Spring's own parsing errors (type mismatch, an invalid
  API version, ...) keep their 400 codes (ERR-02).
- An error message echoes back only bounded input: names and values from the request are
  truncated and their count capped, or a large body full of unknown fields comes back whole in the
  error.
- **Names** say the failure in the module's prefix, so a client can act on the code alone. The house
  patterns: `<X>_NOT_FOUND`; `<X>_<FIELD>_TAKEN` (one unique field); `<X>_EXISTS` (a unique pair or
  tuple over every row: `TENANT_MEMBER_EXISTS`); `<X>_ALREADY_<STATUS>` (a unique rule partial on a
  live status: `ENROLLMENT_ALREADY_ACTIVE`); `<X>_IN_USE` (a parent's delete while referenced);
  `<PARENT>_GONE` (a child's write after its parent's delete, DATA-33); `<X>_OVERLAPS`;
  `<X>_STATUS_CHANGE_INVALID`; `<X>_<STATUS>` (a write refused because the record is in a final
  status: `ENROLLMENT_WITHDRAWN`). Each message explains the failure in words a user understands
  without the code (`STUDENT_GONE`: "This student no longer exists."), without placeholders when a
  constraint answers it (ERR-06).
- A new `ErrorCode` needs, in the same change:
  - a mapper entry;
  - a message in every `message_*.properties`;
  - an OpenAPI `@ApiResponse` on every endpoint that can return it (REST-20);
  - a negative test asserting the **errorCode**, not just the status (TEST-08).

## ERR-05: 409 for a conflict with state, 400 for wrong values
- **Check:** a request that is valid in itself but conflicts with the current state of its target
  or of a related resource is **409** with a domain code (`ORDER_ALREADY_SHIPPED`,
  `ACCOUNT_NOT_ACTIVE`). A request whose own values are wrong is **400** (`DATE_OUTSIDE_PERIOD`: the
  date is outside the period's fixed range, whatever the state). The test: is it refused because of a status, a flag or existing records, things that
  change over time? Then 409. Is it wrong against fixed rules or fixed attributes of the target (its
  date range, a format, a bound)? Then 400.
- **Detect:** a `…_NOT_ACTIVE`, `…_ALREADY_…`, `…_ENDED`, `…_IN_USE` code mapped to 400; a range or
  format check mapped to 409.
- **Not a finding when:** a status move is refused with 409 `<X>_STATUS_CHANGE_INVALID` although no
  state may ever move to that target (a status only the create sets: an enrollment's `ACTIVE`, a
  tenant's `PENDING`). The value is a valid status; the lifecycle refuses the move, so one code and
  one status cover every refused move, and a client handles them alike.
- **Severity:** Medium (the client can't tell "fix your input" from "reload and retry").

## ERR-06: Messages survive MessageFormat
- **Check:** every message goes through `MessageFormat` (`setAlwaysUseMessageFormat(true)`, above), so:
  - an apostrophe is written twice (`can''t`): a single one opens a quoted section, is dropped, and
    leaves the rest of the message unformatted ("cant", a literal `{0}`);
  - a number that names something rather than counts it (a year, an id, a code, a version) is
    `{0,number,#}`, or the caller passes it as a String: a plain `{0}` formats a `Number` by locale,
    so 2026 reads "2,026". A quantity may keep the grouping ("48,213 bytes");
  - a code answered from a constraint name (`*_TAKEN`, `*_IN_USE`, `*_GONE`, an exclusion's `*_OVERLAPS`) has a
    message without placeholders: the handler has no argument to give it, and `{0}` would reach the
    client as is.

  Reference: the comment heading `message_en.properties`; `ErrorCodeContractTest.everyApostropheIsDoubled`,
  `ConstraintNamesIntegrationTest.constraintCodesTakeNoArguments`, `MessageSourceConfigTest.messageFormatRules`.
- **Detect:** `[^']'[^']` in a messages file; `{N}` fed a year, an id or an `int` code; `{` in the
  message of a code a `ConstraintErrorCodes` bean maps; a test asserting only the `errorCode` of a
  refusal whose message carries a number (assert `$.detail` once per such message).
- **Severity:** Medium (wrong client-facing text; a lost apostrophe Low).

## ERR-07: Where a value failed: RFC 9457's `errors`
- **Check:** a 400 that names values carries them as `errors`, the list RFC 9457 shows in its own
  example, one entry per value, each `{location, "detail": "<message>"}` with exactly one location:
  - a **request-body** value: `"pointer"`, a JSON Pointer (RFC 6901) in its URI fragment form:
    `"#/email"`, nested `"#/address/city"`, a list element `"#/items/0/name"`, a map key escaped
    (`~` → `~0`, `/` → `~1`); an element of a **set** has no position, so it points to the set (`"#/tagIds"`);
  - a **query or path parameter** (a bound filter's field, a `@RequestParam`, a type mismatch):
    `"parameter": "size"`;
  - a **rule across fields** (a range in order, a filter that needs an order): on the field a form shows
    the error under, the pair's **end** (`#/endsOn`, `"parameter": "plannedTo"`) or the field that needs the
    other (`dueOnOrBefore`), never on a synthetic name no client sends (`#/dateRangeOrdered`). The house
    shape is a hidden boolean getter with `@CrossField(reportedOn = "endsOn", message = …)` (common.validation;
    a limit on a derived number, `@ConfiguredMax(value = …, reportedOn = "endsOn")`): the handler names the
    sibling its constraint names, `items[0].endsOn` in a list element. A class-level constraint reports on
    `addPropertyNode("endsOn")`; one that names no field points to the body as a whole, `"pointer": "#"`.
    ArchUnit (`ConventionArchTest`) refuses a plain `@AssertTrue` and a getter's `@ConfiguredMax` without
    `reportedOn`.

  `detail` is the constraint's message (from the MessageSource) or a fixed phrase (`invalid value`,
  `unknown field`), never a converter's or Jackson's message. Code that refuses a value itself builds
  the entry (`AppException.withErrors(code, List.of(RequestError.atParameter("after", RequestError.INVALID_VALUE)))`,
  a nested document's violations prefixed with its field: golden `MetadataService`). Reference test:
  `common/exception/RequestErrorsTest` (each shape, nested and indexed pointers, escaping).
- **Detect:** a `failed` member or `"field: message"` strings; a top-level `parameter` extension naming
  the one bad parameter; a body field reported as a `parameter` or a query parameter as a `pointer`
  (the handler decides by `@RequestBody` on the method parameter); a dotted path (`items[0].name`) or a
  bare field name in `pointer`; a test asserting the whole message string instead of the location and the
  `detail` (`$.errors[0].pointer`, `$.errors[0].detail`, or `$.errors[?(@.pointer == '#/x')].detail`).
- **Severity:** Medium (a client can't place the message next to its field).

## Detect
- An `ErrorCode` constant missing from the mapper or the messages file. Grep both against the
  enum; a test can iterate `ErrorCode.values()`.
- Messages files not read as UTF-8, or no `setAlwaysUseMessageFormat(true)`.
- `ProblemDetail` / `ResponseEntity` error bodies built outside the handler. `e.getMessage()`
  copied into `detail` for a 5xx or a database exception.
- Exception classes that differ only by name.
- No transient-failure classification: pool exhaustion and timeouts answer 500.
- `@ExceptionHandler(IllegalArgumentException.class)` answering a 4xx; `throw new
  IllegalArgumentException` on a value that came from the request (ERR-04; Medium).
