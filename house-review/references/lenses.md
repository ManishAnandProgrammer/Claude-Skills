# The eight lenses (SEC / PERF / REL / OPS / CONC / TEST)

Sources:
- `eight-lenses.md`;
- DynamicForm `notes/testing-blind-spots.md`;
- DynamicForm `.claude/skills/monitoring`;
- DynamicForm `playbooks/jvm-observability-playbook.md`;
- DynamicForm `reports/resilience-review-2026-11-09.md`.

"Tests verify 'does the feature work as designed?'; they don't verify 'what does the code allow
that it shouldn't?'"

Lenses per mode (SKILL.md, Step 1):
- **diff and uncommitted:** 1, 2, 6, 7, plus 8 when concurrency-related code is touched;
- **path and whole codebase:** all eight.

Lens 1 is the build and tests plus TEST-01; lens 6 is rest-api.md and error-contract.md; lens 7
is code-smells.md, java.md and architecture.md.

---

## Lens 2: Security (SEC)

### SEC-01: Who must NOT be able to call this?
- **Check:** for every endpoint, name who must be refused (role, ownership, account state) and
  verify the code refuses them.
- **Severity:** Critical when a refusal is missing.

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
  - **heap dumps and JFR files are secrets**: private paths, never in images or logs.
- **Severity:** High (Critical for login codes in logs).

### SEC-05: Injection and hostile input
- **Check:**
  - no SQL built from strings;
  - hostile text follows the REST-10 policy;
  - validators actually handle the field's type;
  - no internals in error bodies;
  - strict JSON input (CFG-05).
- **Severity:** Critical (SQL) / High.

---

## Lens 3: Performance (PERF)

### PERF-01: Hot paths answer four questions
- **Check:** every change to an endpoint in `{{hotPaths}}` (or any per-request path) states:
  1. how many SQL statements per request;
  2. new allocation or retained collections (GC pressure);
  3. blocking or CPU-bound work on virtual threads. CPU-bound work starves carriers. On JDK 24+
     `synchronized` no longer pins, but native frames and class initialisation still can;
  4. **which metric would show a regression in production.**

  Shared expensive reads are cached, and a cache hit takes no connection (`SUPPORTS`, DATA-20).
- **Severity:** Medium (High when it is unbounded).

### PERF-02: Connection pool
- **Check:**
  - nothing holds connections longer than needed (DATA-01..03, 20);
  - the pool and server timeouts follow `config-baseline.md` (CFG-01);
  - exhaustion shows up as pending > 0 sustained, or hold time much greater than query time
    (usage histogram).
- **Severity:** High.

### PERF-03: Under virtual threads, bound concurrency explicitly
- **Check:** with virtual threads, `server.tomcat.threads.max` bounds nothing (k6: 1,995 requests
  waiting on the pool). Admission control does the bounding:
  - a Resilience4j **semaphore** bulkhead per route group (about 2× the pool for request groups,
    small for reports and exports), or Spring 7 `@ConcurrencyLimit`;
  - a short max-wait, then 503 `SERVER_BUSY` + `Retry-After`;
  - a rejected-count metric per group;
  - placed before the security chain; the actuator is never capped.
- **Severity:** High on heavy or public endpoints.

### PERF-04: Long reads and exports
- **Check:**
  - exports read in bounded keyset pages, one short read-only transaction per page, streaming
    between transactions;
  - CPU work and uploads run with no transaction;
  - exact counts over large sets are capped ("10,000+") and computed once per filter set
    (F-079: p50 of 3.4 s from exact counts);
  - export and report row caps exist.
- **Severity:** Medium (High on growing tables).

---

## Lens 4: Reliability (REL)

### REL-01: Outbound calls
- **Check:** every outbound call has:
  - an **explicit** connect and request timeout (never the library default; SDKs set an API-call
    timeout greater than the attempt timeout);
  - retries only for retryable failures (network errors, timeouts, 5xx, 408, 429), with backoff,
    inside the deadline budget (REL-04);
  - a circuit breaker where it pays off.

  Clients are built from Boot's builders (OPS-06), configured through the resilience library's
  registry, never inside a transaction. For user-defined destinations: cap concurrency per
  destination, bound the response size, honour `Retry-After`.
- **Not a finding (no breaker needed):** the only database (Hikari's wait + 503 already fails
  fast); low-volume calls (a timeout plus a quota is enough); paths where an open breaker would
  turn a failure into silent data loss.
- **Severity:** High.

### REL-02: Failure states terminate
- **Check:**
  - no flow can leave a record stuck in progress: timeouts move it to a terminal state;
  - retries back off and count attempts, and the Nth failure is DEAD;
  - claim queries can't be starved by orphans;
  - "replay" really re-does the work;
  - finished rows are purged (OPS-03).
- **Severity:** High.

### REL-03: Dependency outages degrade the feature, not the app
- **Check:** fail-open or fail-closed is decided and documented per dependency. A fail-open
  fallback to the database is bounded, or it moves the outage onto the pool. Health is covered in
  REL-14.
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
- **Severity:** Critical when user data is lost on a core path; otherwise High.

