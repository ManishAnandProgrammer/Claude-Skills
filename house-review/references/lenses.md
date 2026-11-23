# The eight lenses (SEC / PERF / REL / OPS / CONC / TEST)

"Tests verify 'does the feature work as designed?'; they don't verify 'what does the code allow
that it shouldn't?'"

Which lenses each review mode runs: SKILL.md, Step 1. Lens 1 is the build and tests plus TEST-01; lens 6 is rest-api.md and error-contract.md; lens 7
is code-smells.md, java.md and architecture.md.

---

## Lens 2: Security (SEC)

### SEC-01: Who must NOT be able to call this?
- **Check:** for every endpoint, name who must be refused (role, ownership, account state) and
  verify the code refuses them.
- **Shared resources** (a table nobody owns: a catalogue, reference data, staff records; no owner
  column, no owner predicate in its service): with public signup (the `sign-in` piece) every internet
  account is a USER, so each write (POST, PUT, PATCH, DELETE, upload purposes for its files) needs a
  permission USER doesn't hold (`@Requires(Permission.X)`, granted to the roles that write it: AUTHZ-02). Rows naming people (a borrower's name, an email,
  a phone) need the same decision for reads: list, get, filters and dropdown. The decision is in the
  profiles' conventions; the tests prove a USER's 403 with a valid body. **With tenancy** the rule is
  different: the public signup reaches no tenant data (a tenant token needs a membership a tenant ADMIN
  granted), so a tenant-shared table written by every member is a legitimate owner decision. It is
  recorded with that reason, and the tests prove every role reaches every endpoint on another member's
  record (crud-gen tests.md row 14), not a USER's 403. The same goes for an upload purpose naming
  `USER`: never while a public signup reaches the table, fine for a member someone added.
- **Detect:** a controller whose service has no owner scope and whose write methods carry no role
  annotation while signup is public and there is no tenancy; a shared resource with personal fields
  readable by every USER without a recorded decision. Not a finding: the same with tenancy and a
  recorded "every member writes" decision.
- **Severity:** Critical when a refusal is missing (a shared table any account can write is
  Critical); High for personal data readable by every account (SEC-04).

### SEC-02: Authentication flows
- **Check:**
  - every factor is required: a second factor can't be started or completed without the first (a
    challenge id bound to the successful password step);
  - no account enumeration (same answer and timing for unknown user and wrong password; "send
    code" always answers 200);
  - codes are single-use, purpose-bound, expiring and attempt-limited;
  - a channel (phone, email) is verified before it is used to log in;
  - nobody removes the last admin or changes their own role.
- **Severity:** Critical / High.

### SEC-03: Configuration is code
- **Check:** insecure behaviour is never the default when a setting is missing:
  - no default profile that enables sandboxes;
  - guards use an allow-list (`dev`/`test` may), never a deny-list (`!prod`);
  - mocks of SMS, email or AI can't run in prod;
  - test-only endpoints are profile-gated;
  - secrets have no defaults;
  - only health is unauthenticated on the management port; the metrics scrape is secured
    (CFG-06).
- **Severity:** Critical when the exploit needs only a forgotten variable.

### SEC-04: Secrets and personal data
- **Check:**
  - no secrets, tokens, codes, passwords or personal data in logs, error bodies or exceptions;
  - the client IP follows the configured `app.logging.client-ip` mode (CFG-07);
  - secrets are compared in constant time;
  - tokens and codes are stored hashed;
  - personal data is hashed before use as a key;
  - **heap dumps and JFR files are secrets**: private paths, never in images or logs;
  - **a library's own messages count**: a driver or framework that copies values into an exception
    message (pgjdbc's server `DETAIL` and a failed batch's bound values) puts them in every log line that
    logs the exception.
- **Detect:** `logServerErrorDetail` not `false` on every PostgreSQL data source (the pool's
  `data-source-properties`, a data source built in code such as Liquibase's: CFG-01); a test log of a
  409 path showing `Key (` or `Batch entry 0 insert`; `log.*(…, exception)` or `exception.getMessage()`
  logged where the exception carries a request's values (a JSON parse error with the body, a
  `ConstraintViolationException`'s invalid value; a 503 line with `exception.toString()`: the class and SQL
  state only); `org.hibernate.orm.jdbc.error` left at WARN (two lines per refused statement, CFG-07);
  Hibernate's `org.hibernate.orm.jdbc.bind` or SQL parameter logging outside dev.
- **Severity:** High (Critical for login codes in logs).

### SEC-05: Injection and hostile input
- **Check:**
  - no SQL built from strings;
  - hostile text follows the REST-10 policy;
  - validators actually handle the field's type;
  - no internals in error bodies;
  - strict JSON input (CFG-05);
  - the input grammar is owned: what is valid is a product decision pinned by tests, and only what
    every layer (Java, the database, the client) reads alike is accepted. Never delegated to a
    library's parser: one widened what it accepts in a minor release, and Unicode digits passed
    Java but failed in PostgreSQL;
  - no regex with overlapping quantifiers (`(a+)+`, `(\w|\d)*`) on user input: one long input
    takes quadratic or exponential time (REL-05);
  - **a destination a user names** (a site to fetch, a webhook URL) is checked before **every** request
    of the call (a method fallback such as HEAD then GET, and each redirect, followed by hand): the host
    is resolved and every address refused unless public, so a name that resolves again (DNS rebinding) or
    a redirect never reaches an inner address. The check unwraps the IPv6 forms that carry an IPv4
    address (mapped `::ffff:0:0/96`, NAT64 `64:ff9b::/96`, 6to4 `2002::/16`, Teredo `2001::/32`) and
    refuses the reserved IPv4 ranges besides the private ones (`0.0.0.0/8`, `100.64.0.0/10`,
    `169.254.0.0/16`, `198.18.0.0/15`, `240.0.0.0/4`): a resolver hands `::ffff:127.0.0.1` over as an
    `Inet6Address`, which `isLoopbackAddress` and `isSiteLocalAddress` both pass. Its tests answer from a
    fixed resolver (a test bean), never the network and never a literal: Java turns a `::ffff:` literal
    into an `Inet4Address`, so such a test passes for the wrong reason. A check at save time (a validator
    resolving the host) runs outside any transaction (DATA-01) and refuses a name that doesn't resolve;
    the check per request stays, since the answer can change.
- **Severity:** Critical (SQL) / High (a user-named destination reaching an inner address).

### SEC-06: "Unknown" is a refusal
- **Check:** a security predicate that can't decide (a missing claim, an absent row, a null, an
  unparsable value) refuses. A failing dependency answers 503 (DATA-17), never "allowed".
