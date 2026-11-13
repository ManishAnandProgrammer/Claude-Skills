# REST API rules (REST-xx)

Source: `rest-api-best-practices.md`, plus the decisions recorded in DESIGN.md. Each rule
gives what to **Check**, how to **Detect** it, its default **Severity**, and when it is **Not
a finding**. Profile keys are written `{{key}}`.

---

### REST-01: Resource naming
- **Check:** paths are nouns, plural for collections, kebab-case; JSON fields camelCase; one
  resource name per concept across the API (no `/sources` for outlets when "sources" means
  something else).
- **Detect:** list every `@*Mapping` path. Flag:
  - verbs (`/getX`, `/runs/all`, `/approve-bulk`);
  - singular collections (`/spot-check`);
  - camelCase or snake_case path segments;
  - two names for one resource, or one name for two resources.
- **Severity:** Medium.
- **Not a finding when:** auth endpoints in RPC style (`/auth/login`, `/auth/otp/send`), which
  are conventional.

### REST-02: Hierarchy
- **Check:** sub-resources nest under their parent (`/outlets/{id}/ai-runs`), not the other way
  round (`/ai/runs/sources/{outletId}`). At most two levels deep.
- **Severity:** Low.

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
  - 400: validation or business rule;
  - 401: missing or invalid token;
  - 403: role missing;
  - **404**: not found, *or not yours* (REST-16);
  - 409: conflict or duplicate;
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
  - the protocol-agnostic `ErrorCode` enum is mapped to HTTP in one place;
  - messages come from a `MessageSource` (i18n);
  - **never** class names, SQL fragments, constraint names or stack traces.
- **Detect:** the global handler and every `ResponseEntity` / `ProblemDetail` built by hand;
  exception classes that differ only by name (copy-paste hierarchy); `getMessage()` of a
  database or framework exception copied into `detail`.
