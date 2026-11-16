# REST API rules (REST-xx)

Each rule gives what to **Check**, how to **Detect** it, its default **Severity**, and when it is **Not
a finding**. Profile keys are written `{{key}}`.

---

### REST-01: Resource naming
- **Check:** paths are nouns, plural for collections, kebab-case; JSON fields camelCase; one
  resource name per concept across the API (not `/clients` in one place and `/customers` in
  another for the same people).
- **Detect:** list every `@*Mapping` path. Flag:
  - verbs (`/getX`, `/runs/all`, `/approve-bulk`);
  - singular collections (`/invoice` for the invoices);
  - camelCase or snake_case path segments;
  - two names for one resource, or one name for two resources.
- **Severity:** Medium.
- **Not a finding when:** auth endpoints in RPC style (`/auth/login`, `/auth/otp/send`), which
  are conventional.

### REST-02: Hierarchy
- **Check:** sub-resources nest under their parent (`/orders/{id}/shipments`), not the other way
  round (`/shipments/orders/{orderId}`). At most two levels deep.
- **Also:** the path names the target; the body never chooses it. A request DTO carries no id of
  the resource (or its parent) that the path names: a controller that overwrites it hides the
  mismatch, and one that forgets lets a body aim the write at another record.
- **Severity:** Low (Medium when a body id is present; High when it is used, REST-16).

### REST-03: Actions
- **Check:**
  - a **state change** on an existing resource is `PATCH /x/{id}/<action>` (activate, approve,
    reject, dismiss, enable);
  - an action that **creates something or starts processing** is `POST /x/{id}/<action>`
    (copy, regenerate, retry, replay, reconcile).
- **Detect:** for `POST` actions, ask whether they only change the status. For two endpoints
  calling one method with `true` / `false`, it's a flag argument (SMELL-21): use one PATCH with
  the target state.
- **Severity:** Medium.

### REST-04: Method semantics
- **Check:**
  - GET is safe (no writes, apart from the deliberate lazy creation described below);
  - PUT replaces the whole resource;
  - PATCH changes only the fields sent;
  - DELETE removes.
- **Detect:**
  - a `PATCH` whose body DTO has required (`@NotNull` / `@NotBlank`) fields is really a PUT;
  - a GET that INSERTs, e.g. a settings row created on read.
- **Severity:** Medium.
- **Not a finding when:** a REST-03 state change or single-field sub-resource
  (`PATCH /x/{id}/role {role}`, `PATCH /x/{id}/enabled {enabled}`): its one field is required by
  nature, and the body *is* the change. The PUT smell is a PATCH on the resource itself
  (`PATCH /x/{id}`) whose body requires fields the client didn't mean to change.

### REST-05: Status codes
- **Check:**
  - 200: read or update with a body;
  - **201**: created;
  - **202**: accepted asynchronous work, with a status URL in the body;
  - **204**: any success with no body, not only DELETE;
  - 400: the request's own values are wrong (validation, a value outside an allowed range);
  - 401: missing or invalid token;
  - 403: role missing;
  - **404**: not found, *or not yours* (REST-16);
  - 409: a valid request that conflicts with current state, or a duplicate (ERR-05);
  - **429 + `Retry-After`**: rate limited;
  - 500: unexpected;
  - **503 (+ `Retry-After` when transient)**: a downstream service is down, or no database
    connection is available.
- **Detect:** controller methods that create but return the wrapper directly (200);
  `void` methods (200 with an empty body); triggers of asynchronous jobs answering 200; a
  failed downstream call answered 200 with a "FAILED" record.
- **Also detect:** infrastructure exceptions with no 503 mapping (database, SDK,
  `ResourceAccessException`, `CallNotPermittedException`; error-contract.md ERR-02/03); a security
  filter answering 401 for an infrastructure failure (DATA-17).
- **Severity:** Medium (High for a wrong 2xx on a failure).
- **Not a finding when:** the profile's exception handler maps it centrally (check the handler
  before reporting).

### REST-06: Error format
- **Check:**
  - every error is Problem Details (`application/problem+json`) with a stable,
    machine-readable `errorCode`;
  - the request id is included;
  - the values that failed are RFC 9457's `errors` list: a JSON Pointer for a body value, `parameter`
    for a query or path parameter (error-contract.md ERR-07), never a house-made `failed` list;
  - the protocol-agnostic `ErrorCode` enum is mapped to HTTP in one place;
  - messages come from a `MessageSource` (i18n);
  - **never** class names, SQL fragments, constraint names or stack traces.
