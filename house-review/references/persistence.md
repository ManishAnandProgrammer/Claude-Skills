# Persistence rules (DATA-xx)

Sources:
- `Soft-Deletes-Are-A-Pitfall.md`, `AFTER_COMMIT-REQUIRES_NEW-Pool-Deadlock.md`;
- DynamicForm `notes/transactional-deep-dive.md`;
- DynamicForm reports: `hibernate-persistence-context-and-fetching-review.md`,
  `database-concurrency-locking-and-transaction-review.md`, `erp-practices-2026-11-09.md`;
- the decisions in DESIGN.md.

Configuration values are in `config-baseline.md`; the 503 contract is in `error-contract.md`.

Core idea: **transaction scope is connection scope.** A transaction holds a pooled connection for
its whole duration, and a pool runs dry long before CPU or the database does.

---

## Transactions and connections

### DATA-01: No connection held during slow work
- **Check:** no remote call (HTTP, SMTP, SMS, LLM, S3), file or XLSX processing, sleep or retry
  backoff inside a transaction.
- **Detect:** `@Transactional` methods, and class-level ones without `NOT_SUPPORTED` on the
  method, that call a client, notifier, `HttpClient` / `RestClient` / `WebClient`,
  `JavaMailSender`, an SDK, or a retry-decorated call. Follow self-invocations.
- **Fix:**
  1. read in a short transaction;
  2. call with no transaction;
  3. write in a second short transaction that re-reads and re-checks the version or status.

  Use `Propagation.NOT_SUPPORTED` on an orchestrating method **whose caller holds no
  transaction**, or a `TransactionTemplate`. Save a "pending" row before the call, so a fast
  callback finds it.
- **Severity:** High.

### DATA-02: AFTER_COMMIT work
- **Check:** the deadlock is database work (`REQUIRES_NEW`) in an AFTER_COMMIT listener **without
  `@Async`**. At commit the original connection is still held, the listener takes a second one,
  and above pool/2 concurrent requests it deadlocks.

  `@Async` + `@TransactionalEventListener(AFTER_COMMIT)` + `REQUIRES_NEW` is the user's
  documented fix (Spring 6.1+ requires `REQUIRES_NEW` / `NOT_SUPPORTED` on transactional
  listeners).

  Synchronous I/O in a `TransactionSynchronization.afterCommit` callback also holds the
  connection and delays the response.
- **Symptoms:** error waves at exactly `connection-timeout` intervals; `idle in transaction` in
  `pg_stat_activity`.
- **Also:**
  - correctness must not depend on listener timing;
  - side effects that must happen get a durable record (an outbox or delivery row, plus a
    poller). An `@Async` listener alone loses the work on a crash (REL-06).
- **Severity:** High for the deadlock shape; Medium for synchronous afterCommit I/O.

### DATA-03: Nested REQUIRES_NEW
- **Check:** `REQUIRES_NEW` inside an open transaction holds **two** connections per request.
  Also a finding:
  - `REQUIRES_NEW` used "for a clean slate";
  - `REQUIRES_NEW` in a loop or a deep chain (one more connection per level).

  Legitimate use: audit or delivery records that must survive the caller's rollback.
- **Fix:** sequential short transactions, or `noRollbackFor` in one transaction.
- **Severity:** Medium (High on hot or public paths).

### DATA-04: Transaction defaults
- **Check:**
  - every write is transactional. Under a class-level `readOnly = true` default each write method
    has its own `@Transactional`, including one that only writes through another service. A
    method-level annotation **replaces** the class-level one; attributes are not merged;
  - `spring.jpa.open-in-view: false`;
  - `@Retryable` + `@Transactional`: the retry is outermost and the caller holds no transaction,
    so each attempt gets a fresh one; a test proves it;
  - an `@Async` method making several repository calls has its own `@Transactional` (otherwise
    each call is a separate transaction, with no atomicity).