- **Why:** a token without the user-id claim skipped the lock and token-version checks, so it
  outlived both.
- **Detect:** `orElse(true)` or `return true` in a permission or state check; a check guarded by
  `if (claim != null)`; a `catch` that lets the request through.
- **Severity:** Critical when a refused caller gets in; otherwise High.

### SEC-07 (profile-optional: `tokenRevocation: session-version`): Every access change bumps the session version
- **Check:** with the session version on (the `session-version` piece, opt-in; golden
  `SessionVersionService`), every write that changes what a person may do raises their session
  version **in the same transaction** as the change: a role change, a removal from a tenant, a
  suspension or disable of the account or of its tenant, a password change, signing out everywhere,
  and any new one (a lock, a downgrade, a revoked grant), except the roles piece's access changes (below). Per account without tenancy; per membership
  with tenancy, so only the tenant that changed is touched (golden `MembershipService`). The bump is
  the aggregate service's own write (its repository, `Propagation.MANDATORY`), published to Redis only
  after the commit (`SessionVersionsRaised`, `AFTER_COMMIT`, a plain SET); a cache miss fills with
  SET NX. Where the change takes access away, the same transaction also revokes the refresh tokens the
  code doesn't already refuse (signing out everywhere revokes them all; a removed member's refresh
  already fails on the missing membership, a suspended tenant's on its status). Every token carries
  `sv` read from the same row as its role. The number only goes up and is compared with `==`. The
  writes that check the caller's current role in the database (rung 3, `lockAsCurrentAdmin`) stay:
  the session version is in addition, not instead.