### REL-07: No inert limits
- **Check:** every configured limit is really applied, and no comment claims a limit that
  doesn't exist. Under virtual threads, `server.tomcat.threads.max` and
  `spring.task.scheduling.pool.size` do nothing. Verify against the framework source.
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
  bounded. Don't recommend lowering `max-connections` or using platform threads for Tomcat.
- **Severity:** High.

### REL-10: Outages answer 503 + Retry-After, never 500 or 401
- **Check:** infrastructure exceptions map centrally to 503 + a jittered `Retry-After`:
  - database (DATA-18);
  - SDK client errors and 5xx;
  - `ResourceAccessException`;
  - `CallNotPermittedException`;
  - AI provider unavailable.

  A failure inside the security filter chain answers 503, not 401 (DATA-17).
- **Severity:** High (filter → 401); Medium (500 instead of 503).

### REL-11: Writes that clients retry are idempotent
- **Check:** a POST a client may retry after a 503 or timeout has an `Idempotency-Key` or a
  natural or client-chosen key, and replays the stored result. "A `JDBCConnectionException`
  during COMMIT… maps to 503, but the commit may have landed."
- **Severity:** Medium (High on payment or submission paths).

### REL-12: Paid or quota-bearing calls
- **Check:** a call that costs money (LLM, SMS) has:
  - a burst limit **and** a total budget per account;
  - usage recorded from the raw response *before* parsing;
  - retries counted inside the budget and the deadline.
- **Severity:** High.

### REL-13: Graceful shutdown
- **Check:** `server.shutdown: graceful`; `timeout-per-shutdown-phase` covers the normal request and
  job, and the container's stop grace is longer than it. Executors wait with a bound. Claims and
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

### OPS-03: Growth
- **Check:** tables that only grow (tokens, codes, outbox, delivery logs, history) have retention
  or purge jobs.
- **Severity:** Medium.

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

---

## Lens 8: Concurrency (CONC)

### CONC-01: Two threads, same data, same instant
- **Check:** for every state change, dedupe, counter, cap or uniqueness rule, consider:
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
  - the edges: empty, max size, boundaries;
  - the list sizes 0, 1 and more than one page;
  - every member of a closed set (TEST-15);
  - the illegal transition and the overrun (TEST-12).

  "An endpoint-shaped coverage map reports full coverage over the only region it can describe."

### TEST-02: Wrong role, no token, other user
- **Check:**
  - a 403 for a lower or sibling role;
  - a 401 with no token;
  - a 404 for another user's or tenant's record (403 when it's visible but the action is
    refused);
  - *profile-optional:* the positive escape-valve test, where a role that may cross the boundary
    really succeeds.

  Preferred: one table-driven test over every endpoint.

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

### TEST-06: Query count
- **Check:** new paged lists assert a constant statement count, with a fixture **larger than one
  page** (`FIXTURE-AT-N-EQUALS-ONE`).

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

### TEST-08: A status code is not a reason (`STATUS-IS-NOT-A-REASON`)
- **Check:** every negative test asserts *why* it was refused (the `errorCode`, or the field in
  `failed`), not only the status. A test aimed at a service rule must not be stopped earlier by
  bean validation. Incident: 36 tests passed with value validation switched off.
- **Severity:** Medium (High for SEC checks).

### TEST-09: Prove the guard can fail
- **Check:** a regression test goes red against the code without the fix. In diff mode, revert the
  fix hunk, run the test and expect red; for a layered check, switch the layer off. "A regression
  net nobody has seen fail is just an assertion."

### TEST-10: Setup that silently failed (`SETUP-THAT-SILENTLY-FAILED`)
- **Check:** every fixture built through the API asserts its own 2xx, or goes through a helper
  that throws. Incident: six specs posted `formFieldIds` instead of `fieldIds`, got 400, and
  passed.
- **Also:** with `auto-commit: false` on the pool (CFG-01), a direct `jdbc.update` fixture outside a
  transaction is rolled back when its connection returns: the test silently runs on data that isn't
  there. Fixture writes go through a helper that commits (`sql(...)` on the test base).
- **Also:** an order test whose fixtures already come out in the expected order passes with the
  sort ignored: fixtures must give a different result under every order and direction.

### TEST-11: A collection needs a bound (`COLLECTION-WITHOUT-A-BOUND`)
- **Check:** `contains(x)` is never the only assertion on a list endpoint: also check that
  `pageInfo.size` is at most the cap, and that `pageInfo` (number, totalElements, hasNext,
  hasPrevious) is correct. With a shared database, assert at least N of
  your own rows and the cap, not exact totals.

### TEST-12: One step past the edge (`ONE-STEP-PAST-THE-EDGE`, `FLAG-AND-FIELD-DIVERGE`)
- **Check:**
  - pagination has an overrun test: an empty `response`, `pageInfo.hasNext` false,
    `pageInfo.hasPrevious` true;
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