- **Severity:** High for a write that is silently not flushed; Medium otherwise.

### DATA-15: Rollback rules and swallowed persistence exceptions
- **Check:**
  - a `@Transactional` method that can throw a checked exception declares
    `rollbackFor = Exception.class` (by default a checked exception commits);
  - no catch inside a transaction swallows a persistence or flush exception and carries on. On
    PostgreSQL the transaction is aborted, and the commit later throws
    `UnexpectedRollbackException`, which hides the cause;
  - no aspect or `finally` block runs SQL after an exception in the same transaction (incident
    `VERSION-RACE-ANSWERS-500`: a 409 became a 500).
- **Not a finding when:** the catch calls `setRollbackOnly()` and rethrows, or uses its own
  savepoint or transaction.
- **Severity:** High.

### DATA-16: The proxy must actually apply
- **Check:** `@Transactional`, `@Async` and `@Retryable` only work on non-private, non-final
  methods of non-final, Spring-managed classes (not `new`, not Lombok `@Value`), called through
  the proxy. Several boundaries in one flow: a separate bean or `TransactionTemplate`, never
  self-injection.
- **Detect:** private or final annotated methods; `this.` calls to annotated methods; `@Lazy`
  self-injection; `new XService(` in production code.
- **Severity:** High (the write is silently not transactional).

### DATA-17: Filters, security and controllers open no transactions
- **Check:**
  - no `@Transactional` on controllers;
  - code in the servlet or security filter chain opens no transaction on the cache-hit path;
  - an infrastructure failure there answers **503, never 401** (incident F-080: under pool
    saturation 1,004 of 1,275 requests answered 401 and the UI signed everyone out);
  - `DispatcherType.ERROR` is permitted in the `SecurityFilterChain`, so an escaped exception
    isn't re-dispatched to `/error` and answered 401.
- **Severity:** High.

### DATA-18: Transient database failures answer 503 + Retry-After
- **Check:** pool exhaustion, statement timeout, lock timeout and an unreachable database are
  classified by walking the **cause chain and SQLSTATE**, and answered 503 with a stable code and
  a jittered `Retry-After` (`error-contract.md` ERR-03). Never the generic 500.
- **Severity:** High (`POOL-EXHAUSTION-IS-500`: clients retry 500s wrongly, or not at all).

### DATA-19: Server-side timeouts on every app connection, in the right order
- **Check:**
  - `statement_timeout`, `lock_timeout` and `idle_in_transaction_session_timeout` are set for the
    app pool (pgjdbc `options`, or the role);
  - `statement_timeout` is **strictly smaller** than `socketTimeout`, so the server cancels first
    and the connection survives. Incident PA-16: with both at 30 s they raced and 29 of 35
    requests answered 500;
  - Liquibase isn't limited;
  - hot paths use a short default, and exports raise it locally (`SET LOCAL statement_timeout`);
  - a test holds the order.
- **Severity:** High if they are missing; Medium for the order alone.

### DATA-20: readOnly and explicit attributes take the connection at begin
- **Check:**
  - with `provider_disables_autocommit=true`, a plain `@Transactional` borrows the connection at
    the first SQL statement;
  - `readOnly = true`, any explicit `isolation` and any `timeout` borrow it **at begin**. So a
    method that may answer from a cache, or only calls out, must not run under a `readOnly`
    transaction: use `Propagation.SUPPORTS` (cache first) or `NOT_SUPPORTED`;
  - under a class-level `readOnly` default these methods need that carve-out.
- **Severity:** Medium (High on per-request paths: the cache stops protecting the pool).

### DATA-21: Isolation levels
- **Check:**
  - the default isolation is set once on the pool, never repeated per method;
  - no `READ_UNCOMMITTED` (PostgreSQL silently runs READ COMMITTED);
  - `REPEATABLE_READ` / `SERIALIZABLE` only with a retry on SQLSTATE `40001`, in a fresh
    transaction each time, and never on hot paths;
  - point-in-time exports are the legitimate `REPEATABLE_READ` case.
