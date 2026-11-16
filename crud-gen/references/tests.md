# Integration tests (Step 6)

Every generated endpoint ships with its tests (house-review lenses.md TEST-01…18). **Follow the
golden classes**: `~/.claude/skills-reference/src/test/java/com/example/app/todo/TodoIntegrationTest.java`
(cursor list, state action, child of a list) and `TodoListIntegrationTest.java` (offset list,
unique name, dropdown). Generate `{{testRoot}}/<app path>/EntityNameIntegrationTest.java`:
- extends `{{testBase}}` (one shared Spring context; **never** `@MockitoBean` or
  `@TestPropertySource`, which start another context);
- uses the base's client and helpers (`newUser()`, `signIn(account)`, `newAdmin()` and
  `newUserWithRole(role)` with the metadata or media piece, `getAs`/`postAs`/…, `read`, `sql`,
  `statistics()`, `randomIds(n)`, `race(...)`, `awaitLockWaiters` with `lockWaitDeadline()`,
  `awaitLatch`, `within(future)`, `createsHeldAtTheirInsert` and `writesHeldWhileTheParentWaits`
  (row 23), `newExecutor()` for a test's own threads (never `Executors.*`
  directly: with tenancy its threads run in the test tenant); with media `uploadPolicyOf`,
  `setUploadPolicy`, `restoreUploadPolicy`; with tenancy `newTenant()`, `newUserIn(tenant)`, `newUserIn(tenant, role)`,
  `platformToken()`, `testTenant()`);
- with tenancy the tests read as without it: they run inside one test tenant of the run (services and
  jobs called directly included), and `jdbc`/`sql` see its tables. A class about tenancy itself, or
  testing a platform-wide job, is `@WithoutTestTenant`: no scope, each request's token names its tenant;
- fixtures several classes share go by what they serve: **a piece's** helpers (media's upload
  policies, metadata's stored values) live in `{{testBase}}` itself, beside the piece's markers, as the
  golden base does; **the domain's** (an account linked to a record, a manager, a role beyond ADMIN) go
  in a project base extending `{{testBase}}`; a new role brings its `new<Role>()` there (REST-25). A
  fixture kept beyond one test caches the account and calls `signIn` on use, never a cached access
  token (it expires after `app.jwt.access-token-ttl`, at most 15 minutes).

## Conventions
The test rules are house-review's TEST-xx (lenses.md): one behaviour per test with `@DisplayName`,
own data, shared state reset with `@ResourceLock`, today computed once (TEST-07); setup asserts its
2xx and direct fixtures go through `sql(...)` (TEST-10); every refusal asserts its `errorCode`, on
every call (TEST-08); an absence follows a presence (TEST-16); order fixtures that differ under every
order (TEST-10); query parameters with special characters through `.param(...)`, and a 403 test with
a valid body (TEST-07). A validation refusal asserts where and why (ERR-07): `$.errors[0].pointer`
`"#/name"` (a body field) or `$.errors[0].parameter` `"size"` (a query parameter), and
`$.errors[0].detail` with the configured number (`"must be at most 100 characters"`); among several,
`$.errors[?(@.pointer == '#/name')].detail`. No `var`; JSON as text blocks or fixture helpers.

**Shared tables** (a catalogue, admin-managed types, employees: no `owner_id`, SKILL.md Step 1): the
shared database holds every other test's rows, so each list test scopes its calls by a filter only
its own rows match (a fresh parent id, a unique `query` token) and never asserts a page's absolute
contents or totals, nor that a table is empty; row 15 doesn't apply (nobody owns the record), and the
golden "a list shows only yours" test has no counterpart: drop it (with tenancy, the
`TenantIsolationIntegrationTest` covers who sees what). Row 14
carries the access decision: a plain USER gets 403 `ACCESS_DENIED` **with a valid body** on every
write (and on every read the decision restricts), the record unchanged; the writing role succeeds
(golden `MetadataIntegrationTest.onlyAdminsReachTheApi`, `UploadPolicyIntegrationTest`). Row 28 on a
shared table: seeded rows are seen by every unscoped list and dropdown, so give them a unique name prefix
and delete them in an `@AfterAll`. Seed the other scopes beside the tested one as the *Index tests* section
below says (the tested scope a small share of its table: well under 1% for an equality scope of a UUIDv7 table, whose heap follows its ids, so the planner may rightly walk the primary key for a scope of 1 in 50; written in creation order with the others'
rows: `uuidv7()` ids and `clock_timestamp()`, in rounds). A **table with no scope column at
all** (a tenant-wide table under schema tenancy: the schema is the scope) bounds each cell the same way:
a page of the table read in order is two pages, whatever else the table holds.

**Index tests in row mode** (`{{tenancy}}` = `row`): every tenant's rows share one table, so a second
tenant seeded in one block before or after the tested rows doesn't model production. Seed it the way
production fills the table:
- **other tenants in creation order** with the tested one (rounds: a chunk per tenant, in turn, each
  round newer than the last), and together 20–200 times bigger; plus, for a NEWEST cell, a scope whose rows
  are all older than the others' (*The seed*);
