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
  - a GET that INSERTs, e.g. a settings row created on read;
  - a PATCH with a field a client may clear (an optional value set back to none): a record body can't
    tell an absent field from a `null` one, so the PATCH either clears what the client didn't send or can
    never clear. The editable content is a PUT, or the clearable field a sub-resource of its own
    (`PUT /x/{id}/<field>` and `DELETE /x/{id}/<field>`).
- **Severity:** Medium.
- **Not a finding when:** a REST-03 state change or single-field sub-resource
  (`PATCH /x/{id}/role {role}`, `PATCH /x/{id}/enabled {enabled}`): its one field is required by
  nature, and the body *is* the change. The PUT smell is a PATCH on the resource itself
  (`PATCH /x/{id}`) whose body requires fields the client didn't mean to change.

### REST-05: Status codes
- **Check:**
  - 200: read or update with a body;
  - **201**: created, the new resource in the body. A `Location` header is optional (the golden todo
    creates have none, signup's names `/users/me`); when there is one, it names a GET that exists, so a
    resource read only through a list or a queue answers 201 without it;
  - **202**: accepted asynchronous work, with a status URL in the body;
  - **204**: any success with no body, not only DELETE;
  - 400: the request's own values are wrong (validation, a value outside an allowed range);
  - 401: missing or invalid token;
  - 403: permission missing (`ACCESS_DENIED`), or the action refused on this record;
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
  - the request id is in the `X-Request-ID` header (ERR-01); a `requestId` member in the body as well is
    fine (users quote the body), the same value;
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
    one from another sort is refused. The enum is only names (`NAME`, `NEWEST`): no Spring Data `Sort`
    and no property string in the request DTO. The repository maps each constant with an exhaustive
    `switch` over the JPA metamodel (`Author_.name`, `Author_.id`), so a renamed field or a constant
    without an order fails to compile (reference: `TagRepositoryImpl.orderOf` for an offset list,
    `TodoRepositoryImpl.orderOf` for a cursor list). The metamodel stays in the repository: the API
    layer sees only the constant's name, and the generated `_` classes come from the same compile as
    the entities, so no build order is involved. A test per constant
    still proves the order itself;
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
  a sort as a free String; `Sort.Order.asc("<field>")` or `Sort.by("<field>")` with a property string
  anywhere (a rename of the field compiles and answers 500); a list whose kind doesn't follow the rule for its recorded consumer, or a list
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
- **Detect:** a String stored in a `varchar(n)` column without a request limit at or below `n`: a longer
  value answers 500 at the insert today (DATA-12 asks for `text`, where the limit is the product's);
  request DTO fields without constraints; a `LocalDate` field or filter without
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
  - `{{BoundedListWrapper}}` only for a list the code bounds (an enum's values, a configured maximum enforced
    on create, a fixed relation such as a student's subjects this session), or one that
    `{{unpagedLists}}` names with the developer's statement.
- **Detect:** an unpaged list the code doesn't bound and `{{unpagedLists}}` doesn't name; a paged list
  returned as `{{BoundedListWrapper}}`, or page details put in `extraDetails`
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
  - security per `{{dropdownSecurity}}` (default `@SignedIn` with a required `for=<Permission>`: the
    dropdown is shared by every screen that picks the resource, refuses a `for` the caller doesn't hold and
    lists the options of its reach, in one query; AUTHZ-08);
  - `cacheable` only where the developer asked for it (the crud profile's *Decisions per entity*),
    with eviction on writes, and a shared cache (or a confirmed reason) when `{{instances}}` is
    `multiple`;
  - documented in the API interface and tested.
- **Also:** a picker for a write offers exactly what that write accepts (the roles an assignee may have,
  the statuses a parent may be in): one definition both use (a constant or a method of the owning module),
  and a test that a value the dropdown leaves out is refused by the write. The screen names that write in
  `for` (AUTHZ-08); a dependent dropdown's parent is a plain filter of the same query. Where a domain rule can
  refuse an option right now and the screen shows it disabled with its reasons, the write serves its own options
  (`GET /<write's resources>/<resource>-options`, `CursorWrapper<SelectableDropdownItem>`, `{value, label,
  selectable}`, the same batching and `query`; AUTHZ-08), never a flag added to `{value, label}`.
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
  - a permission on every handler (`@Requires`, or `@SignedIn` / `@Public` by declaration: AUTHZ-03);
  - ownership checks on every user-owned record;
  - **404** when the caller can't know the record exists (another user's or tenant's record), so
    ids can't be enumerated;
  - **403** when the caller can see the record but this action isn't allowed (view yes, edit
    no), with a specific `errorCode`; and for a missing permission (`ACCESS_DENIED`);
  - **a record two parties see with different rights** (a leave request: the requester edits and
    cancels, their manager decides): one visibility rule (`visibleTo(caller, record)`, 404 for
    anyone else), then a 403 code per refused action (`<ENTITY>_NOT_YOURS`,
    `<ENTITY>_DECISION_NOT_ALLOWED`). A relationship that grants the right (the requester's
    manager) is read under the same lock as the record it decides, or a reassignment between the
    read and the write lets the former party act. The tests cover each party × each action, plus a
    stranger (404) and the relationship changing (TEST-02). The rules are the owning module's policy
    (AUTHZ-04). Reference: a todo's requester reads it (`TodoPolicy.read`) and gets 403 `TODO_NOT_YOURS` on
    every other call (`TodoPolicy.edit`), `TodoPolicyTest`, `TodoRequesterIntegrationTest`. Ask for the
    parties and their actions before generating.
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
    misconfigured origin can do);
  - CORS as a servlet filter ahead of everything that can answer before the security chain
    (admission control's 503, the firewall's 400) and the chain's `.cors()` disabled: otherwise those
    answers carry no `Access-Control-Allow-Origin` and the browser hides them; `Retry-After` and the
    request id among the exposed headers;
  - no `OPTIONS` permit in any security chain (`requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()`):
    the CORS filter answers every preflight before the chain, so the permit only lets a plain `OPTIONS`
    through without a token; it is 401 like any other unauthenticated request;
  - an origin the CORS settings refuse is Problem Details (403 `CORS_REQUEST_REFUSED`, a
    `DefaultCorsProcessor` that writes through `ProblemResponses`, set on the `CorsFilter`), not Spring's
    plain-text `Invalid CORS request` (ERR-01).
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
  - the permission required (the `403` names it: `ACCESS_DENIED (without X)`).

  Any mismatch is a finding.
- **Drift is tested, not only reviewed.** Prose can't be checked, so the docs keep as little of it as
  possible, and the rest is held by tests over the app's own `/v3/api-docs`:
  - constraints, required fields and enum values come from Bean Validation and the types, never repeated
    in a description: `ApiDocsDriftIntegrationTest` fails on a description stating a bound ("at least 8
    characters", "up to 100") where its schema publishes one (`maxLength`, `minimum`, `maxItems`...);
  - every error code a description names exists in `ErrorCode` (the same test: a renamed or removed code
    fails; a value the docs publish as an enum, a permission, is not a code);
  - every outcome the integration tests observe (method, path template, status, `errorCode`) is documented
    on that operation: a MockMvc hook every test context picks up (reference: `support/ObservedOutcomes`)
    fails the test whose response's status the operation doesn't list, or whose code that status's
    description doesn't name. A response a filter wrote (401, 429, 503) counts too. Codes any operation
    answers (content negotiation, the firewall) are a short allow-list in the hook, a why each; one a
    client should see everywhere is documented by an `OpenApiCustomizer` instead (CORS, the version, the
    permission). An outcome no test produces is a missing test (TEST lens), not a docs question;
  - descriptions say what the names can't: the business meaning and the permission.
  Also check the consumers in `{{publishedContracts}}` (frontend
  changelog, k6 scripts); non-OpenAPI contracts follow ARCH-13. In **diff mode**, a change to a request or response DTO, a
  controller method or a thrown exception, with no change to the matching API interface or
  `@Schema`, is a finding.
- **A tightened rule is a breaking change** (a free text becoming a pattern, a lower maximum, a value
  once truncated now refused): an entry marked *breaking* in the changelog, and a check of the stored
  values (a query, or a changeset that fixes or fails on them): a stored value the new rule refuses fails
  every later PUT that sends it back.
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
  sent for a text field), nor a value with a leading or trailing space or control character, NUL
  included, which Jackson's date, moment and enum readers and Spring's query converters trim, nor a
  single-valued query parameter sent twice (Spring takes one);
  core's `StrictJsonInputIntegrationTest` holds every other form of every type in a body, a query
  parameter and a cursor key.
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

### REST-25: Permissions are declared once
- **Check:** no permission or role name is a string literal at a use site. A handler names its permission
  as an enum constant (`@Requires(Permission.TODO_EDIT_OWN)`), the one meta-annotation declared in
  `common/security`, or is `@SignedIn` or `@Public`; a renamed or misspelt permission then fails at compile
  time, not silently on one endpoint. Roles are groups of permissions (`Role` lists them) and nothing checks
  a role. The rules are `references/authorization.md` (AUTHZ-01 to AUTHZ-03).
- **A new role** needs, in one change: its constant in `Role` with its permissions, a changeset widening the
  role check constraint (`ck_users_role` in the reference, widened for `ADMIN` by
  `0007-users-admin-role.sql`), and the test base's `new<Role>()` (through `newUserWithRole`). Without the
  changeset the first user given the role fails the insert. **A new permission** needs its constant and a line
  in each role that gets it (AUTHZ-02).
- **Roles don't include each other**, and the house ships no role hierarchy: an account has exactly one
  role, and a permission two roles share is listed on both. A check the request's data decides (an upload
  purpose's permissions, a dropdown's `for`) names permissions too (`UploadPurpose(TODO_CREATE_OWN,
  TODO_EDIT_OWN)`; `UploadPurposeTest`, AUTHZ-10).
- **Detect:** `hasRole(`, `hasAnyRole(`, `hasAuthority('` or `@PreAuthorize` outside `common/security`
  (ArchUnit); a `RoleHierarchy`; a `Role` constant with no changeset widening the constraint.
- **Severity:** Medium.
