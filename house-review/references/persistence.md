# Persistence rules (DATA-xx)

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
    `VERSION-RACE-ANSWERS-500`: a 409 became a 500);
  - any exception, unchecked included, that crosses a joined `@Transactional` boundary rolls back
    the whole transaction. A write that must persist although the request is refused (a used
    token's delete, a failed-attempt count) takes `noRollbackFor` on that exception or its own
    transaction (DATA-03), and a test asserts the row, not only the status: a refused refresh
    rolled back its own delete, so the token kept working.
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

### DATA-18: merged into REL-10

### DATA-19: Server-side timeouts on every app connection, in the right order
- **Check:**
  - `statement_timeout`, `lock_timeout` and `idle_in_transaction_session_timeout` are set for the
    app pool (pgjdbc `options`, or the role);
  - `statement_timeout` is **strictly smaller** than `socketTimeout`, so the server cancels first
    and the connection survives. Incident PA-16: with both at 30 s they raced and 29 of 35
    requests answered 500;
  - **migrations get their own `lock_timeout` (about 5 s) and no `statement_timeout`.** Liquibase
    opens its own connection, so the app pool's `options` don't reach it. An `ALTER TABLE` waiting
    for a lock blocks every later query on that table behind it, and a rolling deploy migrates while
    old instances still serve traffic: without a lock timeout one waiting migration stops the table.
    A long statement that already holds its lock (an index build, a backfill) must not be cut off,
    so no statement timeout. Set it on the migration connection (reference:
    `config/LiquibaseConnectionConfig`, Liquibase's own data source with `-c lock_timeout`, from
    `app.liquibase.lock-timeout`), or `SET lock_timeout` at the top of each changeset that alters a
    busy table; a migration that hits it fails and is re-run;
  - hot paths use a short default, and exports raise it locally (`SET LOCAL statement_timeout`);
  - a test holds the order.
- **Severity:** High if they are missing (the migration lock timeout included); Medium for the
  order alone.

### DATA-20: Transactions borrow their connection at the first statement
- **Check:** the pool is wrapped in Spring's `LazyConnectionDataSourceProxy` (CFG-01, reference
  `config/LazyConnectionConfig`), so every transaction borrows at its first SQL statement. A method
  that answers from a cache, or returns before any SQL, holds no connection, even under the
  class-level `readOnly` default.
- **Without the proxy:** `readOnly = true` and an explicit `isolation` borrow **at begin**, because
  Spring's `HibernateJpaDialect` prepares the connection then. A plain transaction and one with a
  `timeout` stay lazy (measured 2026-11-14, Spring 7.0 / Hibernate 7.4). In such a project, a
  cache-first method under a `readOnly` class default needs `Propagation.SUPPORTS` or
  `NOT_SUPPORTED`.
- **With the proxy,** both attributes still reach PostgreSQL when the connection is borrowed
  (`transaction_read_only = on`, the isolation level), and are reset afterwards.
- **Severity:** Medium when the proxy is missing (High on per-request paths that answer from a
  cache: the cache stops protecting the pool).

### DATA-21: Isolation levels
- **Check:**
  - the default isolation is set once on the pool, never repeated per method;
  - no `READ_UNCOMMITTED` (PostgreSQL silently runs READ COMMITTED);
  - `REPEATABLE_READ` / `SERIALIZABLE` only with a retry on SQLSTATE `40001`, in a fresh
    transaction each time, and never on hot paths;
  - point-in-time exports are the legitimate `REPEATABLE_READ` case;
  - reads that must agree (a total and its parts, a check and the rows it vouches for) come from
    one statement: under READ COMMITTED each statement sees its own snapshot, so two queries can
    disagree with each other.
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
    `version`, `updated_at` and the status guards itself. Its `WHERE` repeats **every**
    precondition the Java path checks, not only the one that selects the rows: a scheduled bulk
    activation re-checked only the due time, so records went live without what activation
    requires;
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
  in one stored document makes the row unloadable; one application didn't boot (F-082). Each stored
  JSON type gets a test that loads a document with an extra key. Ordered data in `jsonb` is
  stored as a list, never as object keys (`jsonb` re-sorts keys; `DISPLAY-OPTIONS-ORDER-LOST`).
- **Also:** a JSON column's content is a typed record where possible. Where a
  `Map` is unavoidable, its keys are named constants (one class of keys, never repeated string
  literals), and a closed set of values is an enum, so a typo can't write a key nobody reads.