- **a bigger sibling for every equality scope** of the tested tenant (another member beside the
  tested member, another customer beside the tested customer), else the planner estimates one row and
  picks another index (the exclusion constraint's GiST index) plus a sort;
- a sibling is not enough for a scope that **determines the tenant** (an owner, a member): with the
  tenants interleaved, the planner multiplies the person's share by the tenant's (40 rows × 1.2% = one
  row). The table's changeset gives the pair dependency statistics (TEN-33; golden
  `0023-owner-tenant-statistics.sql`, `PlanRowIndexIntegrationTest`), and the test proves it red without
  them once. **The red proof needs the misestimate to bite**: the tested person's rows × their tenant's
  share of the table must come to about one row, so size that cell's seed like the golden
  `PlanRowIndexIntegrationTest` (a tested member with about 100 rows, a sibling about ten times that,
  other tenants 50 times the tenant and more), not like the 10× rule of *The seed*: for these cells
  "unserved reads ten times the bound" becomes "a sort of the person's whole scope exceeds the bound".
  The proof exists only where **another index competes** for the scope (an exclusion constraint's GiST
  index, a second order's index that also leads with the person): when none does, every cell is green
  with and without the statistics; keep them (TEN-33) and write in the test's javadoc why no cell can
  prove them;
- a class that seeds other tenants into shared tables deletes its rows in an `@AfterAll`
  (`PlanRowIndexIntegrationTest`): the next index class's analysis must see the table it seeds for;
- another tenant's rows through `sqlIn(tenant, …)` (the test base: its own transaction, on a
  connection told that tenant), each insert naming its `tenant_id`; never `set_config('app.tenant_id',
  …)` by hand (TEN-31 reserves it for the hook);
- a `@BeforeAll` that seeds through a `TransactionTemplate` of its own calls `testTenant()` first:
  created inside that transaction the tenant's after-commit provisioning never runs, and it stays
  PENDING (`sql()` does it for you);
- `analyze` under `set local default_statistics_target = 1000` after seeding: in a full run the table
  holds every other class's rows too, and the default target misestimated the tested member's 1,000
  rows as about 60 (green alone, red in `./mvnw verify`). The same holds without tenancy for a shared
  table every class writes (golden `TodoIndexIntegrationTest`, `PlanIndexIntegrationTest` analyze so).

## Index tests: the cell matrix and the read bound (row 28)