- **Severity:** Medium (High without the retry).

### DATA-22: Propagation pitfalls
- **Check:**
  - `NOT_SUPPORTED` called **from inside** a transaction suspends it but keeps its connection, so
    database work in the callee takes a second one;
  - `NESTED` needs `setNestedTransactionAllowed(true)`;
  - `MANDATORY` methods are not reachable from schedulers or async code;
  - a `REQUIRES_NEW` callee doesn't read the caller's uncommitted rows;
  - `NEVER` is the recommended defensive marker for methods that must stay outside a
    transaction.
- **Severity:** Medium (High when it doubles connections on a hot path).

### DATA-23: Transactional events
- **Check:**
  - an event published **outside** a transaction to a `@TransactionalEventListener` without
    `fallbackExecution = true` is silently dropped;
  - with `fallbackExecution = true` the listener fires at once, so it can't assume committed
    data;
  - a plain `@EventListener` runs inside the publisher's transaction;
  - the order between `@Async` listeners isn't guaranteed.
- **Severity:** High for a dropped critical event.

## Entities, queries and loading

### DATA-24: Bulk JPQL and the persistence context
- **Check:**
  - a `@Modifying` query run after entities were loaded uses `clearAutomatically = true` (and
    `flushAutomatically` when pending changes must land first);
  - no entity loaded before it is mutated afterwards: it is detached, so the change is silently
    dropped;
  - bulk updates bypass entity methods, auditing, `@Version` and events, so the query sets
    `version`, `updated_at` and the status guards itself;
  - a conditional bulk update checks its row count and answers 409 on 0.
- **Severity:** High.

### DATA-25: Write paths load only what they change; persist vs merge
- **Check:**
  - a write that changes one row loads that row, not the whole aggregate read-write. Measured:
    21.6 ms vs 8.8 ms, and about 16× the allocation (F-083);
  - no `save()` / `merge()` on an already-managed entity;
  - new rows with **application-assigned ids** (UUIDv7, TSID) use `persist`, or an `isNew()`
    signal (`Persistable`, null `@Version`), not `save()`, which merges: an extra SELECT
    (`SAVE-MERGE-EXTRA-SELECT`);
  - N-row changes such as reorder use one set-based update.
- **Not a finding when:** the aggregate root must be loaded to enforce an invariant (load it,
  don't merge it). The profile may keep `saveAndFlush`-before-mapping as a documented convention
  (to return `version` and `updatedAt`), with minimal work after the flush.
- **Severity:** Medium (High when the cost grows with an unbounded child count).

### DATA-26: Collection fetching and pagination
- **Check:**
  - at most **one** collection fetch-joined or EntityGraph'd per query (two Sets: a Cartesian
    product; two Lists: `MultipleBagFetchException`);
  - **no collection fetch on a list page.** Hibernate 7.4 no longer paginates such a query in
    memory: it pages the roots in a derived table and joins the children, so
    `fail_on_pagination_over_collection_fetch` never fires (measured 2026-11-13). The cost moved:
    every child of the page comes back (20 lists × 1,000 todos: 111.6 ms against 7.2 ms for two
    steps). Child data on a list page is an aggregate subquery in the projection, or two steps
    (a page of ids, then one grouped child query);
  - lazy relations in lists are covered by batch fetching;
  - list pages use projections, not entities (DATA-11).
- **Severity:** Medium (High on a growing table).

### DATA-27: JSON columns use an explicit format mapper
- **Check:** when any attribute is `@JdbcTypeCode(SqlTypes.JSON)`, Hibernate's JSON format mapper
  is configured explicitly:
  - tolerant of unknown properties;
  - ISO dates;
  - no classpath module discovery.

  Otherwise Hibernate builds its own strict Jackson 2 mapper beside Jackson 3, and one extra key
  in one stored document makes the row unloadable. DynamicForm didn't boot (F-082). Each stored
  JSON type gets a test that loads a document with an extra key. Ordered data in `jsonb` is
  stored as a list, never as object keys (`jsonb` re-sorts keys; `DISPLAY-OPTIONS-ORDER-LOST`).