- **Also:** the stored JSON record, the wire DTO and the cross-module contract are three types,
  mapped explicitly: when one type served as two, renaming a wire field orphaned every stored
  document that used the old name.
- **Not a finding when:** every `SqlTypes.JSON` attribute is a `String` the application maps with its
  own `JsonMapper` (Hibernate 7.4 passes a `String` through its format mapper untouched; verified
  2026-11-14): the reference's metadata piece does this.
- **Severity:** High on Jackson 3 projects.

### DATA-28: No heavy data work at startup
- **Check:** backfills and bulk updates run as a migration or a batched one-off job, never in
  `ApplicationRunner`, `CommandLineRunner`, `@PostConstruct` or `SmartInitializingSingleton`.
  They grow with the data until the app can't boot (F-081: a 38 s backfill).
- **Severity:** High.

### DATA-29: Locks, pollers and caps
- **Principle:** a lock names the rule it protects that **no constraint, foreign key or `@Version`
  already holds** (its repository method's one-line javadoc says which). A lock that only turns a
  rare race's 503 or 409 into a nicer code is not added, provided the data stays correct, the answer
  is never 500 and a retry is safe. The exception is a race that is normal traffic (two tabs
  refreshing one token, a double-clicked submit): there the answer must be the documented one.
  Measured 2026-11-14 (lock audit of the reference, every lock removed in turn): the owner-scope
  advisory lock and the three `FOR KEY SHARE` delete guards protected nothing the database didn't
  already hold, and were removed; the todo's `findForUpdate…` and the token's single-winner rotation
  hold the error contract, and stayed (the token's as a conditional update).
- **Check:**
  - **the lock vocabulary has three entries**, chosen by purpose:
    - `findForUpdate…` (`@Lock(PESSIMISTIC_WRITE)`, which Hibernate issues as `FOR NO KEY UPDATE` on
      PostgreSQL): **the owner of a write**, the row the transaction itself changes, taken first when
      the write also changes its member rows (attachments, tag links) or deletes it, so concurrent
      writers queue there (MEDIA-07, the reference's `TodoService.update`, `replaceTags`, `delete`).
      It costs nothing (the version bump locks that row anyway) and holds the answers: without it a
      same-version double submit answered 409 `DUPLICATE_RECORD` (84 of 120) or a deadlock's 503
      (16 of 120) instead of `OPTIMISTIC_LOCK_CONFLICT`, and a member write racing the delete 500;
    - pollers' `FOR UPDATE SKIP LOCKED` (below);
    - **a claim is a conditional update**, not a read lock: `UPDATE … SET <claimed> WHERE id = ? AND
      <still claimable>` and the rows affected decide (1: this writer won; 0: re-read and take the
      loser's path). One refresh token rotated once, a one-time code used once, a status only one
      writer may flip. A `@Modifying(flushAutomatically = true, clearAutomatically = true)` JPQL
      update that bumps `version` and `updatedAt` itself (DATA-24); the clear detaches what was read,
      so a lazy relation needed afterwards is loaded before it (reference:
      `RefreshTokenRepository.revokeIfLive`, `TokenService.refresh`, logout's `revoke`);
  - **caps and multi-row invariants lock the parent** (its `findForUpdate…`) before counting or
    reading the rows the rule spans: "max N per parent" (incident R5: a cap of 5, and 6 created), a
    mutable column of another row re-checked before the write (ship an order only while its account
    is active; test row 23b), a version chain's close-and-open (DATA-36). Or a constraint holds it, or
    the parent's `@Version` does when the collection bumps it (the reference's attachment cap,
    DATA-30);
  - **a parent read before a child's insert is not locked.** The foreign key holds the data: its
    check locks the parent `FOR KEY SHARE` from the insert on, so a delete can't remove a parent under
    a committed child. A delete that wins the window between the read and the insert makes the insert
    fail on the key, answered with the parent's not-found code (DATA-33, error-contract ERR-02). A
    `FOR KEY SHARE` or `FOR SHARE` "delete guard" on that read is a finding unless its javadoc names
    another rule; `FOR SHARE` there also blocks every edit of the parent, and two writers that each
    update their own row and share-lock the other's (swap two employees' managers) deadlock;
  - an exclusion constraint's concurrent overlapping inserts can deadlock (a rare 503, exactly one
    row stored): not a reason to serialise the scope (DATA-35);
  - advisory locks only for a rule that has no row to lock; transaction-scoped
    (`pg_advisory_xact_lock`) with a 64-bit key. Session-level locks leak on pooled connections.
    Detect `hashtext(`: it is 32-bit, so unrelated keys collide and wait on each other; use
    `hashtextextended(key, 0)`;
  - multi-row locks are taken in a consistent order (`ORDER BY id`, `order_updates=true`);
  - **member rows another aggregate's delete removes by cascade** (a tag's delete takes its
    `todo_tags` rows with it) are outside the root's lock: the root's delete removes them with one
    statement that doesn't count them (`TodoTagRepository.deleteAllOfTodo`) before its own delete,
    since Hibernate's versioned delete per loaded row misses the removed ones and answers 409; a
    write that replaces them documents that 409 (reference: `TodoService.delete`, `replaceTags`);
  - a lock by id is taken only once the caller may see the row: the ownership check in the same
    `WHERE` (`findForUpdateByIdAndOwnerId`), or read, check visibility, then lock (DATA-32's steps).
    A lock by id alone lets any signed-in caller queue behind another user's writers before its 404;
  - pollers claim with `FOR UPDATE SKIP LOCKED` plus a lease in a short claim transaction, do the
    remote work between transactions, and use fencing when several instances run;
  - `lock_timeout` bounds every wait.
- **Detect:** a lock whose javadoc names no rule, or only a nicer error code; `findForKeyShare…` /
  `FOR KEY SHARE` / `PESSIMISTIC_READ` on a parent read before a child insert; `@Lock` on a lookup
  whose caller only flips one column once (a claim: a conditional update instead).
- **Severity:** High (a missing lock for a real rule); Medium (a lock that protects nothing: it adds
  waits and deadlock paths).

### DATA-30: Child additions don't bump the parent version
- **Check:** uniqueness checked in memory on a loaded collection isn't safe: adding through the
  inverse side doesn't bump the parent's `@Version` (incident R4: 10 of 10 duplicates). Use a
  constraint, or `OPTIMISTIC_FORCE_INCREMENT` / `PESSIMISTIC_WRITE` on the parent. "max + 1"
  numbering needs a unique constraint plus a parent lock, so the loser gets 409.
- **Also:** when a change to a `mappedBy` collection is meant to change the parent's version (a
  todo's set of tags, replaced with the todo's `version`), the collection carries
  `@OptimisticLock(excluded = false)`. Hibernate excludes inverse collections from the version by
  default, so without it the version never moves and a concurrent replace with the same version
  isn't refused (reference:
  `Todo.tagLinks`).
