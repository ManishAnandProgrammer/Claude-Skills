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
    disagree with each other;
  - a two-step read (a batch, then one lookup of its parents' names; a record, then its parent's view)
    expects the second step to miss a row deleted (or moved, its old parent deleted) in between: the
    batch leaves that row out, the single read answers the record's own 404, never a null mapped into
    a 500 or the parent's not-found code (reference: `PlanService.withTodos`, `findById`, tested by
    gating the second statement: `PlanIntegrationTest.listLeavesOutAPlanWhoseTodoVanishedBetweenTheReads`);
  - a same-module parent on a detail is read with the record in one statement (an entity graph or a
    fetch join of the to-one), and a write's response maps its parents like the single read.
- **Detect:** a detail or a write's response that reads a `@ManyToOne(fetch = LAZY)` parent after the
  record's own statement, justified by a row lock: a write that changes nothing flushes no `UPDATE` and
  holds no lock, so the parent can be deleted in between and Hibernate's proxy throws (a 500).
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
  - the one exception, **a machine-written stamp** (last used, last seen, last synced) on a record users
    edit with a version: it is written by a bulk update that sets the stamp alone, neither `version`
    nor `updated_at`, and the entity has no setter for it. Written through the entity, it moves the
    version a user sends back (measured: a membership at version 3 after three sign-ins, every ADMIN's
    role change 409) and two writers of the stamp conflict. `@OptimisticLock(excluded = true)` doesn't
    help: auditing still changes `updated_at`, which moves the version anyway. Reference:
    `TenantMembershipRepository.markUsed`, tested by `TenantMemberIntegrationTest.tokenIssuesKeepTheVersion`.
    The session version (SEC-07) is such a stamp too, with one more rule: its column is mapped read-only
    (`insertable = false, updatable = false`), so its bulk bump needs no `clearAutomatically`: no
    managed copy can write the older number back, and the caller's entities stay attached for the
    response. Its new value is read back by a query (a scalar or a projection), never from an entity
    loaded before the bump (reference: `TenantMembershipRepository.bumpSessionVersion`);
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
    is active; test row 23b; reference: a plan only for an open todo, `PlanService.create` through
    `Todos.getOwnedTodoViewForUpdate`, tested by `PlanIntegrationTest.completionRacingACreateWaitsForIt`),
    a version chain's close-and-open (DATA-36). Or a constraint holds it, or the parent's `@Version`
    does when the collection bumps it (the reference's attachment cap, DATA-30). Only the writes that
    check the rule take the lock: a write of the child that checks nothing of the parent (new dates
    within one book) doesn't, even when an exclusion constraint scoped by that parent could deadlock it
    (DATA-35's 503 stands);
  - **a parent read before a child's insert is not locked** unless the write checks the parent's
    state (above). The foreign key holds the data: its check locks the parent `FOR KEY SHARE` from the insert on, so a delete can't remove a parent under
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
  - a blocking `pg_advisory_lock` wait honours the connection's `lock_timeout` like any lock wait
    (`55P03`). A wait that must outlast it (instances queued for a startup migration behind each other)
    lifts it on its own connection first (`set lock_timeout = 0`), and only on a connection that ends
    with the work: on a pooled one the setting stays for the next borrower. Reference:
    `TenantSchemaMigrator.waitForLock`, tested by `TenantTemplateMigrationIntegrationTest`;
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
  - **a `LIMIT … FOR UPDATE SKIP LOCKED` batch is computed once**: `WHERE id = ANY (ARRAY(SELECT id …
    LIMIT :batch FOR UPDATE SKIP LOCKED))` (an uncorrelated `ARRAY` sublink is an init plan) or a
    `MATERIALIZED` CTE, never `WHERE id IN (SELECT … LIMIT … FOR UPDATE SKIP LOCKED)`: the planner may
    put that subquery on the inner side of a join and run it again per outer row, each run skipping the
    rows the earlier ones locked and returning others, so one lease takes more than its batch (measured:
    a batch of 2 leased 5) and "disjoint, bounded batches" rests on plan luck. The test takes the
    statement the repository runs (a constant, not a copy), asserts its text (`= ANY (ARRAY(`, no
    `IN (SELECT`: the guarantee is the shape, and the planner may run an `IN` once on the test's data)
    and bounds what it reads to twice its batch on rows that all qualify (reference
    `MediaFileRepository.LEASE_*`, `MediaFileIndexIntegrationTest`); a behaviour test alone passes on a
    near-empty table;
  - `lock_timeout` bounds every wait.
- **Detect:** `IN (SELECT … FOR UPDATE SKIP LOCKED)` or `IN (SELECT … LIMIT …)` in a writing statement;
  a lock whose javadoc names no rule, or only a nicer error code; `findForKeyShare…` /
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
    column gets a **plain unique constraint**, which every `col = ?` lookup uses (emails, codes). A
    pattern-validated value checks its **format** instead (`CHECK (code ~ '^[A-Z0-9-]{1,20}$')`, in the
    stored case): it implies the normalisation, while the case-only check lets a fixture or an import
    store a value no request can send back;
  - the stored text **keeps its case** (a display name) and gets a unique **index on
    `lower(trim(col))`**. PostgreSQL uses an expression index **only for a query naming the same
    expression**: a lookup `WHERE col = ?` beside it is a sequential scan (measured 2026-11-13:
    the reference's login read the whole `users` table).

  A nullable one is partial: `WHERE col IS NOT NULL`.
- **NULLs are distinct in a unique key:** `UNIQUE (email, deleted_at)` (or any unique key with a
  nullable column meant as "only one live row") admits any number of rows with `deleted_at` NULL
  (measured 2026-11-14: two `('a@x', NULL)` rows accepted). Use a partial unique index
  (`WHERE deleted_at IS NULL`), or `UNIQUE NULLS NOT DISTINCT` where NULL must count as a value.
- **Detect:** an expression index next to a repository lookup on the bare column (`findByEmail`); a
  unique constraint or index including a nullable column, without `NULLS NOT DISTINCT` or a
  `WHERE … IS NOT NULL`.
- The constraint's name comes from the driver, for every kind (error-contract.md ERR-02).
- **Severity:** High (it includes an expression index that turns a request-path lookup, such as
  login, into a sequential scan).

### DATA-06: Optimistic locking
- **Check:**
  - entities carry `@Version`;
  - bulk updates bump `version` and `updatedAt` (DATA-24), except a machine-written stamp, which
    moves neither (DATA-24): the version is what a user saw, and a stamp is no edit they could have seen;
  - user actions on reviewed records take the version the user saw (409 when stale);
  - user 409s are **not** retried server-side; only machine writers that re-read state may retry;
  - background writers re-check status under a lock;
  - **the version a write takes is readable through the API by whoever makes the write**: a GET on the
    write's own path, on the resource it acts on, or on that resource's collection answers it (an
    operator's `PATCH /users/{id}/status` comes with `GET /users?email=`). A version only SQL can read
    makes the endpoint unusable (and a test reading it with `jdbc` hides that). Enforced by the reference's
    `ApiContractIntegrationTest.everyVersionedWriteHasAReadOfTheVersion` over the OpenAPI document.
- **Detect:** a request record with `version` (or a `version` query parameter) whose resource has no GET
  answering it, or whose only GET needs a role the writer lacks; a test helper `versionOf` reading the
  version with `jdbc` for an endpoint whose callers have no such read.
- **Severity:** High; Medium for an endpoint unusable without the database (no data at risk).

### DATA-07: Deletes
- **Check:**
  - no soft-delete flags (`deleted`, `deleted_at`, `@SQLRestriction`, `@Where`);
  - business states are a status enum: decisions people take (`APPROVED`, `CANCELLED`), never
    readings of the clock (DATA-37);
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
    sort keys, **ending with the id** tie-breaker; filtered columns covered (composite or partial).
    With a tenant column (row tenancy) both it and the owner are leading equality columns: **the owner
    first when the table has an owner** (`(owner_id, tenant_id, sort…, id)`, which still serves the
    owner's foreign key), **`tenant_id` first when it has none or a list filters by the tenant alone**
    (`(tenant_id, sort…, id)`; TEN-33);
  - **also per scoping filter** (a parent id: a list's own screen): `(parent_id, owner_id, sort
    keys…, id)`, which also serves the foreign key, or the page walks every row of the owner;
  - **a status (low-cardinality enum) filter with a rare value is a scoping filter**: one index per
    offered order (`(scope…, status, sort keys…, id)`), unless the scope bounds the walk to a few pages
    or every offered order brings the rare rows first (recorded per order, never for the default order
    alone). Without it, an order under which the rare rows come last walks the scope past every other
    row (measured: 165–202 ms against under 0.1 ms with the index). On a table without a leading scope
    column the planner may still walk the order's index; a rare value's plan there is a use case
    (crud-gen SKILL.md 4.1's extension point: a partial index for that value, the value a literal, proven
    with the project's own `EXPLAIN (ANALYZE, BUFFERS)` test), not a finding against the composite;
  - **a contains search (`query`) on a table without a scope column** reads the whole table, which grows:
    the house convention is that it is offered only on a table whose size is bounded and recorded (the most
    rows it will hold, crud-gen *Decisions per entity*), or together with a filter that
    scopes it through its own index (the list refuses `query` alone, 400 naming the rule, the
    `@CrossField` shape); a project that needs it unbounded adds the trigram remedy (`TextSearch`'s javadoc);
  - every sort has a unique tie-breaker: the id, unless the sort column is itself unique and not
    null (a code), which is already a total order (`code, id` on a unique `code` adds a sort step
    the index can't serve);
  - **keyset code:** `Seek.order` names the same columns as `Seek.after`, in the same order, id
    last; the query fetches `size + 1`. The seek is **one row-value comparison**, `(a, id) > (x, y)`
    (`Seek.after`, the cursor's values bound as parameters): PostgreSQL makes it one index condition, so
    the scan starts exactly at the cursor. The OR form (`a > x OR (a = x AND id > y)`) is no index
    condition at all (batch N reads N batches), and even with the leading `a >= x` it bounds the index on
    `a` alone: a later batch inside a tie group (a term's start day shared by a thousand enrollments)
    rereads the group's rows before the cursor on every batch (measured: 172 rows for a page of 21);
  - **NEWEST is the creation time, then the id** (`(created_at, id)`, index `(scope…, created_at, id)`),
    **never the id alone**, whatever the scope: the primary key leads with the id, so an id order gives
    every scoped list a competitor, and with UUIDv7 ids in heap order the planner costs walking `pk_<table>`
    backward as cheap whenever the scope looks like a big share of the table; for a scope whose rows are old
    (an owner or a tenant that stopped writing) a page then reads every newer row of everyone else first
    (help-desk, round 4: 16,400 rows for a page of 20; the reference's old, big owner then: 10,540 with
    `(owner_id, id)` in place). No index leads with `created_at`, so the scope's own index has no
    competitor. OLDEST likewise. An unscoped NEWEST (a platform's tenants) gets its `(created_at, id)` index;
  - **a keyset sort key is never null.** A nullable column (a due date) sorts through a stored
    generated column (`coalesce(due_date, date '9999-12-31')`) that the index and the keyset use;
  - **an overlap filter has a lower bound.** "What is on during [from, to]" is `ends_on >= from AND
    starts_on <= to`; every index orders by the start, so only `starts_on <= to` reaches it and
    `ends_on >= from` filters the scope's whole history before `to` (a "this week" screen reads years).
    A configured maximum range length gives the bound: a range ending on or after `from` started no
    earlier than `from - (max - 1)`, so the query adds `starts_on >= from - (max - 1)` and the index
    range is bounded on both sides (golden `PlanRepositoryImpl.filterPredicates`, `plan.max-days`,
    checked on create). Without a maximum, it is a recorded decision that the filter scans the scope's
    history. These bounds hold under the start's order only: the overlap is a range filter like any other
    (next point), each end alone included (golden `PlanFilter.isPlannedFromSortedByStartsOn`,
    `isPlannedToSortedByStartsOn`);
  - **a range filter is offered under its own column's order** (a range of that order's index); under
    another order no index serves it on an unbounded scope (newest first, filtered by a date, walks the
    owner's history), so the filter refuses that sort with a 400 naming the rule (golden
    `TodoFilter.isDueOnOrBeforeSortedByDueDate`), unless a bounded scope holds it (a todo's time entries),
    recorded;
  - **every list has its index test** (crud-gen tests.md, *Index tests*): each offered filter and order,
    the `ids` lookup, the second steps, the delete-side key checks and the job leases name their index,
    and the test asserts it exists; each list's first page, as the repository issues it, is planned on its
    order's index (`QueryPlan`: plan nodes and index names, never a cost or a row count). Measured read
    costs are a project's own `EXPLAIN (ANALYZE, BUFFERS)` tests, where it knows its data (a use case);
  - no exact `COUNT(*)` over large filtered sets on every page (PERF-04).
- **Detect:** a status filter on a list with no status index and no recorded reason per offered order
  (a "rarely listed" note is not one: the UI offers the value); a list's offered filter or order with no
  index named in its index test, or a list without the first-page plan check; **an index test that asserts
  a cost or a row count** (a read bound, buffers) or makes a claim it doesn't assert (a javadoc saying a
  cell "fails without its index", a seed shaped to make it so): costs turn on the data, which is a
  project's own measured test; a contains search on an unscoped growing table with neither a recorded size
  nor a required scoping filter; **an order by the id alone** (`NEWEST` as `id desc`,
  `Sort.Order.desc("id")`) on any list; a seek in OR form; a range filter offered under another column's
  order on an unbounded scope, an overlap filter's end (alone or with the other) under NEWEST included; an
  overlap filter written as two comparisons with nothing bounding `starts_on` from below; an index on
  `updated_at` or `version` (or on any column every update changes): every
  update then writes new index entries and is never a HOT update (measured 2026-11-14 on a
  todos-shaped table: 0% HOT with the index, about 72% without it at the default fillfactor, 97% at
  fillfactor 80). Hibernate rewriting unchanged columns with the same value does not stop HOT, so
  `@DynamicUpdate` is not needed for it. A "recently changed" list is served by a column the
  business sets (a `published_at`), not the audit column.
- **Severity:** Medium (High on unbounded tables).

### DATA-11: Query shape
- **Check:**
  - no loading of unbounded data to count, average or filter in Java;
  - no N+1;
  - no `findAll()` behind an endpoint;
  - count queries are skipped when the page isn't full; cursor lists never count;
  - **list pages and dropdowns are projections** (`construct(Row.class, …)` of only the columns they
    show), never entities: measured 22 ms against 7 ms per page (2026-11-13). That includes a page's
    second step (the parents' names for the batch): a constructor projection of the view's columns
    (reference `TodoRepository.findViewsByIdInAndOwnerId`), not the parents' whole rows;
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
  - **codes and identifiers** (a course code, an SKU, a key compared with Java's `equals` or
    `compareTo`) use `COLLATE "C"`: a locale collation orders them differently and can change with the
    OS. **Names keep the locale collation** (titles, full names: people expect their language's order),
    keyset cursor or not: the order and the seek both run in the database with the same collation, so
    the walk stays consistent.
- **Severity:** Medium for a `varchar(n)` column or a length constant on the entity.

### DATA-13: Migrations
- **Check:**
  - every entity change has a new changeset with a rollback;
  - an applied changeset is never edited;
  - sequences and seed rows needed at runtime are created in migrations;
  - foreign keys are named `fk_<table>_<column>`;
  - changeset numbers: the house pieces own 0001-0099, the project's own start at 0100 (crud-gen
    bootstrap.md); a project changeset numbered below 0100 after the skills were adopted is a finding
    (a piece's later changeset would share its number, and the upgrade path's release ranges misread it);
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
    `@CrossField(reportedOn = "<end>") is<X>RangeOrdered()` (400 on the end field, ERR-07);
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
- **Version chains** (successive versions of one thing: a rate, a price list, contract terms;
  DATA-36) are a different range from a span a person types:
  - **half-open `[effective_from, effective_to)`**: the successor starts on the day the predecessor
    ends (`old.effective_to = new.effective_from`, the same value), never "the day before"
    (`minusDays(1)`). `'[]'` stays for user-typed day spans ("from the 20th to the 22nd"), as above;
  - **`CHECK (effective_from < effective_to)`** always (a NULL `effective_to` passes it). `EXCLUDE`
    lets empty ranges through silently (two `[d, d)` rows in one scope were both accepted), and
    `from > to` under the range expression is a data exception (22000, a 500); the CHECK names both
    as a 23514. It also catches closing a version in the transaction that opened it;
  - **the open end is NULL** (`effective_to` NULL: `daterange(from, NULL)` is unbounded). Never
    `'infinity'` (Hibernate reads it as year 292278994, and a JPA edit of any other field of that row
    then fails, 22008), `LocalDate.MAX` (22008 on write and as a parameter) or `Instant.MAX`
    (`ArithmeticException: long overflow`). `9999-12-31` only as a generated sort key (DATA-10's
    `due_sort`), never as a stored business value. "Current" is `WHERE effective_to IS NULL`, served
    by a partial index;
  - the default form is the **expression `EXCLUDE`**: `EXCLUDE USING gist (<ref>_id WITH =,
    daterange(effective_from, effective_to, '[)') WITH &&)`, no range column, `btree_gist` as
    above. PostgreSQL 18's `UNIQUE (<ref>_id, effective WITHOUT OVERLAPS)` gives the same 23P01
    (the same mapping) but needs a range column, which must be `GENERATED ALWAYS AS (daterange(…))
    STORED`: PostgreSQL 18 makes a generated column VIRTUAL by default, and a virtual one is refused
    in a unique or exclusion constraint (0A000). Use that form only when a `PERIOD` foreign key needs
    a key to reference. Map the scalar columns in the entity, never the range (Hibernate 7 has no
    range type, and `ddl-auto: validate` ignores the unmapped generated column);
  - writing the chain (close the open version and insert the next) locks the referent row first
    (DATA-36): unlike a single insert, it spans two rows of the scope.

  Measured 2026-11-14 on PostgreSQL 18.6 and Hibernate 7.4 (house-review DESIGN.md, temporal
  experiments).
- **Detect:** a `exists…Overlapping` / `count…Between` query before an insert; an overlap rule only in
  Java; a daterange without stated bounds; a version chain on `'[]'` or closed with `minusDays(1)`;
  a version table with no `CHECK (from < to)`; `'infinity'`, `LocalDate.MAX` or `Instant.MAX` as an
  open end; a `GENERATED` range column without `STORED`.
- **Severity:** High (two overlapping bookings is corrupt data).

### DATA-36: Versioned documents and the records made from them
- **Check:** a record made **from** a document that changes (an invoice from a price list, a time
  entry billed at a rate, a submission of a form, an acceptance of terms) keeps what it was made
  from:
  - it points at the **version** it used (`<doc>_version_id`, a foreign key to the version row,
    not to the document), and **copies the values it computed with** (`rate_applied`, `unit_price`)
    in columns commented `SNAPSHOT at <event>, never synced`. Its response carries the version id
    (and `versionNo` or `effectiveFrom`), so a client can show "billed at the rate of 1 March";
  - a published version is **never edited**: no setters, entity methods refuse once it is
    `PUBLISHED` (JAVA-20), no update endpoint on it. A new value is a new version. Correcting a
    published version's past (a backdated change, not a new one) is its own privileged action that
    records why, never the ordinary update (DESIGN.md: considered);
  - the stored state is the decision only: `DRAFT` / `PUBLISHED`, with `CHECK ((status =
    'PUBLISHED') = (published_at IS NOT NULL))`. `CURRENT`, `SUPERSEDED`, `SCHEDULED`, `EXPIRED` are
    derived from the dates and version numbers in the response (DATA-37);
  - **superseding locks the document row first** (its `findForUpdate…`, by id, never "the open
    version" by a mutable column: DATA-32), then reads the open version, closes it and inserts the
    next, with **one clock read** for the close and the open (JAVA-25). Without the lock, two
    supersedes deadlock in the exclusion constraint (measured 2026-11-14 with one insert per
    transaction too); with it, the loser waits, then fails the document's version check (the
    request carries the version the user saw, DATA-06) and answers 409;
  - the chain follows DATA-35's version-chain shape (`[from, to)`, CHECK, NULL open end);
  - **a copy** (a new draft from the latest version, with its child rows) is **one explicit entity
    method** (`RateVersion.draftFrom(RateVersion previous)`, each field named, children copied
    through the entity's `add…` methods), never `toBuilder()`: Lombok's `@SuperBuilder(toBuilder =
    true)` copies the id, the version and the audit fields and shares the collections, and
    `save(copy)` silently **overwrote the published row** (measured 2026-11-14; after resetting the
    ids by hand, persist failed on "Found shared references to a collection");
  - **the copy has a drift test**: an integration test compares `to_jsonb(row)` of the source and
    the copy, minus the keys that must differ (`id`, `version_no`, `status`, `published_at`,
    `version`, `created_*`, `updated_*`), for the version row and each child table
    (`jsonb_agg(to_jsonb(child) - … ORDER BY …)`), and **first asserts the source fixture has no
    NULL column** (`jsonb_each(to_jsonb(source)) WHERE value = 'null'`, except `created_by` /
    `updated_by`), with non-default values: a forgotten column that is NULL or default in both rows
    compares equal (a false green, measured). A new column the copy forgets turns the test red. No
    reflection in application code.
- **Why:** a result that reads the live document (`invoice.getPriceList().getPrice()`) changes
  meaning when the document changes; a copy written field by field forgets the next column added.
- **Not a finding when:** the record wants the referenced thing's **current** value (a customer's
  current email): that is a plain reference.
- **Detect:** a result's foreign key to the document with no version; a mapper or report reading the
  document's live value for a stored result; a setter or an update endpoint on a published version;
  `toBuilder()` on an entity; a hand-written copy without a drift test, or with one whose fixture
  leaves columns NULL; supersede code that locks the open version (`… WHERE effective_to IS NULL FOR
  UPDATE`) instead of the document.
- **Tests:** superseding keeps the earlier slice (the old version keeps `[from, newFrom)`); both
  sides of a boundary (the day before `to` finds the old version, `to` the new one); a stored result
  is unchanged (its response byte for byte) after a new version is published; an overlapping version
  is 409; concurrent supersedes of one document store one new version and answer 409, never 503; the
  copy's drift test.
- **Reference:** no golden code yet (the planned shape: a per-owner `billing_rates` referent with
  `billing_rate_versions`, and time entries gaining `billing_rate_version_id` and `rate_applied`).
- **Severity:** High (a past result changes meaning, or a published version is overwritten).

### DATA-37: No stored status that the passage of time changes
- **Check:** a status that becomes true because time passes (`ACTIVE` once the start date is
  reached, `EXPIRED`, `OVERDUE`, `SCHEDULED`, `SUPERSEDED`, `CURRENT`, an `is_current` flag) is
  **derived** from the stored dates when it is read (in the projection, or one `static` method that
  the entity and the projection both use, SMELL-01), never stored and flipped by a job. A job that
  runs late, twice or not at all leaves it wrong, and every reader trusts it.
- **Not a finding when:** the status is a decision a person or a process takes (`APPROVED`,
  `CANCELLED`, `PUBLISHED`): that is a real attribute (DATA-07). When the passage of time must cause
  something exactly once (send the expiry notice), store **the event** (`expiry_notice_sent_at`),
  not the state.
- **Detect:** status values of that kind beside date columns; a `@Scheduled` method running
  `UPDATE … SET status = 'EXPIRED' WHERE ends_at < now()`; code that "recomputes" a stored status
  defensively; an `is_current` column on a version table (a partial index on `effective_to IS NULL`
  serves "current", DATA-35).
- **Severity:** Medium (High when a wrong status gates money or access).

### DATA-38: A lookup for a dated record filters by that record's date
- **Check:** reading a rate, price, fee, tier, configuration or terms **on behalf of a dated record**
  (a time entry's day, an invoice's issue date) filters by **that record's date**
  (`findEffectiveOn(<ref>Id, LocalDate on)`: `effective_from <= :on AND (effective_to > :on OR
  effective_to IS NULL)`), or reads the version the record already points at (DATA-36). Never "the
  current one".
- **Not a finding when:** the record is created now and the as-of date is today, from the `Clock`
  (JAVA-25); the lookup then still passes that date, not "current".
- **Detect:** `find…Current…` / `findBy<Code>` on a `*_rate`, `*_price`, `*_fee`, `*_tier`,
  `*_terms` table called while computing or re-computing a stored record; a recompute or report
  query joining such a table without a date predicate.
- **Severity:** High (a past record is recomputed at today's price).