- **Severity:** High if internals leak, otherwise Medium.
- **Reference implementation:** `error-contract.md` (the user's DynamicForm contract). Check
  every piece there: ERR-01 members, the ERR-02 mapping table, ERR-03 transient failures, ERR-04
  throwing rules.

### REST-07: Paths and versioning
Owner's decision (2026-11-13): paths on the controller class, versions through Spring Framework 7's
API versioning, carried in the path.
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
  - path patterns elsewhere (security public paths, admission groups) use a version wildcard
    (`/api/*/auth/**`), so a new version needs no change there and inherits their protection;
  - deprecating a version: an `ApiVersionDeprecationHandler` bean (RFC 9745 `Deprecation` /
    `Sunset` headers), not hand-written headers.
- **Detect:** a full path on a method; a method without `version`; `/api/v1/` written into a security
  or admission pattern; a second controller for the same resource per version.
- **Enforce:** ArchUnit (reference: one class-level path per controller; every handler declares its
  version).
- **Severity:** Medium.

### REST-08: Lists
- **Check:**
  - **pagination:** **cursor-based by default** for lists in `{{cursorLists}}` (user-facing,
    growing, feeds, dropdowns): `after` cursor, `CursorWrapper` with `pageInfo {nextCursor,
    hasMore}`, no count. **Offset** only for bounded admin tables in `{{offsetLists}}`:
    `PaginationWrapper`, the count only on a full page;
  - **sizes from configuration** (`app.limits.cursor-page` / `offset-page`); over the maximum is a
    400, never a silent clamp;
  - **filters:** validated fail-fast (400 `REQUEST_PARAMETER_INVALID` on an unknown value, naming
    the parameter and nothing else), typed as enums, not free strings;
  - **free-text search** is the `query` parameter in every list and dropdown;
  - **sorting:** a sort enum (allow-list), a stable tie-breaker (id); a cursor carries its sort, and
    one from another sort is refused;
  - `POST /x/search` with a body only when a query string can't express the filter. The HTTP
    `QUERY` method (RFC 10008, June 2026) replaces it once the project's Spring has it in a GA
    release (merged for Spring Framework 7.1) and the proxies and OpenAPI tooling pass it.
- **Detect:** a `PageRequest` from raw client values; an unbounded `findAll()` behind an endpoint;
  a sort as a free String; an OFFSET list over a growing table; a count on a cursor list.
- **Severity:** High for an unbounded list, otherwise Medium.

### REST-09: Validation layers
- **Check:**
  1. bean validation at the edge: `@Valid` on every `@RequestBody`; **a length limit on every
     String** (`@MaxLength("<key>")`, the number in `app.limits.text`: configurable, one source of
     truth; a literal `@Size(max = N)` is a finding where the house limits exist); ranges on
     numbers (`@ConfiguredMax` for tunable ones); `@NotEmpty` + max size on collections;
     constraints on `@RequestParam` / `@PathVariable`;
  2. security validators (REST-10);
  3. domain validation in the service (existence, state, ownership).
- **Detect:** request DTO fields without constraints; a body parameter missing `@Valid`; a
  service re-checking by hand what a constraint could declare (duplicated bounds).
- **Severity:** **High for a String with no length limit, unless the author justifies it** (ask;
  a justified exception is an accepted deviation); High for other unbounded input; otherwise
  Medium.

### REST-10: Hostile text
- **Check:** every free-text input has `{{NotXss}}` or a documented output-escaping policy.
  `{{NoEmoji}}` only on the fields listed in `{{noEmojiFields}}` (codes, identifiers), never on
  content: readers write Gurmukhi and emoji.
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
- **Severity:** Medium.

### REST-13: Dropdowns follow the house standard
The standard is crud-gen `references/dropdown.md`: the user's `@DropdownCandidate` contract plus
the house decisions. Its purpose: one shape, so the frontend has one component for every
dropdown.
- **Check:**
  - path `GET {{urlPrefix}}/<resource-plural>/dropdown`; a parent in the path only for a true
    child resource; no named sub-paths (`by-x-id/dropdown`);
  - response `CursorWrapper<{{DropdownItem}}>` (cursor-paged, so a large set is browsed in
    batches or narrowed with `query`), items a record `{value, label}` with no other fields;
  - parameters: `{{DropdownFilter}}` (`size`, `after`, `status`, `sortDirection`, `query`), or a
    subclass adding the entity's mandatory (`@NotNull`) and optional filters. No loose
    `@RequestParam`s; the generic names are never renamed;
  - the entity carries `@DropdownCandidate`, and the code honours it (`displayField`,
    `valueField`, the `activeField` default, `sortField` / `sortDirection`, `searchable`,
    `cacheable`);
  - the **service** builds the wrapper; the controller only delegates;
  - the query is a Criteria **projection** (no entity loading), keyset-paged on `sortField` then the
    value, with indexes on the filter and sort columns;
  - `query` on a non-searchable dropdown is a 400, not silently ignored; so is a `status` other
    than `ACTIVE` on an entity without an active flag;
  - results are scoped like the resource's reads;
  - security per `{{dropdownSecurity}}` (normally `isAuthenticated()`);
  - `cacheable` only for static master data, with eviction on writes, and a shared cache (or a
    confirmed reason) when `{{instances}}` is `multiple`;
  - documented in the API interface and tested.
- **Also:** a screen that needs a picker has its dropdown (e.g. editors listing editors for
  "reassign").
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
  honoured only through a trusted-proxy configuration.
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
      on logout.
- **Severity:** High.

### REST-16: Authorization
- **Check:**
  - role checks on every protected method;
  - ownership checks on every user-owned record;
  - **404** when the caller can't know the record exists (another user's or tenant's record), so
    ids can't be enumerated;
  - **403** when the caller can see the record but this action isn't allowed (view yes, edit
    no), with a specific `errorCode`; and for a missing role.
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
  - CORS origins from configuration, trimmed, no wildcard with credentials.
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
  ignored.
- **Severity:** Medium.