- **Detect:** a `mappedBy` collection written by a versioned endpoint, without the annotation.
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
- **Unique text:** case and surrounding spaces must not make a different value
  ("Groceries" vs " groceries", two accounts for one email). Two correct shapes:
  - the code **stores the normalised form** (enforced by `CHECK (col = lower(trim(col)))`) and the
    column gets a **plain unique constraint**, which every `col = ?` lookup uses (emails, codes);
  - the stored text **keeps its case** (a display name) and gets a unique **index on
    `lower(trim(col))`**. PostgreSQL uses an expression index **only for a query naming the same
    expression**: a lookup `WHERE col = ?` beside it is a sequential scan (measured 2026-11-13:
    the reference's login read the whole `users` table).

  A nullable one is partial: `WHERE col IS NOT NULL`.
- **Detect:** an expression index next to a repository lookup on the bare column (`findByEmail`).
- The constraint's name comes from the driver, for every kind (error-contract.md ERR-02).
- **Severity:** High (it includes an expression index that turns a request-path lookup, such as
  login, into a sequential scan).

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
  - a "hide" or "restore" flag that only filters lists is a disguised soft delete;
  - a "trash with recover" feature is a status (`TRASHED`) plus a purge job that hard-deletes after
    the window (OPS-03). Restoring re-checks the partial unique index's rule in the service and
    answers its domain 409: the name may have been taken meanwhile, and the flush otherwise fails
    with a generic duplicate error.
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
  - the id is never updatable: `@Column(updatable = false)`;
  - TSID needs a unique node id per instance, checked at startup (refuse to start without one when
    several instances run), or use UUIDv7, which needs none: a node id set under a name the library
    doesn't read left every instance on the same node.

  Random UUIDv4 is a finding.
- **Severity:** Medium.

### DATA-10: Indexes
- **Check:**
  - every foreign key column is indexed;
  - **one index per offered list order**, leading with the scope columns (owner, tenant), then the
    sort keys, **ending with the id** tie-breaker; filtered columns covered (composite or partial);
  - **also per scoping filter** (a parent id: a list's own screen): `(parent_id, owner_id, sort
    keys…, id)`, which also serves the foreign key, or the page walks every row of the owner;
  - every sort has a unique tie-breaker: the id, unless the sort column is itself unique and not
    null (a code), which is already a total order (`code, id` on a unique `code` adds a sort step
    the index can't serve);
  - **keyset code:** `Seek.order` names the same columns as `Seek.after`, in the same order, id
    last; the query fetches `size + 1`;
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
    for row 20,000, against 20 with keyset);
  - **outliers:** every list or loop over one owner's rows (a user's todos, a tenant's orders) that
    can grow without bound is keyset-paged, or the owner has a configured maximum
    (`app.limits.*`, enforced on create, DATA-29). Ask "what if one owner has 10 million rows?":
    a loop that loads them all, an export with no page, an in-memory sort or a cap check that counts
    them all is a finding.
- **Severity:** High for unbounded loads; Medium for N+1.

### DATA-12: Columns fit the data
- **Check:**
  - **string columns are `text`** (PostgreSQL: `varchar(n)` adds no speed, only a second length
    contract that drifts); entities carry no length constants;
  - the length contract is the request's `@MaxLength` (REST-09, which also sets the severity of a
    missing limit);
  - never `@Lob` (on PostgreSQL that's an `oid` large object);
  - an `Instant` has microseconds at most before it is stored or returned: PostgreSQL rounds
    nanoseconds, so a stamp sent before the read differs from every later read of it. The reference
    does it once: its `Clock` bean ticks in microseconds (`Clock.tick(..., 1 µs)`, `ClockConfig`) and
    auditing reads that clock (`JpaAuditingConfig`'s `DateTimeProvider`), so every `clock.instant()`
    and audit field already fits; elsewhere `truncatedTo(ChronoUnit.MICROS)`. Detect `Clock.systemUTC()`
    as the bean, or `@EnableJpaAuditing` without a `dateTimeProviderRef`;
  - text keys that are sorted or compared like Java does (keyset cursors, codes) use
    `COLLATE "C"`: a locale collation orders them differently and can change with the OS.
- **Severity:** Medium for a `varchar(n)` column or a length constant on the entity.

### DATA-13: Migrations
- **Check:**
  - every entity change has a new changeset with a rollback;
  - an applied changeset is never edited;
  - sequences and seed rows needed at runtime are created in migrations;
  - foreign keys are named `fk_<table>_<column>`;
  - Liquibase migrates the app's own database URL;
  - *profile-optional:* the app uses a DML-only role, and Liquibase the owner role;
  - a data migration checks that every row maps to the new shape and fails the changeset rather
    than dropping or nulling the rows that don't.
- **Detect:** an entity `@Index` or `@Table(uniqueConstraints = …)` that no changeset creates:
  `ddl-auto: validate` doesn't check indexes, so a claimed unique index may never have existed.
- **Severity:** High for an edited applied changeset, a data migration that drops rows, or a
  claimed unique index that doesn't exist; Medium otherwise.

### DATA-14: Display codes come from sequences
- **Check:** human-readable codes under a unique constraint come from a database sequence, never
  from short random strings.
- **Severity:** High.

### DATA-32: Never lock "the current X" by a mutable column
- **Check:** a locked read of a row chosen by a column that other transactions change
  (`SELECT … WHERE is_current FOR SHARE`, `WHERE status = 'OPEN' FOR UPDATE`) is a finding. Under
  READ COMMITTED it waits on the old row, re-checks only that row after the switch commits, finds it
  no longer matches, and returns nothing: it never sees the new current row, so a valid request is
  refused. Instead:
  1. read the id without a lock;
  2. lock that row by id;
  3. re-check the condition on the locked row, and answer 409 if it no longer holds (ERR-05).
- **Detect:** a `@Lock` / `FOR SHARE` / `FOR UPDATE` query whose `WHERE` names a flag, a status or a
  date range rather than the id.
- **Severity:** Medium (High when the wrong answer is a write, not a refusal).

### DATA-33: A child's owner is its parent's owner, enforced by the database
- **Check:** a child row that carries its own `owner_id` and belongs to an owned parent (a todo in
  a list, a link between two owned records) can't point at another owner's parent:
  - a **composite foreign key** `(parent_id, owner_id) → parent(id, owner_id)`, which needs
    `UNIQUE (id, owner_id)` on the parent (a new changeset when the parent's is applied);
  - the plain `owner_id` foreign key keeps its own index (the composite one doesn't lead with it);
  - the service still loads the parent by id **and** owner before writing the child (a foreign
    parent is a 404, REST-16), so the composite FK firing means a delete, on one side or the other.
    The module's `ConstraintErrorCodes` maps it twice, by the statement that tripped it (PostgreSQL
    names the child table and the same constraint on both sides; Hibernate's `JDBCException.getSQL()`
    tells them apart): `byConstraintName` for a **delete** of the parent (409 `<PARENT>_IN_USE`),
    `byForeignKeyOnWrite` for an **insert or update** of the child whose parent was deleted after the
    read (404 `<PARENT>_GONE`: a not-found code without placeholders, ERR-06, since the handler has no
    id to give it). A key with `ON DELETE CASCADE` trips only on the write side. An unmapped side is a
    500 (reference: `TodoConstraintErrorCodes`, `ConstraintViolationCodes`);
  - a child that can change parent does it through an entity method that checks the owner
    (`moveTo`); one that can't has `updatable = false` on its parent column.
- **Why:** the ownership check in the service is one line a later change can drop; the constraint
  makes a cross-owner row impossible.
- **Detect:** an owned child table whose parent FK is the parent id alone; a parent column on the
  child that is updatable with no owner check; a child's foreign key mapped only to `*_IN_USE` (its
  insert after a racing delete answers 409 "in use" for a parent that is gone, or 500 when unmapped).
- **Severity:** Medium (High when a cross-owner row would leak data, REST-16).

### DATA-34: Values that stored records reference are retired, not deleted
- **Check:** a reference value that stored rows point at (an option, a status, a category, a
  type) is retired with a status (DATA-07: a business state, not a soft-delete flag): hidden from
  pickers and new writes (REST-13 `status`), still resolved for the rows that use it. A hard
  `DELETE` is only for a value nothing references yet.
- **Why:** deleting a used value either fails on the foreign key or, where the reference is a code
  in text or JSON, leaves old records pointing at nothing.
- **Detect:** a `DELETE` endpoint on a lookup or option table; a stored code with no row behind it.
- **Severity:** Medium (High when old records stop loading or lose their meaning).

### DATA-35: No overlapping ranges is an exclusion constraint
- **Check:** "a person's bookings never share a day", "one price per product per period" is an
  `EXCLUDE USING gist (<scope> WITH =, daterange(start, end, '[]') WITH &&)` constraint, with
  `WHERE (status <> 'CANCELLED')` when only live records hold their range. Never a check-then-insert:
  two concurrent creates both see no overlap and both commit. Parts:
  - `CREATE EXTENSION IF NOT EXISTS btree_gist` in its own changeset (the `=` on a uuid or a number
    needs it). It is trusted since PostgreSQL 13, so the database owner can create it; a managed
    database must allow it (RDS and Cloud SQL do), else a DBA creates it before the first migration;
  - the bounds say inclusive or not (`'[]'` for days a person reads as "from the 20th to the 22nd";
    `'[)'` for instants), the API text says it, and a `CHECK (start <= end)` backs the request's
    `@AssertTrue is<X>RangeOrdered()` (400);
  - **no scope lock for the constraint's sake** (DATA-29): two overlapping inserts at the same instant
    each wait in the exclusion check for the other's uncommitted row, and PostgreSQL ends it as a
    deadlock. The victim answers 503 `DATABASE_CONTENTION`, exactly one row is stored, and its retry
    answers 409 (measured 2026-11-14: about 1 answer in 6 of a 6-way race, never 500, never two
    rows). Serialise the scope only when that 503 is normal traffic for the endpoint, or when the
    write spans several rows of the scope (a version chain's close-and-open, DATA-36: lock the
    referent row);
  - the constraint is named (`ex_<table>_<columns>`) and mapped by its module's
    `ConstraintErrorCodes` to a 409 code (`<ENTITY>_OVERLAPS`, a message without placeholders: ERR-06).
    Hibernate leaves 23P01 unnamed; the driver names it (error-contract.md ERR-02);
  - tests: an overlap on the first day and on the last day of an existing range, one inside and one
    around it, all 409 without internals; the day before and the day after accepted; another scope's
    same range accepted; concurrent overlapping creates store exactly one, the others 409
    `<ENTITY>_OVERLAPS` or 503 `DATABASE_CONTENTION` with their bodies checked, never 500 (TEST-05).

  Reference: `planning/` (`Plan`, changeset `0011-plans.sql`, `PlanningConstraintErrorCodes`,
  `PlanIntegrationTest`).
- **Detect:** a `exists…Overlapping` / `count…Between` query before an insert; an overlap rule only in
  Java; a daterange without stated bounds.
- **Severity:** High (two overlapping bookings is corrupt data).