- **Detect:** the global handler and every `ResponseEntity` / `ProblemDetail` built by hand;
  exception classes that differ only by name (copy-paste hierarchy); `getMessage()` of a
  database or framework exception copied into `detail`; an error raised before the handler (the
  security chain, a filter, the firewall's refused path) answered in another shape (error-contract.md,
  *Errors raised before the handler*).
- **Severity:** High if internals leak, otherwise Medium.
- **Reference implementation:** `error-contract.md`. Check
  every piece there: ERR-01 members, the ERR-02 mapping table, ERR-03 transient failures, ERR-04
  throwing rules.

### REST-07: Paths and versioning
Paths on the controller class, versions through Spring Framework 7's API versioning, carried in
the path.
- **Check:**
  - every `@RestController` has **one class-level `@RequestMapping`**, `{{urlPrefix}}/<resource-plural>`
    (`/api/{version}/todo-lists`): one resource per controller (SRP). Handler methods carry only
    relative paths;
  - **every handler method declares `version`**, as a baseline (`"v1+"`): a new version adds methods
    only for the endpoints that change, in the same controller;
  - versions are Spring API versioning (`spring.mvc.apiversion.*`: path segment, `required`, the
    `supported` list in configuration) with a strict parser (`v<major>` only: Spring's default parser
    drops non-digits, so `/api/x1/` would serve v1);
  - a missing, malformed or unsupported version answers 400 `API_VERSION_UNSUPPORTED` (documented
    once by an OpenAPI customizer, not per operation);
  - the resolved version (`HandlerMapping.API_VERSION_ATTRIBUTE`) is in the request-completion log
    (`apiVersion`) and a tag on `http.server.requests`;
  - path patterns elsewhere use a version wildcard, so a new version needs no change there and
    inherits their protection: security public paths `/api/*/auth/**`; admission groups name only
    the endpoints that hash or verify a password (`/api/*/auth/login`, `/api/*/auth/signup`; golden
    `app.admission.groups`), never a whole prefix that would also throttle token refresh;
  - deprecating a version: an `ApiVersionDeprecationHandler` bean (RFC 9745 `Deprecation` /
    `Sunset` headers), not hand-written headers.
- **Detect:** a full path on a method; a method without `version`; `/api/v1/` written into a security
  or admission pattern; a second controller for the same resource per version.
- **Enforce:** ArchUnit (reference: one class-level path per controller; every handler declares its
  version).
- **Severity:** Medium.