- **Also (HelpBook):** a JSON column's content is a typed record where possible. Where a
  `Map` is unavoidable, its keys are named constants (one class of keys, never repeated string
  literals), and a closed set of values is an enum, so a typo can't write a key nobody reads.
- **Severity:** High on Jackson 3 projects.

### DATA-28: No heavy data work at startup
- **Check:** backfills and bulk updates run as a migration or a batched one-off job, never in
  `ApplicationRunner`, `CommandLineRunner`, `@PostConstruct` or `SmartInitializingSingleton`.
  They grow with the data until the app can't boot (F-081: a 38 s backfill).
- **Severity:** High.

### DATA-29: Locks, pollers and caps
- **Check:**
  - advisory locks are transaction-scoped (`pg_advisory_xact_lock`) with a 64-bit key.
    Session-level locks leak on pooled connections;
  - a "max N per parent" cap locks the parent before counting, or uses a constraint (incident R5:
    a cap of 5, and 6 created);
  - multi-row locks are taken in a consistent order (`ORDER BY id`, `order_updates=true`);
  - pollers claim with `FOR UPDATE SKIP LOCKED` plus a lease in a short claim transaction, do the
    remote work between transactions, and use fencing when several instances run;
  - `lock_timeout` bounds every wait.
- **Severity:** High.

### DATA-30: Child additions don't bump the parent version
- **Check:** uniqueness checked in memory on a loaded collection isn't safe: adding through the
  inverse side doesn't bump the parent's `@Version` (incident R4: 10 of 10 duplicates). Use a
  constraint, or `OPTIMISTIC_FORCE_INCREMENT` / `PESSIMISTIC_WRITE` on the parent. "max + 1"
  numbering needs a unique constraint plus a parent lock, so the loser gets 409.
- **Severity:** High.

### DATA-31: Bulk imports keep memory bounded
- **Check:**
  - import loops `flush()` + `clear()` every `batch_size` rows (or use a stateless session);
  - uploads are parsed streaming (no whole `XSSFWorkbook`);
  - the import doesn't hold one transaction for the whole file.
- **Severity:** High (out of memory).

## Integrity and data design

### DATA-05: Check-then-act needs the database
- **Check:** "exists? then insert", "anything in progress? then start" and "count then insert"
  need a (partial) unique index with the violation translated to **the domain code**, or
  `INSERT … ON CONFLICT`, or a lock. Idempotent operations (pin, follow) use
  `ON CONFLICT DO NOTHING` and answer 2xx, not 409 (F-084).
- **Unique text (HelpBook):** a unique `text` column gets a unique **index on
  `lower(trim(col))`**, so case and surrounding spaces don't make a different value ("Groceries" vs
  " groceries", two accounts for one email). A nullable one is partial: `WHERE col IS NOT NULL`.
  The code normalises the same way before writing and looking up, so the index is used.
- **Severity:** High.

### DATA-06: Optimistic locking
- **Check:**
  - entities carry `@Version`;
  - bulk updates bump `version` and `updatedAt` (DATA-24);
  - user actions on reviewed records take the version the user saw (409 when stale);
  - user 409s are **not** retried server-side; only machine writers that re-read state may retry;
  - background writers re-check status under a lock.
- **Severity:** High.

### DATA-07: Deletes
- **Check:**
  - no soft-delete flags (`deleted`, `deleted_at`, `@SQLRestriction`, `@Where`);
  - business states are a status enum;
  - true removal is a hard `DELETE`, with an archive table when recovery or audit needs it;
  - personal data is truly deletable;
  - a "hide" or "restore" flag that only filters lists is a disguised soft delete.