An index test is a **matrix**: for a generated list, every cell (each offered order × each filter, the
filter's every value for an enum; a later batch; an offset list's later page; the `ids` lookup; each
second step that fetches the rows' parents or tags; each parent's delete-side foreign key check; each
job's lease) names **the index meant to serve it** and **the most rows it may read**. Write the matrix
first (a table in the test class's javadoc or its `cells()` list), then one parameterized test runs every
cell. Golden: `TodoIndexIntegrationTest`, `TagIndexIntegrationTest`, `TimeEntryIndexIntegrationTest`,
`PlanIndexIntegrationTest`, `MediaFileIndexIntegrationTest`, the tenancy overlay's
`TenantMemberIndexIntegrationTest`, the row overlay's `PlanRowIndexIntegrationTest`.

**What each cell asserts: two checks, neither of which turns on statistics.**
1. **The index exists** under its name (`ReadCost.indexExists`): a schema check. It is what catches a
   dropped or never-created index that another index happens to serve cheaply on the seed (a composite
   key `(id, owner_id)` walked for the owner's newest filters inside the index, which no count shows).
2. **The statement reads at most the cell's bound** (`ReadCost.of(jdbc, transactionManager, sql,
   parameters).rowsRead()`), written as the repository issues it, `limit` included: a cursor list fetches
   `size + 1` rows (`limit 21` for the default 20), an offset page `size`: `EXPLAIN ANALYZE` runs the statement (rolled back, so a lease's `UPDATE`
   changes nothing) and adds up, over every table scan, the rows it handed on plus the rows its filter
   threw away, times its loops. Assert it with `.as(cost.plan())`, so a failure prints the plan.

Never assert a plan's shape (an index name in the plan, no `Sort`, a column in `Index Cond`): which plan
wins turns on the statistics, so such a test passes alone and fails in a full run, or the other way round.
The bound holds whichever plan the planner picks; other classes' rows in the table only make a plan the
index doesn't serve read more.

**The bounds** (constants in the test, each with its reason):

| Cell | Bound |
|---|---|
| served by its index: a first page, a later batch (the seek exactly as `Seek.after` writes it: one row-value comparison, `(a, id) > (:a, :id)`), a later batch whose cursor sits inside a tie group of the leading key, a status value with its own index, a range of the order's own column | two pages (`2 × (size + 1)`) |
| an offset list's later page | the offset plus two pages |
| a filter **applied while walking** the order's index (a recorded reason, SKILL.md 4.1: no value rare, a tag) | two pages ÷ the share of the rarest value the reason allows (seeded at that share), times two when each walked row probes another index |
| a filter left to **a bounded scope** (a todo's time entries: "one todo's entries are few") | the scope's rows, as seeded |
| a contains search (`query`, no btree serves it) | the scope's rows (the owner's, the tenant's): never anyone else's |
| an overlap filter with its derived lower bound | the configured maximum length plus the range's days, times the ranges the scope holds per day (1 for a scope whose ranges never overlap, a person's plans; a tenant's rota holds one per member on call each day) |
| an `ids` lookup | the asked rows (two pages at most) |
| a second step (`<parents> where id in (...)`, a batch's tags) | the batch's rows per table it reads |
| a parent's delete-side foreign key check, a cascade | the parent's own children |
| a job's `LIMIT ... FOR UPDATE SKIP LOCKED` lease | twice the batch; the statement's own text is asserted too (`= ANY (ARRAY(...))`, DATA-29): no bound can tell an `IN (...)` that the planner happens to run once |
| a contains search on a table without a scope column | the table's recorded maximum size (*Decisions per entity*), or, required with a scoping filter, that filter's rows |
| an index the policy can't use under row mode (`&&`, `LIKE`: TEN-36) | the scope's rows (recorded in the cell) |

**The seed** makes every cell its index doesn't serve read at least ten times its bound, **checked per
cell**: the test's javadoc names the cells no seed can make expensive (a common value walked on its order's
own index, a count of the whole table), which rest on the index's existence check alone, and the red proof
drops an index of each other kind once. Ids are assigned **in a declared order**: within one statement
`uuidv7()` follows the statement's output order, which a join or a cross join doesn't fix, so a claim such
as "the active loans are the newest" is made true by rounds (one statement per round, in time order) or a
single series `order by n`, and **checked before the cells** (golden `TodoIndexIntegrationTest` asserts its old owner's lists are
older than every other's). Also: the tested
scope a small share of its table (a sibling owner with twenty times the rows, another tenant's members),
**written as the application writes them**: `uuidv7()` ids and `clock_timestamp()` as `created_at`, in
rounds of each scope's share in turn, so the rows reach the heap in id order and the scopes interleave.
Random ids, or one block per scope, hide a backward walk of the primary key: on such a seed the walk looks
expensive to the planner, in production (time-ordered ids in heap order) it looks cheap. Each scope big enough that a sort
of all its rows exceeds two pages, the rare value at the share it really has and **where it really sits**
(open todos are the latest due, active loans the latest days), every value of an enum filter bound as a
parameter as the repository binds it (an inlined literal gets a plan the real query never gets).
**A status a later decision sets** (DONE, WITHDRAWN, CANCELLED, RETURNED) **is absent from the newest
rows**: a record is created in its first status and moves on afterwards, so seed it among the older rows
(none in the newest quarter by creation), never uniformly over every id; the first, live status sits with
the newest. **Each scope at its production share**, from the domain facts Step 2 asks (members per
tenant, enrollments per course, the share of each status value), written in the test's javadoc: a share
picked to make the cells pass proves nothing. A later
batch's cursor is **a seeded row inside the order's key range** (read after seeding, from the middle):
a constant outside the seed leaves nothing past the seek. Two more seeded shapes, each with its cell
(golden `TodoIndexIntegrationTest`): **a tie group**, several pages of rows sharing one value of the
order's leading key (200 todos due on one day), with a later batch's cursor inside it (an index bounded on
the leading key alone rereads the group); and for NEWEST **an old, big scope**, many rows all created
before everyone else's (3,000 lists), the scope an id order walks the primary key for. A cursor's
`created_at` is read as an `OffsetDateTime` (a `java.sql.Timestamp` is bound in the JVM's time zone). `analyze` the tables under `set local
default_statistics_target = 1000` after seeding (in a full run they hold every other class's rows too).
A fixture that inserts rows of a table with a display number splices in a number (golden
`TODO_NUMBER_COLUMN`, `TODO_NUMBER` in the test base).

**A rare value's cell is two pages, never the walking bound.** The walking bound is for a filter
without a rare value (a recorded reason, SKILL.md 4.1); a rare value has an index per order, so its cells
are served. When such a cell fails two pages, the seed is wrong (the value placed where it doesn't sit:
a later-decision status among the newest rows) or the index isn't needed (every offered order brings the
value first: record that and drop the index). A rare value's cell loosened to the walking bound can't
fail under any plan, so the index it was written for may go missing unnoticed.

**A table with no scope column at all** (a tenant-wide table under schema tenancy, a platform catalogue)
gives a contains search (`query`) a bound of the whole table, which no plan can exceed: such a cell
records the decision instead of an assertion that can't fail (the table's expected size, asked in
SKILL.md 4.1, and "no index serves a contains search"), keeping only the index existence checks of the
cells beside it; the offset list's count statement is a cell of its own (bounded by that size). **A
tenant-wide table in row mode** shares the test tenant with every other class's rows: count the scope's
rows at run time after seeding (`select count(*) from <table> where tenant_id = ?`) for a bound that is
"the scope's rows", or seed the cell's rows into a `newTenant()` of its own (golden
`TenantMemberIndexIntegrationTest`).

**A cell no index serves** is either given its index (golden `0024-tags-status-newest.sql`: tags by
status under NEWEST) or not offered: a range filter is offered under its own column's order only, unless
a bounded scope holds it (golden `TodoFilter.isDueOnOrBeforeSortedByDueDate`: `dueOnOrBefore` with sort
NEWEST is a 400 naming the rule). Prove the matrix red once: drop one index from its changeset (the
existence check and the bound both fail), and remove one seek bound (the bound fails).

The table is the contract; test method names may follow the golden classes. When the golden tests
and this table disagree, the table wins (and the golden class is a bug to report).

## Required tests

| # | Test | Asserts | Rule |
|---|---|---|---|
| 1 | `createReturns201WithTheSavedEntity` | 201, every field echoed, `version` 0, audit fields set | TEST-01 |
| 2 | `getReturnsTheEntity` / `getUnknownIdIs404` | 200 + fields; 404 `ERROR_PREFIX_NOT_FOUND` | TEST-01, 08 |
| 3 | `updateChangesTheFieldsAndBumpsTheVersion` | 200, new values, `version` + 1 | TEST-01 |
| 4 | `updateWithAStaleVersionIs409` | 409 `OPTIMISTIC_LOCK_CONFLICT`; a re-read shows the earlier values | DATA-06, TEST-08 |
| 5 | `writesToAnUnknownIdAre404` | PUT, PATCH actions and DELETE on an unknown id: 404 with the code | TEST-08 |
| 6 | `deleteReturns204AndTheEntityIsGone` | 204, then GET 404 | TEST-01 |
| 7 | `deleteWhileReferencedIs409` (if referenced) | 409 `ERROR_PREFIX_IN_USE`, no constraint name in the body | ERR, TEST-04 |
| 8 | `createWithoutRequiredFieldsIs400`; `writesWithoutAVersionAre400` | 400 `VALIDATION_FAILED`, `errors` points to each field (`$.errors[*].pointer`, `#/title`) | TEST-08 |
| 9 | Per **bounded field** (list them first: every field × create and update, an optional one included, and tick each off) (`@MaxLength`, `@Min`/`@ConfiguredMax`, `@ConfiguredMaxSize`, `@LatestDate`, a pattern), **on create and on update**, both sides of each bound: the limit accepted (multi-byte text at a length limit), one past it 400 with the configured number; the maximum page `size` accepted, and with offset paging the maximum `page` index accepted (an empty page, 200) as well as one past it refused | TEST-03 |
| 10 | `createWithAScriptTagStoresItAsText` | the documented `{{NotXss}}` behaviour, per free-text shape | TEST-03 |
| 11 | Per unique field: taken on create **and on update** is 409 `*_TAKEN`, the body without internals and a re-read showing the record unchanged (one row with the value); uniqueness ignores case and surrounding spaces. A **format-validated** field (`@Email`, a `@Pattern`) refuses surrounding spaces with 400 before uniqueness is reached: test the case only, and its `@Schema` says "ignoring case" without "and surrounding spaces". A **partial unique on a live status** (SKILL.md Step 2): "on update" means the writes that move a row into the live status or change the pair; when none does, the create's test (and the re-create after the terminal status) is the whole duty | DATA-05, TEST-04 |
| 12 | `concurrentCreatesWithTheSameXStoreOne` | latch race: one 201, the rest 409, **one row** in the database | TEST-05 |
| 13 | `concurrent<Action>sWithOneVersionApplyOnce` | latch race on update or a state action with the same version: one 200, the rest 409, the database `version` advanced once; every write endpoint that takes a version, the state actions (approve, reject) included | TEST-05 |
| 13c | An **unlocked, versioned delete** that documents 409 `OPTIMISTIC_LOCK_CONFLICT` (SKILL.md 4.9): `concurrentDeletesRemoveItOnce` | a latch race of deletes of one record: exactly one 204 (`containsOnlyOnce(204)`), every other answer 404 `<ERROR_PREFIX>_NOT_FOUND` or 409 `OPTIMISTIC_LOCK_CONFLICT` (`isSubsetOf(204, 404, 409)`, never `containsOnly`, which demands every value), each refusal through `assertRefusalsAre` per status (its code and no internals, row 22), the row gone (golden `PlanIntegrationTest.concurrentDeletesRemoveItOnce`, `TodoListIntegrationTest.concurrentDeletesRemoveItOnce`). A **locked** delete (`findForUpdateBy…`) gets the same race, its outcome exact: one 204, every other answer 404 `<ERROR_PREFIX>_NOT_FOUND`, never 409 (the cheapest proof of its lock: red without it) | TEST-05 |
| 13b | Racers on **different rows that share one global row** (two "make default" calls on two different records) | they may both succeed one after the other, correctly: assert the **invariant** (exactly one default row, one of the 200s; every refusal its code), never "exactly one 200" (flaky) | TEST-05 |
| 14 | `everyEndpointNeedsAToken` (and the wrong role per endpoint when roles exist) | 401 `AUTHENTICATION_REQUIRED` / 403 `ACCESS_DENIED`. When **every role writes** a shared root (with tenancy, recorded in Step 1): one parameterized test per `Role` constant (`@EnumSource(Role.class)`), each reaching every endpoint on another member's record (list, dropdown, get, update, delete, its own create), so a role added later is tested without an edit | TEST-02, SEC-01 |
| 15 | `anotherUsersRecordIs404` (owned) | B gets 404 **with the code** on GET, PUT, actions, DELETE of A's record; A's record unchanged | TEST-02, 16 |
| 15b | Tenancy (`{{tenancy}}` not `none`), in its own class `EntityNameTenantIsolationIntegrationTest` | a member of another tenant (`newUserIn(newTenant())`) gets 404 **with the code** on GET, PUT, actions, DELETE (each call asserts `errorCode`, PUT included); the record unchanged; that tenant's list doesn't show it, nor the reverse. **The stranger holds the role that writes the record** (`newUserIn(newTenant(), "ADMIN")` for an `@AdminOnly` root, the owner an ADMIN too): a plain USER's write is refused 403 by method security before the scope is reached, and the test would prove the role check, not the 404. Records shared by the whole tenant (no owner): scope both tenants' lists by a filter only the test's records match (a unique `query` token in their names; for a list without a text query, `ids` or a fresh parent's id), and assert presence and absence in the same call. With files (media piece): another tenant's upload key sent in a create or update is 409 `UPLOAD_NOT_AVAILABLE`, another tenant's file id in `remove<X>Ids` 404, the record unchanged. A list **filtered by the other tenant's parent id** can only be empty from the stranger's side: the owning tenant's same call first shows its row, then the stranger's is empty | TEN-13 |
| 15c | Row mode (`{{tenancy}}` = `row`), in the same class | each **native query** of the entity's repository, run in the other tenant's scope (`TenantScope.callAs(other.id(), ...)` around the service call, which passes that tenant), returns none of the caller's rows and changes none; the guards (once per project, never per entity) run green with the new table: `RowSecurityCatalogIntegrationTest` (its policy forced, `tenant_id` without a default, its keys carrying `tenant_id`, its replica identity holding `tenant_id`, no key to a platform table but `tenants`, its entity a `TenantOwnedEntity`) and `NativeQueryTenantIntegrationTest` (each native statement names `tenant_id = :tenantId` per tenant table). A table that is the platform's is added to its `PLATFORM_TABLES` with the reason. Schema mode: `SchemaCatalogIntegrationTest` green (no key leaves the tenant schema) | TEN-30, TEN-33, TEN-37, TEN-38, TEN-16 |
| 16 | Cursor list: `cursorBatchesWalkTheWholeListInOrder` | fixtures created **out of sort order** (TEST-10: rows created in calendar order pass a walk that ignores the sort; a creation-time order such as NEWEST has no other order to create in: walk it both ways instead), then walk every batch with `after`: every row once, in order; batches split **inside a group of equal sort keys** (the id tie-breaker) and, when a key is nullable through a generated column, across that boundary (undated rows last); the last batch `hasMore` false, no `nextCursor`. Per **text sort key** (a dropdown's label included): a batch ending on a value at its `@MaxLength` of control characters (`\u0001`, escaped in six bytes in the cursor's JSON) and 4-byte letters (`𐌰`) continues (golden `TodoListIntegrationTest.dropdownCursorOfTheLongestNameIsAccepted`; the cursor limit, SKILL.md 4.2) | TEST-11, 12, REST-08 |
| 16b | Offset list: `listIsOffsetPaged`, `listPastTheLastPageIsEmptyButNavigable` | `pageInfo` fields; the overrun page; size over the configured maximum is 400 | TEST-11, 12 |
| 17 | `cursorFromAnotherSortIsRefused`; a tampered cursor, one whose text key is well-formed JSON holding U+0000 included (golden `TodoListIntegrationTest.dropdownCursorWithANulCharacterIs400`: PostgreSQL can't take it, a 500 without `Cursor.read`'s check) | 400 `REQUEST_PARAMETER_INVALID`, `$.errors[0].parameter` is `after` | REST-08 |
| 18 | Per filter: `filterByXReturnsOnlyMatching` | its matching row present, its non-matching row **in the same scope** absent (a test scoped to one row passes with the filter ignored); **range filters include the boundary row** and exclude the next one | TEST-16, TEST-12 |
| 18b | `ids` (golden `filterByIdsReturnsOnlyMatching`, `idsAreBounded`, `filterByIdsRunsAConstantNumberOfQueries`) | the asked rows in the list's order, an unasked row absent (after a presence check), unknown and another user's ids simply absent; the maximum size accepted, one more 400 `VALIDATION_FAILED` with the configured number, a malformed id 400 `REQUEST_PARAMETER_INVALID` naming `ids`; the statement count of row 21 for 1 id and many | REST-23 |
| 19 | Per domain enum (filter or field; sort enums are covered by rows 16, 17 and 24): `@EnumSource` over every constant | each member works through the real endpoint | TEST-15 |
| 20 | `unconvertibleFilterValuesAre400`; unknown enum in a body; `unknownJsonFieldIs400` | `REQUEST_PARAMETER_INVALID` naming only the parameter (`$.errors[*].parameter`, no `java.` in the body); `REQUEST_UNREADABLE` whose `errors` point to the unknown field (`#/<field>`, `unknown field`) | REST-06, REST-22 |
| 21 | `listRunsAConstantNumberOfQueries` | `statistics()`: cursor lists and **dropdowns** exactly 1 statement for 1 row and a full batch; a nested member list 2 (the root's ownership check, then the batch); offset lists 1 for a partial page and 2 for a full one (the count), the same for every page size; **plus one per lookup the service makes before the query** (the caller's own row in another module, such as their employee record; the ids of a scope from another module's facade, such as a manager's team) **and one per parent-name lookup after it** (SKILL.md 4.3, each parent the rows show, the same module's or another module's: golden `PlanIntegrationTest.listRunsAConstantNumberOfQueries`, 2; an enrollment showing its student and its course, 3); **with the session-version piece, measure with an account that already made a request (its first one reads the session version once, a Redis miss: one statement too many on a fresh account whose fixtures came through `sql(...)`); an empty batch is the query alone** (the lookups skip no ids: `List.of()` without a statement, SKILL.md 4.8), asserted too: say which in the test | TEST-06 |
| 22 | Race tests return status **and body** | every 409 of a race asserts its errorCode and that the body has no `uq_`, `fk_`, `SQL`, `Exception`, `java.`: races reach other code paths (`@Version`, the unique index) than the sequential tests | TEST-08, TEST-04 |
| 23 | Parent with children: `childWritesRacingAParentDeleteKeepTheInvariant` (golden `TodoIntegrationTest` names it `createsRacingTheListDeleteNeverOrphanATodo`), **one per foreign key** that children are written through | child creates held at their insert while the parent is deleted (`createsHeldAtTheirInsert(<children table>, create, delete)` in the base class: a `share mode` lock on the child table, held in the test's own transaction, lets reads and foreign key checks pass and holds every insert; `awaitLockWaiters`, then the delete, then the gate opens): assert the **exact** outcome, the delete 204, every create 404 `<PARENT>_GONE` without internals (never `*_IN_USE`, never 500), no child left (golden `TodoIntegrationTest`, `TimeEntryIntegrationTest`, `TodoTagsIntegrationTest`). Proves the insert side of the foreign key's mapping (DATA-33); nothing locks the parent (DATA-29). A child update that **moves** it to another parent gets the same race on the update side (golden `movesRacingTheListDeleteKeepEveryTodoInItsList`: every move 404 `<PARENT>_GONE`, each child still under its old parent). Moves of **one** child queue on its row lock, and those behind the first read the parent after the delete: they answer `<PARENT>_NOT_FOUND`, so assert 404 with either code there, and nothing moved. So: racing moves of **different** children assert `<PARENT>_GONE` exactly (the golden); several moves of the **same** child accept either code. **When the child's write locks the parent** (SKILL.md Step 1's locked view, row 23b), the delete waits for the held writes instead: `writesHeldWhileTheParentWaits(<children table>, writes, delete)` in the base class holds the writes at their insert (one holds the parent's lock, the others wait for it: `HELD_CREATES` lock waiters), submits the delete on its own thread, awaits it as one more waiter and **asserts it is still waiting under the gate**, the assertion that goes red without the lock; then the gate opens. Assert every write 201 and the delete 409 `<PARENT>_IN_USE`, the children stored and the parent still there (golden `PlanIntegrationTest.createsRacingTheTodoDeleteAreKeptAndTheDeleteIsRefused`). Lock waits aren't FIFO: the delete may get the lock between two writes; the outcome holds anyway (the first write committed, so the delete finds a child), so assert the **set** of outcomes, never their order. `<PARENT>_GONE` is unreachable on that write: keep its mapping, don't document it on that endpoint. Every wait has a deadline (`awaitLatch`, `within`, `lockWaitDeadline()`), so a broken gate fails instead of hanging. **Self-referencing parent** (an employee's manager is an employee): a table gate on the children blocks the parent's own delete too, so gate on another foreign key's parent row instead (the child's `users` row held `FOR UPDATE`: the create waits at its insert's check of that key). Lives in the **child's** test class; the parent's `deleteWhileReferencedIs409` too, once the child exists. Not applicable when the parent's delete is refused for another reason (the default record can't be deleted): then row 23b | TEST-05 |
| 23b | A write that **checks another row under that row's lock** (a cap, or ship an order only while its account is active: the account's `findForUpdate…`, DATA-29) | raced against a change of that row (the account is suspended), **gated** so the change really waits on the lock: for a parent's state, `writesHeldWhileTheParentWaits(<children table>, List.of(write), change)` with **one** held write (with several, the change may get the lock between them and the later writes are 409: correct, but no fixed outcome to assert). The end state is the same with and without the lock (one write, the change applied), so what proves the lock is the helper's "the change is still waiting while the gate holds"; then the write 201, the change 200 after it, and the next write refused with the rule's code (golden `PlanIntegrationTest.completionRacingACreateWaitsForIt`, 409 `PLAN_TODO_DONE`). Plus the rule sequentially, both sides (golden `planForADoneTodoIs409`). Removing the lock turns the test red (TEST-09) | TEST-05 |
| 24 | Per sort, both directions | every keyset (each sort, each direction, the dropdown's `DESC`) walked to the end | TEST-12 |
| 25 | `query` limits | `search-query` at its limit and limit + 1 on every list and dropdown | TEST-03 |
| 25b | Range filters | a bound row included and the next value excluded at each end; both bounds equal; an inverted range 400 naming the range's end (`<x>To`, `max<X>`; ERR-07). An **overlap filter** (SKILL.md 4.1): ranges crossing either end found, one ending the day before `from` and one starting the day after `to` absent, a range of the maximum length found on its last day (the derived lower bound) and absent a day later, a range one day over the maximum 400 at `#/endsOn` (golden `PlanIntegrationTest.plannedDaysFindEveryPlanSharingADay`, `theLongestPlanIsFoundOnItsLastDay`, `lengthIsBounded`) | TEST-12 |
| 25c | Calendar dates (`@NotAfterToday`, `@EarliestDate`, `@LatestDate`) | today computed **once** from the `Clock` bean before any request: today + tolerance accepted, one day later refused, on create **and** update, and on a state action that records a day (returned on), where a day before the record's own start is 400 with its code; the configured earliest and latest dates accepted and the day beyond each refused 400, a year PostgreSQL can't hold (`-300000-01-01`) refused 400, never 500; the same for date filters. **Each date field on its own** (a range's `startsOn` and `endsOn` both), each bound, on create **and** update: the accepted bound exactly at the limit (both ends of a range at it at once, so the range check passes), `-300000-01-01` sent in each field, `errors` asserted exactly (`$.errors[*].pointer` and `$.errors[*].detail` with `contains(...)`, not `hasItem`): removing one field's annotation must turn a test red (golden `PlanIntegrationTest`'s date tests) | JAVA-25, REST-09 |
| 25d | Optional text / pattern fields | blank and empty stored and returned as `null` | SMELL-27 |
| 25f | A rule from configuration on an update (a maximum length of a loan, a file count) | with the limit lowered below an existing record: an update that changes another field succeeds, one that changes the rule's inputs is refused with its code; both sides of the limit on create (golden `TodoAttachmentsIntegrationTest`'s shrunk policy) | SMELL-27, TEST-03 |
| 25e | Immutable resources (no PUT, PATCH or DELETE: an issued invoice) | each answers 405 `REQUEST_METHOD_NOT_ALLOWED`, and the record is unchanged | TEST-01 |
| 26 | Entity unit test (only when the entity has state methods) | state methods (`changeStatus`, `moveTo`): transitions, the timestamp rule, the impossible-state guard (`IllegalStateException`); for a **status workflow** (SKILL.md Step 3) the whole matrix of moves, each refused one the 409 `AppException` with its code (no `IllegalStateException`: a refused move is the client's, not a bug) | JAVA-16, ERR-05 |
| 26b | A **frozen terminal status** (SKILL.md Step 3: a WITHDRAWN enrollment can't be edited) | each write the status freezes (PUT, member and file writes) on a record in that status: 409 `<ENTITY>_<STATUS>` with the code, the record unchanged (a re-read, `version` included), sent with a body that would also break another rule the write checks (longer days than a configured maximum, a parent's state; when the write checks no other rule, the frozen status alone), so the freeze is proven first; the same write on a live record succeeds; the entity unit test refuses it too; the writes left open (a delete) still work | ERR-05, TEST-01 |
| 27b | A **display number** (a counter, SKILL.md Step 2's TEN-18 row; golden `Todo.number`) | consecutive creates get consecutive numbers, shown on the detail and the list's rows; another tenant's first record is #1; concurrent creates get distinct consecutive numbers, never a duplicate or a 500 (the counter's row queues them); a refused create takes no number (golden `TodoReferencesIntegrationTest`, the tenancy overlay's `TodoRequesterTenancyIntegrationTest.eachTenantNumbersFromOne`) | TEN-18, TEN-05 |
| 27c | Row mode: the display number's seed pair (TEN-34) | the counters backfill run for a tenant without the counter gives it its highest number (0 for none); a PENDING tenant approved after the backfill is ACTIVE with one counter and numbers its first record #1 (golden `TodoSeedsBackfillIntegrationTest.existingTenantsGetTheirCounter`, `aPendingTenantThroughTheBackfillsIsActivated`); on a used database every tenant, whatever its status, numbers its own rows from 1 in id order (row 30) | TEN-34 |
| 27d | Row mode: a tenant without its counter (activated by an instance without the listener) | delete the counter row in a tenant, then create: #1, then #2 (the create's `createIfAbsent`), never a 500 (golden `TodoSeedsBackfillIntegrationTest.aTenantWithoutItsCounterNumbersFromOne`) | TEN-34 |
| 28 | `everyCellReadsAtMostItsBound` | the list's cell matrix (*Index tests* above): every offered order × every filter (every value of an enum filter, the rare one included), a later batch, an offset list's later page, the `ids` lookup, each second step (`<parents> where id in (...)` with the repository's own select list), each parent's delete-side foreign key check (`select 1 from <children> where <fk> = ?`), an overlap filter in each order with its derived lower bound: the cell's index exists by name, and the statement as the repository issues it (same select list, predicates, order and seek) reads at most the cell's bound. Golden: `TodoIndexIntegrationTest` | DATA-10 |
| 28b | No overlapping ranges (an exclusion constraint, DATA-35; golden `PlanIntegrationTest`) | overlap on the first day, the last day, inside and around an existing range: 409 `<ENTITY>_OVERLAPS` without internals; the day before and after accepted; another scope's same range accepted; concurrent overlapping creates store exactly one, the others 409 `<ENTITY>_OVERLAPS` or 503 `DATABASE_CONTENTION` (a deadlock in the constraint: no scope lock), never 500; when the writes lock a parent for a state rule, race creates under two parents of the constraint's scope (golden `concurrentOverlappingCreatesStoreOne`: two todos of one owner), since one parent's writes queue; with an **updatable range**, race updates that move different records onto the same days (nothing locks them, so this is the path to the constraint's deadlock): one 200, the others 409 `<ENTITY>_OVERLAPS` or 503, one row on the days, never 500; with an **updatable scope** (a hand-over, SKILL.md 4.1), race hand-overs of different records onto one member's same days the same way | DATA-35, TEST-05 |
| 28c | A parent in another module (ARCH-04; golden `PlanIntegrationTest`) | an unknown and another owner's parent id: 404 with the parent module's code, nothing stored; the parent's delete while children exist 409 (the child module maps the foreign key); row 23's gated race through the facade (unlocked view: 404 `<PARENT>_GONE` from the child module's write-side mapping; locked view, golden: every write 201, the delete 409, and row 23b); a parent deleted with a child between the child's two reads: the list leaves the row out (200), the single GET answers the child's 404 (golden `listLeavesOutAPlanWhoseTodoVanishedBetweenTheReads`, `getOfAPlanWhoseTodoVanishedBetweenTheReadsIs404`: the deletion holds the parent table `ACCESS EXCLUSIVE`, which the first statement passes and the second waits for); a list filtered by the parent's id (presence, then absence; another owner's parent id matches none), walked in each order; its statement count one more than row 21 (the batch's facade lookup) | ARCH-04, TEST-05 |
| 28f | A **same-module parent** shown on the child's detail or a write's response (a `@ManyToOne(fetch = LAZY)`; SKILL.md 4.3; golden `Todo.todoList` shown as `todoList {id, name}`) | the detail and every write's response show the parent's name; the child and its parent deleted between the two reads (a transaction deletes both and holds the parent table `ACCESS EXCLUSIVE`; the read waits there, then the deletion commits) answer the child's 404, never a 500, for the GET and for every write that holds no row lock (a status change to the status it has); **assert the status first** (`assertRefusalsAre` checks only the answers with that status, so alone it passes a 500); one gated test per parent lookup when a row shows two parents (golden `TodoReferencesIntegrationTest.getOfATodoWhoseListVanishedBetweenTheReadsIs404`, `aStatusChangeToTheSameStatusOfAVanishingTodoIs404`: red without the entity graph). **On list rows**: each row shows its parent by name (`listRowsShowTheirListsByName`), a child deleted with its parent between the batch and the names' lookup is left out of the page, 200 (`listLeavesOutATodoWhoseListVanishedBetweenTheReads`, red when the mapper keeps the row), and the list's statement count is one more (row 21) | DATA-21, TEST-05 |
| 28g | A **person reference** (an owner, an assignee, who asked; SKILL.md 4.3; golden `Todo.requesterId`) | without tenancy: an unknown account 404 `USER_NOT_FOUND` on the create and on an update that names it, nothing stored; the id echoed, kept by a PUT that re-sends it, replaced by one naming another, cleared by one without it (golden `TodoReferencesIntegrationTest`). An **updatable** reference is checked only when the request changes it: With tenancy: the person removed from the tenant while records name them: the list still shows the records, the GET answers 200 with the person by id and the name `null`, a PUT re-sending the removed member works (200, kept) and DELETE works; a create or a PUT newly naming a removed member, or another tenant's, is 404 `TENANT_MEMBER_NOT_FOUND`, the record unchanged; list rows carry the person's id; re-added, the name is back (golden `TodoRequesterTenancyIntegrationTest`, `anUpdateNamesOnlyACurrentMember`) | TEST-05, TEN-17 |
| 30 | **Upgrading a used database** (a changeset that backfills or changes existing rows; *Upgrading a used database* below) | the project's `UpgradeFromPreviousReleaseIntegrationTest` asserts what the new changesets did to the previous release's seeded rows: every row backfilled (in every tenant, whatever its status), the constraints added hold over them, a PENDING tenant approved afterwards gets its seeds, a SUSPENDED one reactivated goes on where it was, a seeded account works through the API (golden: the reference's and the overlays' `UpgradeFromPreviousReleaseIntegrationTest`) | DATA-13, TEN-34 |
| 31 | `apiDocsNameEveryErrorCode` (SKILL.md 4.9) | the published OpenAPI document names, per endpoint and status, every code it can answer (`$.paths['<path>'].<method>.responses['409'].description` contains `<ENTITY>_IN_USE`, ...); a code added to an endpoint is added there in the same change (golden `TodoListIntegrationTest.apiDocsShowTheConfiguredLimits`, `PlanIntegrationTest`'s docs test) | REST-20 |
| 32 | **The project's first entity** (bootstrap.md 4.2 names them; once per project) | `lockedRowAnswers503` (a write waiting on a held row lock answers 503 `DATABASE_CONTENTION` with `Retry-After`) and the entity-level `apiDocsShowTheConfiguredLimits` (the published limits and codes) | ERR-03, REST-20 |
| 33 | A feature that **reads a fixed metadata key** (a loan's create reads the lending rules) | the class holds the key's `@ResourceLock(..., mode = READ)`: `MetadataIntegrationTest` deletes and restores the row under the write lock, so an unlocked reader fails in a parallel run | META-05 |
| 28d | Member rows another aggregate's delete removes by cascade (a todo's tag rows) | gated: the other delete held uncommitted, the root's delete answers 204 and leaves no rows (golden `todoDeleteRacingATagDeleteSucceeds`); a write that replaces them answers the documented 409 (golden `tagReplaceDroppingATagBeingDeletedIs409`) | DATA-29, TEST-05 |
| 28e | Versioned document (DATA-36; no golden code yet) | supersede keeps the earlier slice; both sides of a boundary (the day before `effective_to` finds the old version, `effective_to` the new); a stored record answers byte for byte the same after a new version; an overlapping version 409; concurrent supersedes store one new version, the others 409; the copy's `to_jsonb` drift test with its no-NULL fixture assertion | DATA-36, DATA-35, TEST-05 |
| 29 | Variant N (golden `TodoTagsIntegrationTest`) | max and max + 1 on create and the link PUT; a repeated id counts once; unknown and foreign ids 404 on both; a malformed id 400 without internals; stale version, missing fields, no token; the version moves only when the set changes; A's PUT leaves the links and refuses `<b>Ids`; list rows and the `<b>Id` filter, a cursor walk under the filter, 2 statements; both deletes (cascade keeps A and its version, or `*_IN_USE`); the gated race of link writes against B's delete (row 23: 404 `<B>_GONE`, no link stored); concurrent link PUTs apply once; the composite FK refusing a cross-owner link through `sql(...)`; `maxItems` in the docs | TEST-05, DATA-05 |

Dropdown: add the tests from `references/dropdown.md`. Files (media piece): add the tests from
`references/media.md`. Variant B replaces 2, 6 and 16–18 with:
GET before create (null object), PUT create, PUT replace, and GET for an unknown parent.

**A first fixed metadata key** (metadata piece; the project's own `MetadataKeys` constant): its seed
changeset (house-review META-05; bootstrap.md's metadata bullet), and the key-agnostic tests the
golden `MetadataIntegrationTest` runs on `TODO_LIST_DEFAULTS`, ported to the key: GET with the
defaults, PUT replaces and bumps the version, a stale version 409, concurrent PUTs with one version
apply once, an invalid value 400 naming the field, an unreadable value and a body without `value` or
`version` 400, a stored unknown field tolerated, a stored missing field defaulted, an unknown key 404
`METADATA_KEY_UNKNOWN`, a deleted row 404 `METADATA_NOT_FOUND` on the admin API **and** on every
write of the feature that reads the key (409/404 as its service answers), 401/403. The class holds the
key's `@ResourceLock`, captures the stored row before each test and restores it after
(`storedMetadata`, `restoreMetadata`; set a value with `storeMetadata`), never deletes and re-inserts
it at version 0: real code reads it (every loan create reads the lending rules).

Per-context configuration (metadata piece, `GET/PUT /<contexts>/{id}/<configuration>`): GET and PUT
with the version (stale 409, unchanged value keeps the version), the context's own rule (409, both
sides of its boundary), an unknown context 404, the value's bounds, 401/403, and that the generic
`/metadata/{key}` answers `METADATA_KEY_UNKNOWN` for the context's key. The context's create seeds
it and its delete removes it (with its counters).

## Upgrading a used database

A changeset that backfills or changes existing rows (a new `NOT NULL` column, a display number for the
rows already there, a per-tenant seed for the tenants that exist) is tested on a database that already
holds the previous release's data, never only on an empty one: on an empty database a backfill that
starts a counter at 0 instead of the highest number, or reaches only the ACTIVE tenants, passes every test.
The test base's `PreviousReleaseDatabase` (core) builds that database for the whole run when
`app.test.upgrade.previous-release` names the release in production (the highest changeset number of
each range it shipped, `<pieces>[,<project>]`: the pieces own 0001-0099, the project 0100 and up, and a
piece's changeset added since the release runs after the project's, so one number can't say what shipped;
`28,105`, or `28` while the project has none of its own): Liquibase applies the changesets up to the
first one the release didn't ship, `src/test/resources/db/upgrade/previous-release-seed.sql`
fills them as a used database holds them, then the application's own run applies the newer changesets
(schema per tenant: each provisioned tenant's schema at that release too, with
`previous-release-tenant-seed.sql`, which the deploy-time `TenantMigrations` then brings up). Every test of
that run works on the upgraded database; `UpgradeFromPreviousReleaseIntegrationTest` (row 30) asserts the
backfills. Run it as `./mvnw verify -Dapp.test.upgrade.previous-release=<pieces>,<project>`.

The seed is realistic rather than tidy: several accounts and one with most of the rows, statuses skewed
as they are in production, every table filled, with tenancy tenants in every status (each with what that
release gave it: the provisioned ones their seeds, a PENDING one none), a member in two tenants and one who
left. Write it against the previous release's tables only, with fixed ids for the rows the test reads.
**When a release ships**, raise the number and add the new tables' rows to the seed. **Before upgrading
a live database**, also run the migration once against a restored copy of it (the runbook's upgrade
section): the seed models production, the copy is production.

## After generating
Run the class. Then **prove one guard can fail** (TEST-09): temporarily remove `@Valid` from the
create method, run test 8 and watch it go red, then restore it. Report the result in one line.