- **Check (every token path, with or without the piece):** the sign-in piece's account lifecycle is
  golden: a password change (`POST /users/me/password`: the current password, 400 `USER_PASSWORD_INCORRECT`,
  rate limited as the account's login; every refresh token revoked `PASSWORD_CHANGED`, the session version
  raised, new tokens for the calling device), an account suspension (`AccountStatusService`: the row
  updated first, every refresh token revoked `SUSPENDED`, the session version raised; a reactivation revives
  nothing) and, with tenancy, staff deactivation (TEN-05). Every path that issues a token (login, refresh,
  a password change; with tenancy switch-tenant and impersonation) starts with the account's row
  share-locked and ACTIVE (`UserService.activeForTokenIssue`, 403 `AUTH_ACCOUNT_SUSPENDED`), before any
  refresh token or membership: a change of access racing an issue then leaves no token valid. Without the
  piece the access tokens already out live out their lifetime (REST-15's bound) and issue nothing. The
  table of states × paths with a test per cell is `references/tenancy-matrices.md` (*Every state × every
  token path*; without tenancy its suspended-account row).
- **Detect:** a new write to a role column, a status, a membership row, a password hash or the refresh tokens
  without a bump in its transaction; a token issue that reads the account without its share lock, or
  after it spent the refresh token (a suspension racing it leaves a token); a status check on some paths
  only; a bump in another transaction (`REQUIRES_NEW`, `@Async`, a second
  service call from a non-transactional caller); Redis written before the commit, or a miss filled
  with a plain SET; a reset of the number; a token whose number is read apart from its role; an
  operator's SQL change of access documented without the bump (runbook *Operator tasks*).
- **Tests (TEST-01):** the older token is 401 `SESSION_VERSION_STALE` on the very next request and the
  refresh returns the new access; a removed or suspended member's refresh fails; a rolled-back change
  raises nothing and leaves Redis alone (golden `SessionVersionServiceIntegrationTest`,
  `SessionVersionTenancyIntegrationTest`).
- **Not a finding when:**
  - one device signs out: it revokes that refresh token and lets the access token run out (a bump would sign
    out every device);
  - **with the roles piece** (AUTHZ-11), a change of a person's roles or personal grants, a role's edit, or a grant
    reaching its `valid_until`, with no bump: the permissions are read from the tables on every request and the
    token carries none of them, so the next request already holds the new set (golden `PersonAccessService`,
    `AccessRoleManager`, which raise nothing). The bump stays for what the token carries or its refresh relies
    on: the account's status, the password, signing out everywhere, and with tenancy the membership (a removal, a
    tenant's suspension).
- **Severity:** High: a revoked person keeps access for up to one token lifetime.

---

## Lens 3: Performance (PERF)

### PERF-01: Hot paths answer four questions
- **Check:** for every changed endpoint in `{{hotPaths}}` and every changed hot path (any list, dropdown or
  public endpoint: SKILL.md Step 3), the review report answers:
  1. how many SQL statements per request;
  2. new allocation or retained collections (GC pressure);
  3. CPU-bound or long blocking work on a request thread (bcrypt, a remote call): it holds one of
     `server.tomcat.threads.max` and an admission permit for its whole duration, so it gets an admission
     group of its own (the reference's `sign-in`);
  4. **which metric would show a regression in production.**

  A cache hit takes no connection (the lazy proxy, DATA-20).
- **Detect:** a new index on `updated_at` or `version`: every update of that table stops being a HOT
  update (DATA-10).
- **Severity:** Medium (High when the statement count or the memory a request holds grows with the
  number of rows).

### PERF-02: Connection pool
- **Check:**
  - nothing holds connections longer than needed (DATA-01..03, 20);
  - the pool and server timeouts follow `config-baseline.md` (CFG-01);
  - exhaustion shows up as pending > 0 sustained, or hold time much greater than query time
    (usage histogram).
- **Severity:** High.

### PERF-03: Bound concurrency explicitly, per route group
- **Check:** Tomcat's `threads.max` bounds the total, but the excess waits unseen in the accept queue
  until the client times out, and one slow route group takes the threads every other group needs.
  Admission control decides at the door instead:
  - a Resilience4j **semaphore** bulkhead per route group (the `api` group at least 2× the pool,
    checked at startup, golden `app.admission`; a report or export group's limit is the developer's
    number), or Spring 7 `@ConcurrencyLimit`;
  - a short max-wait, then 503 `SERVER_BUSY` + `Retry-After`;
  - a rejected-count metric per group;
  - placed before the security chain and behind CORS, which is a servlet filter of its own (never the
    chain's `.cors()`), so a 503 carries the CORS headers a browser needs and a preflight takes no permit;
  - the actuator is never capped, matched as its base path or a path below it (never a bare prefix:
    `/actuatorX` is an API path).
- **Severity:** High on a hot path, a report or an export.

### PERF-04: Long reads and exports
- **Check:**
  - exports read in bounded keyset pages, one short read-only transaction per page, streaming
    between transactions;
  - CPU work and uploads run with no transaction;
  - an offset list counts only on a full page, a cursor list never (DATA-11). **Extension point (a
    use case):** a project whose counts get slow caps them ("10,000+") and computes them once per
    filter set;
  - export and report row caps exist;
  - CSV is written with a library (Commons CSV, Jackson CSV), never hand-quoted, and a cell
    starting with `=`, `+`, `-`, `@`, tab or CR is neutralised (prefixed with `'`): a spreadsheet
    runs it as a formula;
  - the export is written to a temporary file and sent once complete: streamed straight to the
    client, a failure halfway delivers a truncated file that looks complete.
- **Severity:** Medium (High on a hot path).

### PERF-05: Caches stay correct
- **Check:**
  - eviction happens after commit: `@CacheEvict` on a transactional method runs before the commit
    unless the cache manager is transaction-aware (`TransactionAwareCacheManagerProxy`), so a
    read in between reloads the old value and keeps it;
  - a local (per-instance) cache of mutable data is refused at startup when `{{instances}}` is
    `multiple`, unless its staleness bound is stated and accepted (REST-13, ARCH-17): an eviction
    reaches only its own instance;
  - loader-based, not check-then-put (CONC-01).
- **Not a finding when:** immutable values keyed by id: they need no eviction.
- **Severity:** Medium (High when a stale value is a security fact, REST-15).

---

## Lens 4: Reliability (REL)

### REL-01: Outbound calls
- **Check:** every outbound call has:
  - an **explicit** connect and request timeout (never the library default; SDKs set an API-call
    timeout greater than the attempt timeout);
  - retries only for retryable failures (network errors, timeouts, 5xx, 408, 429), with backoff,
    inside the deadline budget (REL-04);
  - a circuit breaker.

  Clients are built from Boot's builders (OPS-06), configured through the resilience library's
  registry, never inside a transaction. For user-defined destinations: cap concurrency per
  destination, bound the response size, honour `Retry-After`, check the address before every request
  (SEC-05).
- **Detect:** a Resilience4j breaker with `record-failure-predicate` (or `record-exceptions`) and no
  `ignore-exceptions`: every exception it doesn't record counts as a **success**, so a failure classified
  as not the dependency's fault (a permanent refusal) closes a half-open breaker; those go to
  `ignore-exceptions` (or an `ignore-exception-predicate`). A `CallNotPermittedException` reported as
  "paused" when the same call's own failed attempt opened the breaker: the call's outcome is that
  attempt's failure.
- **Not a finding (no breaker needed):** the only database (Hikari's wait + 503 already fails
  fast); a call the developer records as low-volume in the profile's Conventions (a timeout plus a
  quota is enough); paths where an open breaker would
  turn a failure into silent data loss.
- **Severity:** High.

### REL-02: Failure states terminate
- **Check:**
  - no flow can leave a record stuck in progress: timeouts move it to a terminal state;
  - retries back off and count attempts, and the Nth failure is DEAD;
  - claim queries can't be starved by orphans;
  - "replay" really re-does the work;
  - finished rows are purged by their finish time, never while running (OPS-03);
  - a sweep that ends work stuck in progress judges by when the work **started** (a claim the running
    thread sets, `started_at`), never by when it was queued or created: work that waited in a queue is
    otherwise ended as it starts. What never started has a limit of its own (the queue wait).
- **Severity:** High.

### REL-03: Dependency outages degrade the feature, not the app
- **Check:** fail-open or fail-closed is decided per dependency, and its client's javadoc says which
  (golden `RedisRateLimiter`). A fail-open fallback to the database is at most one indexed read per
  request (golden: the session version's read while Redis is down), or it moves the outage onto the
  pool. Health is covered in REL-14.
- **Severity:** High.

### REL-04: Timeouts nest (deadline budget)
- **Check:** each caller's deadline exceeds the worst case of what it waits for:
  - `attempts × timeout + backoff` per outbound call;
  - `pool wait + statement timeout` for SQL;

  all below the upstream (gateway, client) timeout. Otherwise "the callee finishes work nobody is
  waiting for", or pays twice for a paid retry.
- **Detect:** the timeout table built in Step 3b.
- **Not a finding when:** the work is asynchronous and answered with 202.
- **Severity:** High.

### REL-05: A timeout must stop the work
- **Check:** `Future.cancel(true)` and TimeLimiter only interrupt. CPU-bound code (regex,
  parsing, loops) never checks the interrupt flag. User-supplied regexes run with a deadline or a
  linear-time engine.
- **Severity:** High (about one stuck probe per core starves every carrier).

### REL-06: Effects after commit are durable
- **Check:** work that must happen never fails silently after commit (logged, but the client got
  201). Either do the read checks *before* the transaction and commit everything together, or
  write a pending row in the transaction for a poller. An idempotent replay repairs the side
  effect.
- **Severity:** Critical when user data is lost on a hot path (any list, dropdown or public endpoint);
  otherwise High.

### REL-07: No inert limits
- **Check:** every configured limit is really applied, and no comment claims a limit that
  doesn't exist. Admission caps whose sum reaches `server.tomcat.threads.max` never refuse; with
  virtual threads on, `server.tomcat.threads.max` and `spring.task.scheduling.pool.size` do nothing.
  Verify against the framework source.
- **Severity:** Medium (High when a safeguard relies on it).

### REL-08: Schedulers are isolated and stop cleanly
- **Check:**
  - scheduled jobs run on an explicit `TaskScheduler` with N named platform threads,
    `waitForTasksToCompleteOnShutdown` and an `awaitTerminationSeconds`;
  - one hung job can't stall the others, and a test proves it;
  - a long-running job triggers an alert;
  - jobs carry MDC ids (OPS-05);
  - with `{{instances}}` set to `multiple`, a distributed lock or idempotency;
  - no `fixedRate` switch on `SimpleAsyncTaskScheduler` (runs overlap).
- **Severity:** Medium (High when a staleness gauge also stops).

### REL-09: Bounded executors and queues
- **Check:**
  - no `Executors.newFixedThreadPool` / `newCachedThreadPool`;
  - no `ThreadPoolTaskExecutor` without `queueCapacity` (the default is `Integer.MAX_VALUE`);
  - no unbounded `LinkedBlockingQueue`;
  - a rejection policy is set;
  - async executors propagate the MDC (`TaskDecorator`).
- **Not a finding when:** a virtual-thread-per-task executor whose downstream is already
  bounded **and** whose backlog is bounded too (rejected past a limit) or held as a durable pending
  row with a backlog gauge: a semaphore bounds concurrency, not the queue, so parked threads hold
  the work in memory and lose it on shutdown. Don't recommend lowering `max-connections`.
- **Severity:** High.

### REL-10: Outages answer 503 + Retry-After, never 500 or 401
- **Check:** infrastructure failures answer 503 + a jittered `Retry-After` (error-contract.md ERR-02,
  ERR-03), never the generic 500:
  - database: pool exhaustion, statement timeout, lock timeout and an unreachable database,
    classified centrally by walking the **cause chain and SQLSTATE** (ERR-03);
  - a dependency's: SDK client errors and 5xx, `ResourceAccessException`, `CallNotPermittedException`,
    an AI provider unavailable. Classified where the client sees them, as `AppException.withCause(
    <DEPENDENCY>_UNAVAILABLE, failure)` (golden `MediaStorage`, ERR-02): only the client knows which
    dependency failed, and one that reaches the handler unclassified answers the generic 500 (a finding
    against the client).

  A failure inside the security filter chain answers 503, not 401 (DATA-17).
- **Severity:** High (filter → 401; database failures answering 500, `POOL-EXHAUSTION-IS-500`:
  clients retry 500s wrongly, or not at all); Medium (other 500s instead of 503).

### REL-11: Writes that clients retry are idempotent
- **Check:** a POST a client may retry after a 503 or timeout has an `Idempotency-Key` or a
  natural or client-chosen key, and replays the stored result. "A `JDBCConnectionException`
  during COMMIT… maps to 503, but the commit may have landed." Clients choose idempotency keys,
  never primary keys that carry meaning: a client-chosen time-ordered id allowed backdating.
- **Severity:** Medium (High on payment or submission paths).

### REL-12: Paid or quota-bearing calls
- **Check:** a call that costs money (LLM, SMS) has:
  - a burst limit **and** a total budget per account;
  - an application-wide budget per paid channel, so many accounts each at its own cap (self-made
    accounts pumping SMS) can't spend without bound; **one budget per purpose**: a sign-in purpose (a
    two-factor code) is never refused because an open purpose (codes to numbers nobody has proven) used a
    shared budget up, or whoever abuses the open path locks everyone out of signing in. The numbers are
    the developer's (ask);
  - usage recorded from the raw response *before* parsing;
  - retries counted inside the budget and the deadline.
- **Severity:** High.

### REL-13: Graceful shutdown
- **Check:** `server.shutdown: graceful`; `timeout-per-shutdown-phase` covers the normal request and
  job, and the container's stop grace is longer than the drain plus every executor's
  `awaitTermination` that runs after it, in sequence: a 10 s phase and two 10 s waits were killed
  by Docker's default 10 s grace. Executors wait with a bound. Claims and
  leases stay consistent on SIGTERM.
- **Not a finding when:** the drain is shorter than a rare worst-case request **by decision** (house
  default 30 s: the load balancer times out around 60 s anyway, and a short predictable drain is
  safer for deployments), recorded in the profile's Conventions.
- **Severity:** Medium.

### REL-14: Health, probes and the management surface
- **Check:**
  - liveness never includes external or optional dependencies;
  - readiness reads the pool's queue from memory, never by borrowing a connection;
  - health is unauthenticated (load balancer) on a separate management port;
  - the metrics scrape is secured;
  - the management port is never published publicly;
  - probes and scrapes need no database round trip (CFG-06).
- **Severity:** High when an optional dependency or pool exhaustion takes instances out or
  blinds monitoring.

---

## Lens 5: Operability (OPS)

### OPS-01: Logs follow the house pattern
- **Check:**
  - `{}` placeholders, with the exception passed last;
  - level policy:
    - ERROR: act now;
    - WARN: handled but abnormal;
    - INFO: business events;
    - DEBUG: diagnostics;
    - no INFO in loops or hot paths except the request-completion line;
  - context on every line, and an async appender, per CFG-07;
  - JSON in prod;
  - no personal data (SEC-04).
- **Possible finding (needs an experiment):** with `neverBlock=false` and
  `discardingThreshold=0`, requests can wait when stdout stalls.
- **Severity:** Medium.

### OPS-02: Metrics
- **Check:**
  - `http.server.requests` percentiles **and** histograms (never averages);
  - Hikari `acquire` **and** `usage` histograms;
  - timers on hot paths and outbound calls;
  - 503 counters by cause;
  - async executor queue depth;
  - from a latency spike, you can reach the query using only logs and metrics;
  - configuration comes from env-bound typed properties.
- **Severity:** Low / Medium.

### OPS-03: Growth (retention and purge jobs)
- **Check:** a table whose rows only accumulate has a purge job, or `{{retentionExceptions}}` lists it with the
  owner's reason (kept by decision: a legal retention, rows another table still refers to). Accumulating rows are
  tokens, one-time codes, sign-in challenges, an outbox, delivery or webhook logs, daily quotas and usage counters,
  finished jobs or operations, history and audit rows: rows nobody edits again, added by every request or job of a
  kind. Each table a change adds gets the question (kept or accumulating; crud-gen asks it), and its answer is in
  the code (the job) or the profile (the exception).
- **The purge** (golden: the sign-in piece's `RefreshTokenPurgeJob` over `TokenService.deleteExpiredBefore` and
  `RefreshTokenRepository.DELETE_EXPIRED`, tests `RefreshTokenPurgeJobIntegrationTest`,
  `RefreshTokenIndexIntegrationTest`):
  - deletes in batches, each statement its own short transaction (a `@Transactional` one-batch method of the
    table's service, called until a batch comes back short), so no transaction nears DATA-19's statement and lock
    timeouts and no request waits long on its row locks;
  - the batch is `id = ANY (ARRAY(SELECT id … WHERE <column> < :cutoff LIMIT :batchSize FOR UPDATE SKIP
    LOCKED))`: computed once (DATA-29's shape), and instances purging at once take disjoint rows;
  - the cutoff column has its index (partial when only some rows qualify: `WHERE finished_at IS NOT NULL`), and
    the batch is planned on it in the index test (DATA-10);
  - the `@Scheduled` method declares its multi-instance posture (ARCH-12);
  - the retention period, the cron (`zone = "UTC"`) and the batch size are validated settings
    (`app.jobs.<thing>-purge.*`; a period can never be negative), the cutoff taken once per run from the injected
    `Clock`; the test profile switches the cron off (`"-"`) and the tests call the job;
  - the run logs its cutoff and its count, one line per run (OPS-01); the scheduler's own
    `tasks.scheduled.execution` timer has its duration and outcome. A counter of deleted rows only with a consumer
    (OPS-07);
  - rows with children through a foreign key: the children go first (their own batch, or in the parent's
    statement), or the parent stays while a child refers to it (`NOT EXISTS` in the batch); never an
    `ON DELETE CASCADE` added for the purge without a changeset comment saying so (a cascade deletes rows nobody
    counted, inside the parent's batch, and from every other delete of the parent too).
- **By the data's end of life, never by a status change that still matters:** a rotated or revoked refresh token
  stays until it expires, or presenting it again is no longer seen as reuse and no longer ends the family
  (REST-15); a one-time code until its expiry plus the window its attempts are counted over; a challenge until it
  expires; finished work by its finish time, a running row never; a usage row once its day is past every reader.
  The period is the developer's (a business decision, never a guess); only the data's own lifetime gives a default
  (an expired token is useless once it expired).
- **Personal data** (an email, a phone number, an address, a person's tokens): kept as long as anyone needs it, said
  in the setting's comment, never forever (SEC-04; DATA-07: personal data is truly deletable).
- **Detect:** an entity or changeset of an append-only kind (a `*_log`, `*_events`, `*_tokens`, `*_codes`,
  `*_attempts`, `*_deliveries`, `*_history` table; rows with an `expires_at` or `finished_at`) without a purge job or
  an exception; a purge by `revoked_at` or `created_at` where the rows have an expiry; a purge that deletes in one
  statement, without its index, or with the period in code.
- **Not a finding when:** business records the domain keeps (orders, stories, users, todos); a table in
  `{{retentionExceptions}}` with its reason; a table bounded by its key (a row per account, a settings singleton).
- **Severity:** Medium; **High** when callers who aren't signed in can make the table grow (signup codes, sign-in
  challenges, anonymous submissions, webhook deliveries): the disk fills at an attacker's pace.

### OPS-04: A refusal is not an error
- **Check:**
  - 4xx is never logged at ERROR and never counted as an error;
  - error rates count 5xx and timeouts only;
  - alerts target user symptoms (p99, 5xx rate, sustained pool pending), never CPU, heap or GC
    counts.
- **Not a finding when:** a 4xx is an attack signal (WARN plus a dedicated counter).
- **Severity:** Medium.

### OPS-05: One completion line per request; ids everywhere
- **Check:**
  - a single filter logs one line per request (status, duration), at WARN past the slow
    threshold, switched by `app.logging.request-completion.enabled` (it must exist; the switch
    lets it be turned off);
  - request, trace and span ids are in the MDC for HTTP requests **and** for scheduled and async
    runs (a trace per scheduler run, a `TaskDecorator` on executors).
- **Severity:** Medium.

### OPS-06: Outbound clients come from Boot's builders
- **Check:** HTTP clients are built from the injected `RestClient.Builder`, `WebClient.Builder`
  or `RestTemplateBuilder`, so they get observation and `traceparent`. Not a finding:
  `RestClient.create()`, `new RestTemplate()` or a static `HttpClient.newHttpClient()` with
  manual instrumentation and a reason in the profile.
- **Severity:** Medium.

### OPS-07: Feedback loops are closed
- **Check:**
  - every counter or gauge added as a mitigation is scraped and alerted, and the alert has a
    test;
  - counters are registered at zero at startup (so "no data" isn't read as "no failures");
  - the scrape path is independent of the app's auth and database.
- **Severity:** High for data-loss mitigations; Medium otherwise (reported once when no
  monitoring stack exists).

### OPS-08 (profile-optional: the repo holds alert rules): Alerts are tested code
- **Check:** each alert has a traffic floor and a `for:` window, and excludes 4xx. Each new alert
  has a fires and a doesn't-fire test (`promtool test rules`). Dashboards are generated, not
  hand-edited.

### OPS-09: The JVM is diagnosable in its container
- **Check:** the flags in CFG-08. Executors and thread factories name their threads.
- **Severity:** Low / Medium.

### OPS-10: An incident runbook ships with the code
- **Check:** the project has `docs/runbook.md` (reference: the reference application's), and it is
  correct:
  - triage SQL that works: blockers from `pg_blocking_pids(pid)` over `pg_stat_activity`, not a
    hand-written self-join of `pg_locks` (it misses lock kinds and reports wrong pairs); long and
    `idle in transaction` sessions; `max_connections` against what is used;
  - which metrics to read first: pool pending, acquire and usage (CFG-06), bulkhead rejections
    (PERF-03), 503 counters by cause;
  - thread dumps: `jcmd <pid> Thread.dump_to_file -format=json <file>`, two or three a few
    seconds apart;
  - rollback and drain steps: how to take an instance out, roll back a release, and what a failed
    migration needs.

  "Raise the pool" or "restart" is never the first step: it hides the cause and moves the queue
  into the database (instances × pool must stay below `max_connections`).
- **Severity:** Medium (missing, or a triage step that gives wrong answers); Low for a gap in an
  otherwise correct runbook.

---

## Lens 8: Concurrency (CONC)

### CONC-01: Two threads, same data, same instant
- **Check:** for every state change, dedupe, counter, cap or uniqueness rule, check each of:
  - lost updates (DATA-06);
  - check-then-act (DATA-05);
  - caps (DATA-29);
  - child additions (DATA-30);
  - double webhook or job processing;
  - mutable singleton fields;
  - multi-instance pollers and schedulers (`SKIP LOCKED` + lease + fencing).

  Severity follows `{{instances}}`.
- **Not a finding when:**
  - overlapping runs guarded by a try-lock;
  - idempotent deletes;
  - loader-based caches (Caffeine `get(key, loader)`), whereas check-then-put caches are a
    finding.
- **Severity:** High.

---

## Tests (TEST): required with new or changed code

A missing required test is a finding against the changed code: Medium, or High for SEC-related
tests (tests of a refusal, an authentication or authorization check, or a secret; a missing
resilience or failure-injection test is Medium).

### TEST-01: Positive and negative cases, on more axes than the endpoint
- **Check:** every new or changed behaviour covers:
  - the happy path **and** its refusals (400 / 404 / 409 / state);
  - the edges: empty, max size, boundaries, each bounded field **on create and on update** (both
    sides of the bound, on both paths);
  - the list sizes 0, 1 and more than one page;
  - every member of a closed set (TEST-15);
  - the illegal transition and the overrun (TEST-12);
  - an immutable resource (no PUT, PATCH or DELETE) answers 405 to each, tested.

  "An endpoint-shaped coverage map reports full coverage over the only region it can describe."

### TEST-02: Wrong role, no token, other user
- **Check:**
  - a 403 for a lower or sibling role;
  - a 401 with no token;
  - a 404 for another user's or tenant's record (403 when it's visible but the action is
    refused);
  - *profile-optional:* the positive escape-valve test, where a role that may cross the boundary
    really succeeds.

  Preferred: one table-driven test over every endpoint. That table is the suite's largest writer of
  shared data (TEST-07): each row cleans up what it made (an enabled record a later run picks up, a run
  left in progress) and sets any static stub it relies on, never the state the previous row left.

### TEST-03: Hostile input
- **Check:** one test per new free-text input shape, with `<script>alert(1)</script>` and an
  oversized value.

### TEST-04: No leaks in errors
- **Check:** new 409 and 500 paths assert the whole body has no SQL, constraint names, class
  names or stack traces.

### TEST-05: Concurrency
- **Check:** new transitions, dedupe, caps and uniqueness get N identical requests through a
  `CyclicBarrier` / `CountDownLatch` against Testcontainers PostgreSQL, then assert the
  **database invariant** (row count), not just the status codes.
- **Racers on different rows that share one global row**, and **a write guarded by another row's state**
  (checked under that row's lock, DATA-29): crud-gen tests.md rows 13b and 23b say what each asserts (the
  invariant, never "exactly one 200"; "the change is still waiting while the gate holds").

### TEST-06: Query count
- **Check:** new lists and dropdowns assert a constant statement count, for 1 row and for page size + 1
  rows (`FIXTURE-AT-N-EQUALS-ONE`); the counts per list kind are crud-gen tests.md row 21.

### TEST-07: Test quality
- **Check:**
  - one behaviour per test;
  - camelCase name **plus `@DisplayName`**;
  - tests create their own data and restore shared state;
  - no fixed sleeps;
  - no needless extra Spring contexts;
  - exact assertions;
  - no `Strictness.LENIENT`;
  - shared fixtures;
  - text blocks for JSON;
  - no `var`.
- **Shared test database:**
  - a test that changes a global singleton (the one default record, a fixed setting) carries
    `@ResourceLock` on it, sets known values in a `@BeforeEach` and puts the values it found back
    in an `@AfterEach` (golden `uploadPolicyOf` / `setUploadPolicy` / `restoreUploadPolicy`): the
    first so no test depends on the one before, the second so no class that doesn't hold the lock
    meets a test's values (a narrowed upload policy fails every todo create elsewhere);
  - every wait on a latch, a gate or a request the test started has a deadline (`awaitLatch`,
    `awaitResult`, `awaitLockWaiters` against one `lockWaitDeadline()` per gated sequence, which stays
    under `lock_timeout`): a gate that never opens fails the test instead of hanging the build;
  - a fixture that inherits data from neighbouring rows (a new record copying the previous one's
    settings) resets it to known values, so no test depends on the one that ran before; a direct
    `sql(...)` fixture seeds what the record's lifecycle would (its configuration, its counter);
  - a fixture that would join a working set read in bulk (records enabled for a scheduled run, what a
    "run every one" endpoint drains) is created outside it by default (disabled, another status) and
    joins it only in the test that needs it, which restores it: otherwise every later run picks up every
    test's rows;
  - **a list over a shared, company-wide table** (types, employees, anything admin-managed) shows
    every other test's rows: each test scopes its list calls by a filter only its own rows match (a
    fresh parent id, a unique `query` token), never asserting a page's absolute contents or total.
    Row 15 ("another user's record is 404") doesn't apply to records nobody owns; the permission tests
    (row 14, and the generated 403 test: AUTHZ-09) cover who may see them.
- **Shared fixtures and time:**
  - fixtures several classes use (an employee linked to an account, a manager, a role beyond ADMIN)
    live in a project base that extends the test base (`<Project>IntegrationTest`), a role's
    `new<Role>()` beside it (REST-25);
  - a fixture kept longer than one test caches the **account**, never its access token, and signs
    it in again on use (`signIn(account)`): the token lives `app.jwt.access-token-ttl` (at most
    15 minutes, checked at startup), and a run that passes it turns every later call into 401;
  - the test profile lowers bcrypt's cost (`app.security.password-hash-strength: 4`; at production's
    12 every account costs about half a second: a hash at signup, another at login). Even so, a test
    creates one account per role, never one per call.
- **MockMvc traps:**
  - a query value with special characters goes through `.param(...)`: `get(String)` treats the
    string as a URI template and encodes `%25` a second time, so the test sends something else;
  - a 403 test sends a **valid** body: argument binding and validation run before method
    security, so an invalid body answers 400 and the permission check is never reached (AUTHZ-03).
- **Order in unit tests:** a test whose stubs or assertions follow an order the code computes (a sort
  by id, a set's iteration) builds its ids in that order (fixed values), never one `UUID.randomUUID()`
  each: a sort added later makes it pass or fail by chance (a strict stub met in another order).
- **Dates:** a date-dependent test computes "today" once, or uses a settable clock; it never calls
  `LocalDate.now()` twice across UTC midnight, and never hard-codes a year that the data will pass.

### TEST-08: A status code is not a reason (`STATUS-IS-NOT-A-REASON`)
- **Check:** every negative test asserts *why* it was refused (the `errorCode`, or the field in
  `errors`: its `pointer` or `parameter`, ERR-07), not only the status. A test aimed at a service rule must not be stopped earlier by
  bean validation: a status-only test passes with value validation switched off.
- **Severity:** Medium (High for SEC checks).

### TEST-09: Prove the guard can fail
- **Check:** a regression test goes red against the code without the fix. For a **hand-written fix**
  the diff gives the hunk: revert it, run the test and expect red; for a layered check, switch the layer
  off. "A regression net nobody has seen fail is just an assertion." **Generated code** carries no such
  duty per entity (a red run leaves nothing a reviewer can check): its guards mirror golden tests, proven
  red in the reference, and the generation report names which golden test each one mirrors; the review
  compares the shapes.
- **A layered guard** (a row lock, `@Version` and a conflict retry, each enough on some path) stays green
  with one layer off, by design: prove each layer with a test the others don't cover (the retry with a
  forced conflict, the lock with a gate), or the red-proof names the layers switched off together and why
  one alone stays green.
- **Detect:** an assertion that holds whatever the code does: two bcrypt hashes of one value differ
  (every `encode` is salted: compare with `matches`), a fresh random id is unique.

### TEST-10: Setup that silently failed (`SETUP-THAT-SILENTLY-FAILED`)
- **Check:** every fixture built through the API asserts its own 2xx, or goes through a helper
  that throws: a fixture posted with a wrong field name gets 400, and the test passes on nothing.
- **Also:** with `auto-commit: false` on the pool (CFG-01), a direct `jdbc.update` fixture outside a
  transaction is rolled back when its connection returns: the test silently runs on data that isn't
  there. Fixture writes go through a helper that commits (`sql(...)` on the test base).
- **Also:** an order test whose fixtures already come out in the expected order passes with the
  sort ignored: fixtures must give a different result under every order and direction.

### TEST-11: A collection needs a bound (`COLLECTION-WITHOUT-A-BOUND`)
- **Check:** `contains(x)` is never the only assertion on a list endpoint. With a shared database,
  assert at least N of your own rows and the cap, not exact totals. Per list kind (the page records
  differ, so a rule for one names fields the other doesn't have):
  - **offset** (crud-gen tests.md row 16b): `pageInfo.size` is at most the cap, and `pageInfo` (number,
    totalElements, hasNext, hasPrevious) is correct;
  - **cursor** (row 16): a batch holds at most the asked size, and `pageInfo` is `{nextCursor, hasMore}`
    alone: `hasMore` false and no `nextCursor` on the last batch.

### TEST-12: One step past the edge (`ONE-STEP-PAST-THE-EDGE`, `FLAG-AND-FIELD-DIVERGE`)
- **Check:**
  - pagination has an edge test. **Offset** (tests.md row 16b): the page past the last one has an empty
    `response`, `pageInfo.hasNext` false, `pageInfo.hasPrevious` true. **Cursor** (row 16): the walk ends
    on a batch with `hasMore` false and no `nextCursor`, every row seen once;
  - state machines have an illegal-transition test;
  - response flags (`pageInfo.hasNext` / `hasPrevious`, or a cursor's `hasMore`) come from the
    same data as the token or page they promise.

### TEST-13: Tests must not code around defects
- **Check:** a known bug is a red or expected-failure test with a task id. Never:
  - a loop guarded on the defect condition (`GUARD-THAT-HIDES-THE-BUG`);
  - a loosened assertion or schema (`SCHEMA-LOOSENED-TO-GREEN`);
  - `@Disabled` without a ticket (`FINDING-WITHOUT-A-TASK`).

### TEST-14: Real payloads; producer and consumer together
- **Check:**
  - fixtures for external wire shapes are captured from real payloads, with a provenance comment
    (`INVENTED-FIXTURE`);
  - a producer's real output is fed to its real consumer in one test
    (`PRODUCER-AND-CONSUMER-TESTED-APART`).

### TEST-15: Every member of a closed set; one payload, one parser
- **Check:**
  - features taking an enum value test every member through the real seam (`@EnumSource`,
    `SAMPLE-OF-A-CLOSED-SET`);
  - every hand-built `ObjectMapper` / `JsonMapper` reading what the web layer reads is configured
    the same way;
  - Jackson 2 and Jackson 3 are not mixed.

### TEST-16: An absence needs a presence
- **Check:** every absence assertion (`isEmpty`, `doesNotContain`, `never()`, `doesNotExist`)
  follows a presence assertion that proves the producing path ran.
- **Severity:** High for leak and authorization tests.

### TEST-17: The test database is the production database (`SAME-ENGINE-DIFFERENT-DATA`)
- **Check:** the Testcontainers image matches production's family (glibc, not `-alpine`).
  Behaviour that depends on time-zone names, collation or ICU also gets a pure-function test over
  a written-down input set.

### TEST-18: Failure injection and small-pool tests
- **Check:** each outbound dependency and each safeguard has a test of its failure mode:
  - dependency down or slow → 503, nothing stored;
  - a hung job doesn't block the others;
  - the scrape answers while the pool is full;
  - poison items reach a terminal state;
  - a **small pool** (e.g. 5) under concurrent slow requests answers 503, never 401 or 500;
  - the timeout order (DATA-19);
  - JSON extra-key loads (DATA-27).

  `SyncTaskExecutor` swaps don't prove connection behaviour.

### TEST-19 (profile-optional: multi-repo): One definition per shared concept (`TWO-DEFINITIONS-ONE-WORD`)

### TEST-20 (profile-optional: full-stack): E2E or not done
- **Check:** a change to a screen's flow names a green cross-stack journey (`E2E-OR-NOT-DONE`).