- **Not a finding when:**
  - a documented legal retention (`{{retentionExceptions}}`); then the retained rows are
    partitioned;
  - `@SQLRestriction` used for **scoping**, not deletion (e.g. a media collection filtered by
    `purpose`).
- **Severity:** Medium (High when deleted rows leak or block unique keys).

### DATA-08: Change history (PostgreSQL triggers)
- **Check:**
  - tables in `{{auditedTables}}` get history from one generic trigger function writing to an
    append-only `audit_log` (old and new rows as `jsonb`);
  - the app passes the acting user with `set_config('app.user_id', …, true)`: transaction-local,
    **once per transaction**, never at session level on pooled connections (F-075: 61% of all
    statements were redundant `set_config` calls);
  - audit tables are for compliance only, never queried by business logic.
- **Severity:** Medium.

### DATA-09: Primary keys are time-ordered
- **Check:** new tables use time-ordered ids per `{{idStrategy}}` (UUIDv7 or TSID).
  - For JPA entities, generate in the application (Hibernate 7's `UuidVersion7Strategy` via
    `@UuidGenerator`), not with a database `DEFAULT uuidv7()`: a database-generated id must be
    read back after each insert, which costs JDBC insert batching;
  - application-assigned ids are inserted with `persist`, not `save` (DATA-25);
  - the id is never updatable: `@Column(updatable = false)` (HelpBook).

  Random UUIDv4 is a finding.
- **Severity:** Medium.

### DATA-10: Indexes
- **Check:**
  - every foreign key column is indexed;
  - **one index per offered list order**, leading with the scope columns (owner, tenant), then the
    sort keys, **ending with the id** tie-breaker; filtered columns covered (composite or partial);
  - every sort has a unique tie-breaker;
  - **a keyset sort key is never null.** A nullable column (a due date) sorts through a stored
    generated column (`coalesce(due_date, date '9999-12-31')`) that the index and the keyset use;
  - no exact `COUNT(*)` over large filtered sets on every page (PERF-04).
- **Severity:** Medium (High on unbounded tables).

### DATA-11: Query shape
- **Check:**
  - no loading of unbounded data to count, average or filter in Java;
  - no N+1;
  - no `findAll()` behind an endpoint;
  - count queries are skipped when the page isn't full; cursor lists never count;
  - **list pages and dropdowns are projections** (`construct(Row.class, …)` of only the columns they
    show), never entities: measured 22 ms against 7 ms per page (2026-11-13);
  - **growing lists are keyset-paged** (`after` cursor); OFFSET reads every skipped row (20,020 rows
    for row 20,000, against 20 with keyset).
- **Severity:** High for unbounded loads; Medium for N+1.

### DATA-12: Columns fit the data
- **Check:**
  - **string columns are `text`** (PostgreSQL: `varchar(n)` adds no speed, only a second length
    contract that drifts); entities carry no length constants;
  - **the length contract is the request:** every String in a request record carries
    `@MaxLength("<key>")` with the number in `app.limits.text` (configuration, not code);
  - never `@Lob` (on PostgreSQL that's an `oid` large object).
- **Severity:** **High** for a request String with no length limit, unless the author justifies it
  (ask; a justified exception is an accepted deviation); Medium for a `varchar(n)` column.

### DATA-13: Migrations
- **Check:**
  - every entity change has a new changeset with a rollback;
  - an applied changeset is never edited;
  - sequences and seed rows needed at runtime are created in migrations;
  - foreign keys are named `fk_<table>_<column>`;
  - Liquibase migrates the app's own database URL;
  - *profile-optional:* the app uses a DML-only role, and Liquibase the owner role.
- **Severity:** High for an edited applied changeset; Medium otherwise.

### DATA-14: Display codes come from sequences
- **Check:** human-readable codes under a unique constraint come from a database sequence, never
  from short random strings.
- **Severity:** High.