### REST-08: Lists
- **Check:**
  - **pagination follows the list's consumer**, as the profile records it (`{{cursorLists}}` /
    `{{offsetLists}}`, the developer's statement): **cursor** (`after`, `CursorWrapper` with `pageInfo
    {nextCursor, hasMore}`, no count) for pages a mobile or end-user app reads, dropdowns, and an admin
    screen that needs no total and no page numbers and whose filters are simple and non-nullable;
    **offset** (`PaginationWrapper`, the count only on a full page) for an admin screen that needs page
    numbers or a total, or has complex filters. The review checks that the kind follows this rule for the
    recorded consumer, never a guess at the table's size, which only the developer knows;
  - **sizes from configuration** (`app.limits.cursor-page` / `offset-page`); over the maximum is a
    400, never a silent clamp;
  - **filters:** validated fail-fast (400 `REQUEST_PARAMETER_INVALID` on an unknown value, naming
    the parameter and nothing else), typed as enums, not free strings;
  - **free-text search** is the `query` parameter in every list and dropdown, over the record's own
    name-like text (a free-form note is no search target unless the owner asked) (not a finding when the
    entity has no text column of its own: an enrollment between a student and a course, the
    reference's `PlanFilter`; its parents' text is searched in their own lists);
  - **sorting:** a sort enum (allow-list), a stable tie-breaker (id); a cursor carries its sort, and
    one from another sort is refused;
  - **a tampered cursor is a 400** `REQUEST_PARAMETER_INVALID` naming `after`, never a 500: `Cursor.read`
    takes the sort and its key count (a key missing or extra is refused), and each key reader takes a key
    only in the form `Cursor.write` gives it and only inside what the column stores (text without U+0000
    or a lone surrogate; a canonical id; a day or moment in years 1-9999, PostgreSQL's range being
    narrower than `java.time`'s, a moment in microseconds; an `integer`'s range; an enum's exact names).
    A new key type gets a reader in that shape;
  - **the cursor's length limit** (`app.limits.text.cursor`) holds the cursor the server writes after
    the longest value of every text key a cursor carries (a text sort key, a dropdown's label):
    Base64url JSON, a control character escaped in six bytes, so `(6 × @MaxLength + 100) × 4/3`.
    Shorter, the next batch after a long value is a 400 on the server's own cursor (reference:
    `limits.yml`, `LimitsIntegrationTest.cursorLimitHoldsTheLongestTextKeys`);
  - `POST /x/search` with a body only when a query string can't express the filter. The HTTP
    `QUERY` method (RFC 10008, June 2026) replaces it once the project's Spring has it in a GA
    release (merged for Spring Framework 7.1) and the proxies and OpenAPI tooling pass it.
- **Detect:** a `PageRequest` from raw client values; an unbounded `findAll()` behind an endpoint;
  a sort as a free String; a list whose kind doesn't follow the rule for its recorded consumer, or a list
  with no consumer recorded; a count on a cursor list; a cursor
  limit below `(6 × max + 100) × 4/3` for a text sort key or a dropdown label; a cursor key parsed by
  `java.time` with no range check, by `UUID.fromString`/`Integer.valueOf` without comparing with what was
  written, or as an enum through `valueOf` outside `Cursor` (a 500 on a bad name); `Cursor.read` without
  the key count.
- **Severity:** High for an unbounded `findAll()` or a list without a page size, otherwise Medium.

### REST-09: Validation layers
- **Check:**
  1. bean validation at the edge: `@Valid` on every `@RequestBody`; **a length limit on every
     String** (`@MaxLength("<key>")`, the number in `app.limits.text`: configurable, one source of
     truth; a literal `@Size(max = N)` is a finding where the house limits exist), **element
     Strings included**: `Set<@NotBlank @MaxLength("media.upload-key") String> attachmentKeys`, since
     each key reaches a query and the error text (every String with the house `@MaxLength` is also
     refused U+0000, which PostgreSQL `text` can't store: a 500 at the insert otherwise, and a lone
     surrogate, which UTF-8 can't carry: stored as `?` behind a 201 otherwise; an element
     limit is published as the items' `maxLength` by `ElementLimitsModelConverter`, and
     `LimitsIntegrationTest.everyLimitKeyIsConfigured` checks element keys too); **every date** that
     reaches a column or a query, filters included, has a configured floor and ceiling
     (`@EarliestDate`, and `@LatestDate` or `@NotAfterToday`): a `LocalDate` reaches years
     PostgreSQL's `date` can't hold, and `-300000-01-01` is otherwise a 500 at the database; ranges on numbers
     (`@ConfiguredMax` for tunable ones); `@NotEmpty` + max size on collections (`@ConfiguredMaxSize`;
     an upload-key or file-id list takes `upload-policy.max-files`, the most files any policy may
     allow);
     a boolean is a `Boolean` with `@NotNull` (an absent field must not become a silent `false`);
     a bound without a house annotation (a security floor such as `@Size(min = 8)`) carries its own
     `message` ("must be at least 8 characters"): the default prints the other bound, "size must be
     between 8 and 2147483647";
     constraints on `@RequestParam` / `@PathVariable`;
     **A `@Pattern` bounds the length only when every part of it is bounded:** closed character
     classes, every quantifier `{m,n}` (or `?`), and nothing unbounded anywhere, at the ends least of
     all (`\s*`, `.*`, `+`); bean validation matches the whole value, so no `^…$` is needed. Golden:
     `TagColor` (`(#[0-9A-Fa-f]{6})?`). A pattern that tolerates surrounding spaces
     (`^\s*[A-Z]{2,32}\s*$`, to meet "ignores surrounding spaces") accepts megabytes of spaces: drop
     the `\s*` and refuse them (400), or keep it and add `@MaxLength`;
  2. security validators (REST-10);
  3. domain validation in the service (existence, state, ownership).

  Data rebuilt from stored rows or other sources (a copy, a seed, an import, a restore, a model's
  output) passes the same validation as a request, through the same validators: writers that
  skipped the gate stored values no request could, and later reads failed on them.

  `@Valid` and `@PreAuthorize` sit on the controller while REST is the only entry point. When a
  second one arrives (GraphQL, a CLI, a job calling the same use case), they move to the service
  (`@Validated` class + `@Valid` parameters, `@PreAuthorize` on service methods), so every entry
  point is covered once and none is forgotten.
- **Detect:** request DTO fields without constraints; a `LocalDate` field or filter without
  `@EarliestDate`; `List<String>` / `Set<String>` whose elements
  carry no `@MaxLength`; a `@Pattern` with `*`, `+` or `\s*` and no `@MaxLength`; a body parameter missing `@Valid`; a
  service re-checking by hand what a constraint could declare (duplicated bounds); a second entry
  point calling a service whose checks live only on the REST controller (High: unvalidated or
  unauthorized input).
- **Severity:** **High for a String with no length limit, unless the author justifies it** (ask;
  a justified exception is an accepted deviation); High for other unbounded input; otherwise
  Medium.

### REST-10: Hostile text
- **Check:** every free-text input has `{{NotXss}}` or a documented output-escaping policy.
  `{{NoEmoji}}` only on the fields listed in `{{noEmojiFields}}` (codes, identifiers), never on
  content: users write in every script, emoji included.
- **Detect:** String fields in request DTOs; check validators that only accept `String`
  applied to `JsonNode`, `Map` or collections (they silently do nothing).
- **Severity:** High.

### REST-11: Response wrapping
- **Check:** every response is wrapped. Never a raw array or a raw entity:
  - `{{ObjectWrapper}}` for one object;
  - `{{PaginationWrapper}}` for an **offset-paged** list: `response` + an explicit, documented
    `pageInfo` (number, size, totalElements, totalPages, hasNext, hasPrevious);
  - `CursorWrapper` for a **cursor-paged** list: `response` + `pageInfo {nextCursor, hasMore}`;
  - `MapWrapper` for values keyed by name;
  - `{{ListWrapper}}` only for a list bounded by nature.
- **Detect:** a paged list returned as `{{ListWrapper}}`, or page details put in `extraDetails`
  (they are invisible in the OpenAPI schema). A hand-built `PageInfo` that doesn't come from the
  same `Page` as the items is a finding (TEST-12, FLAG-AND-FIELD-DIVERGE).
- **Cursor lists** (`{{cursorLists}}`): the same principle; the cursor is opaque to clients and
  carries only a position (the caller's scoping still applies to every batch).
- **Severity:** Medium (High if an entity is returned).

### REST-12: Bulk operations
- **Check:** take a `Set` of ids (no duplicates) with `@NotEmpty`, a maximum `@Size` and
  element constraints; load in one query; all-or-nothing behaviour documented.
- **Detect:** a `List` of ids: a lookup that expects one row per id fails on a duplicate (a
  `findFirst().orElseThrow()` per id answered 500).
- **Severity:** Medium.

### REST-13: Dropdowns follow the house standard
One shape for every dropdown, so the frontend has one component for all of them. crud-gen
generates it (`references/dropdown.md`); the reference application's `common/dropdown/` holds the
types.
- **Check:**
  - path `GET {{urlPrefix}}/<resource-plural>/dropdown`; a parent in the path only for a true
    child resource; no named sub-paths (`by-x-id/dropdown`);
  - response `CursorWrapper<{{DropdownItem}}>` (cursor-paged, so a large set is browsed in
    batches or narrowed with `query`), items a record `{value, label}` with no other fields,
    `pageInfo {nextCursor, hasMore}`, no count;
  - parameters: `{{DropdownFilter}}` (`size`, `after`, `status`, `sortDirection`, `query`), or a
    subclass adding the entity's mandatory (`@NotNull`) and optional filters. No loose
    `@RequestParam`s; the generic names are never renamed;
  - the entity carries `@DropdownCandidate`, and the code honours it (`displayField`,
    `valueField`, the `activeField` default, `sortField` / `sortDirection`, `searchable`,
    `cacheable`). The request adjusts the defaults like this:

    | Parameter | Effect |
    |---|---|
    | `status` | With an `activeField`: `ACTIVE` (default) → `= TRUE`, `INACTIVE` → `= FALSE`, `ALL` → no predicate. **Without one, anything but `ACTIVE` is a 400** `REQUEST_PARAMETER_INVALID` (`errors: [{"parameter": "status", ...}]`), never silently ignored |
    | `sortDirection` | Flips `sortField`'s direction. Each direction is its own keyset (`NAME_ASC`, `NAME_DESC`), so a cursor from one direction is refused in the other |
    | `size`, `after` | The batch (default and maximum from `app.limits.cursor-page`) and the position; over the maximum, or a foreign or tampered cursor, is a 400 |
    | `query` | Only when `searchable`: `TextSearch.contains` on `displayField`, `@MaxLength("search-query")`. On a non-searchable dropdown it is a 400, never ignored |
  - the **service** builds the wrapper; the controller only delegates;
  - the query is a Criteria **projection** (no entity loading), keyset-paged on `sortField` then the
    value, with indexes `(scope columns, sortField, valueField)`;
  - results are scoped like the resource's reads: a dropdown never lists items the caller couldn't
    fetch;
  - security per `{{dropdownSecurity}}` (default `isAuthenticated()`);
  - `cacheable` only where the developer asked for it (the crud profile's *Decisions per entity*),
    with eviction on writes, and a shared cache (or a confirmed reason) when `{{instances}}` is
    `multiple`;
  - documented in the API interface and tested.
- **Also:** a screen that needs a picker has its dropdown (an assignee picker for "reassign"); a
  resource other screens pick from (another entity references it) without `@DropdownCandidate` is
  worth asking about.
- **Detect:**
  - other shapes or paths for id+name lists (`/lookup`, `/options`, `{id, name}`);
  - ad-hoc params (`activeOnly`, `q`, `search`, `limit`);
  - a dropdown that can't return more than one batch;
  - controller-side wrapping;
  - `@Query` dropdowns that can't honour the generic params;
  - entity `findAll()` used as a dropdown;
  - unbounded queries;
  - `@Cacheable` without eviction.
- **Severity:** Medium (High for a dropdown leaking records the caller can't see; Low for docs
  only).

### REST-14: Rate limiting
- **Check:** auth and other abuse-prone endpoints (OTP, signup, anything that costs money such
  as SMS, public writes) are rate limited per IP and per account, with a shared backend when
  several instances run. The client IP comes from `getRemoteAddr()`; `X-Forwarded-For` is
  honoured only through a trusted-proxy configuration: `server.forward-headers-strategy: native`
  with the proxies' addresses in `server.tomcat.remoteip.internal-proxies`, so a client can't set
  its own IP and get a fresh bucket.
- **Also:**
  - a bucket key a caller can multiply by changing the path suffix is a finding (prefix paths
    collapse into one bucket);
  - cost-bearing routes (AI, SMS) get a tighter bucket (REL-12).
- **Not a finding when:** the profile documents an edge proxy (Nginx) as the IP limiter, or
  `{{instances}}` is `single` with an in-memory bucket.
- **Severity:** High on auth; Medium elsewhere.

### REST-15: Tokens
- **Check:**
  - stateless JWT;
  - refresh tokens rotated on every use, stored hashed;
  - logout matches `{{tokenRevocation}}`:
    - `blacklist`: the access token's jti goes on a blacklist with a TTL, in any store, checked
      on each request. A cache hit must not borrow a database connection, and a store failure
      answers 503, not 401 (DATA-17);
    - `short-lived(<n>)`: the access token TTL is ≤ n minutes and the refresh token is revoked
      on logout; a token stays valid for TTL + the validator's clock skew (Spring's
      `JwtTimestampValidator` default: 60 s), so a stated bound includes the skew;
    - `session-version` (the `session-version` piece, on top of `short-lived`): every token carries
      `sv`, checked on every request against Redis (the database by primary key while Redis is down,
      never "accepted"); every access change raises it in its own transaction (SEC-07), so revocation
      is immediate. One device's logout still revokes only its refresh token. The stated bound for a
      lost publish or an operator's SQL change is `app.session-version.cache-ttl`;
  - each cached security fact (revocation, account status, role) states its staleness bound, per
    instance, in the configuration and the API docs: a disabled account keeps access for that long;
  - a refresh token reused after rotation revokes its whole family (reuse detection): only a stolen
    copy is ever reused;
  - a role or status change takes effect within the stated bound: a token version claim checked
    against the user, or an access TTL no longer than the bound.
- **Severity:** High.

### REST-16: Authorization
- **Check:**
  - role checks on every protected method;
  - ownership checks on every user-owned record;
  - **404** when the caller can't know the record exists (another user's or tenant's record), so
    ids can't be enumerated;
  - **403** when the caller can see the record but this action isn't allowed (view yes, edit
    no), with a specific `errorCode`; and for a missing role;
  - **a record two parties see with different rights** (a leave request: the requester edits and
    cancels, their manager decides): one visibility rule (`visibleTo(caller, record)`, 404 for
    anyone else), then a 403 code per refused action (`<ENTITY>_NOT_YOURS`,
    `<ENTITY>_DECISION_NOT_ALLOWED`). A relationship that grants the right (the requester's
    manager) is read under the same lock as the record it decides, or a reassignment between the
    read and the write lets the former party act. The tests cover each party × each action, plus a
    stranger (404) and the relationship changing (TEST-02). No golden code yet: ask for the parties
    and their actions before generating.
- **Detect:** endpoints that take an id and load a user-owned record without comparing it to
  the caller; `/users/{id}/...` where `/users/me/...` is meant.
- **Severity:** Critical (IDOR) or High.

### REST-17: Passwords
- **Check:** **both**:
  - a composition `@Pattern` (upper, lower, digit, special);
  - length 8–72 **bytes** (bcrypt ignores everything after 72) plus a breached-password check.
- **Severity:** Medium (High if over 72 bytes is accepted silently with bcrypt).

### REST-18: Transport
- **Check:**
  - gzip on (`server.compression.enabled`), or documented as done by the edge proxy;
  - security headers on (HSTS, `X-Content-Type-Options`, `X-Frame-Options`), not disabled in
    the security configuration;
  - CORS origins from configuration, trimmed, no wildcard with credentials; credentials off
    entirely when auth is a header, not a cookie (nothing needs them, and they widen what a
    misconfigured origin can do).
- **Severity:** Low / Medium.

### REST-19: Long-running work
- **Check:** work longer than about a second (AI, external jobs, exports) runs asynchronously:
  202 + a status endpoint (or a webhook), never a request thread waiting on it.
- **Severity:** Medium.

### REST-20: OpenAPI on API interfaces, and doc drift (every run)
- **Check:**
  - each controller implements an API interface `controller/api/<Name>Api` that carries
    `@Tag`, and `@Operation` + `@ApiResponse` for every status the endpoint can return;
  - request and response DTO fields carry `@Schema` (description, example, constraints);
  - multi-line descriptions are text blocks (JAVA-13).
- **Doc drift:** for every endpoint in scope, compare code and docs:
  - parameters and body fields: names, required, constraints;
  - response fields;
  - the status codes actually returned, including those from exceptions thrown by the service
    and mapped by the handler;
  - the role required.

  Any mismatch is a finding. Also check the consumers in `{{publishedContracts}}` (frontend
  changelog, k6 scripts); non-OpenAPI contracts follow ARCH-13. In **diff mode**, a change to a request or response DTO, a
  controller method or a thrown exception, with no change to the matching API interface or
  `@Schema`, is a finding.
- **Detect:** a code-only description (`description = "AUTHENTICATION_REQUIRED"`) while springdoc's
  i18n is on (no `springdoc.disable-i18n: true`): springdoc resolves it as a message key, and the
  published docs show the code's message, never the code a client switches on. Fix the setting (the
  reference's `config/server.yml`) and assert one code in the api-docs test.
- **Severity:** Medium (Low for missing examples).
- **Fix:** update the interface and `@Schema` in the same change.

### REST-21: Thin controllers
- **Check:** controllers map HTTP to a service call and nothing else. No business rules, no
  authentication logic (API-key checks go in the security chain or a component), no long
  how-to comments (they belong in docs). Return the service result as is.
- **Severity:** Medium.

### REST-22: Strict JSON input
- **Check:** unknown properties, coerced scalars, floats as ints and case-insensitive enums are
  refused (CFG-05), so a misspelt field is a 400 `REQUEST_UNREADABLE` and is never silently
  ignored. A body value is read only in its documented form: a date is `yyyy-MM-dd`, never a date-time
  (read leniently, a moment sent for a day stores the day before east of UTC) or a number; an enum is
  its name, never its position; a number and a boolean are never quoted; an id is the 36-character
  UUID. Another form is the same 400, pointing at the field, wherever a Jackson setting refuses it
  (CFG-05 lists them).
- **Detect:** a `spring.jackson` key of CFG-05 missing or relaxed; `@JsonFormat(lenient = …)` or a
  hand-written deserializer on a request field of a standard type. Not a finding: a form no setting
  refuses (a date as a `[year, month, day]` array or with surrounding spaces, a UUID as Base64, a number
  sent for a text field).
- **Why strict, not "tolerant reader":** tolerance is for data we read back, which we didn't just
  receive from the caller: stored JSON (DATA-27, META-04) and other services' responses are read
  leniently. A request is never read leniently: the caller is right there to be told about the
  typo.
- **Severity:** Medium.

### REST-23: Lists can be fetched by ids
- **Check:** every list endpoint accepts `ids` (`GET /todos?ids=a,b,c`), so a client that holds
  several ids makes one call instead of looping over the network. The number of ids is capped at
  the page size (over it is a 400, REST-08); unknown ids and ids the caller can't see are simply
  absent from the result (no 404, no hint that they exist, REST-16); the caller's scoping applies
  as for any other filter.
- **Detect:** a list endpoint with no `ids` filter; a client or a frontend document calling
  `GET /x/{id}` in a loop.
- **Severity:** Low (Medium when a documented screen loops over `GET /x/{id}`).

### REST-24: One call per screen when a screen needs many
- **Check:** when one screen needs many calls to render (mobile home, a dashboard), a read-only
  bootstrap endpoint (`GET /home`) returns them in one response. It is a read-only counterpart of
  ARCH-15's Manager: it composes the owning services' read methods (their `api` views in
  `api-facades` mode, ARCH-06), so their validation, scoping and caching still apply; it never reads
  repositories itself. Each part keeps its own size cap.
- **Detect:** a screen documented (frontend contract, API changelog, a client) as needing more
  than five calls on open.
- **Not a finding when:** the calls are lazy (below the fold, on a tab switch). Not generated:
  added when a screen asks for it.
- **Severity:** Medium.

### REST-25: Role names are declared once
- **Check:** role names are never string literals at use sites. Each role check is a
  meta-annotation declared once in `common/security` (`@AdminOnly` carrying
  `@PreAuthorize("hasRole('ADMIN')")`), and endpoints use the annotation. A renamed or misspelt role
  then fails in one place, not silently on one endpoint.
- **A new role** needs, in one change: its annotation next to `@AdminOnly`, a constant in the `Role`
  enum, a changeset widening the role check constraint (`ck_users_role` in the reference,
  widened for `ADMIN` by `0007-users-admin-role.sql`), and the test base's `new<Role>()` (through
  `newUserWithRole`). Without the changeset the first user given the role fails the insert.
- **Roles don't include each other**, and the house ships no role hierarchy: an account has exactly
  one role, `hasRole('USER')` is false for an ADMIN, and every check that names roles lists each role
  it lets in, so it reads on its own line: an upload purpose's roles (`UploadPurpose("USER", "ADMIN")`;
  `UploadUrlIntegrationTest`: a role it doesn't list is 403, a test skipped when it lists them all), a
  role annotation declared once with `hasAnyRole('ADMIN', 'MANAGER')`. USER-level endpoints carry no
  role check, so every role passes them. A `RoleHierarchy` bean is a finding unless the profile
  records it as a deviation: it must then be read by method security **and** `CurrentUser.hasRole`,
  and the copied media 403 tests rewritten against it.
- Role names that live in data or in another module's code (`UploadPurpose("USER")`, where the media
  module can't depend on the user module's enum) are checked by a test against the `Role` constants
  (reference: `UploadPurposeTest`).
- **Detect:** `hasRole('` or `hasAnyRole('` outside `common/security`; a `Role` constant with no
  changeset widening the constraint.
- **Severity:** Medium.
