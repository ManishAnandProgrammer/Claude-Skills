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
  `setUploadPolicy`, `restoreUploadPolicy`; with tenancy `newTenant()`, `newUserIn(tenant)`,
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
a valid body (TEST-07). Limit tests assert the message with the configured number
(`"name: must be at most 100 characters"`). No `var`; JSON as text blocks or fixture helpers.

**Shared tables** (a catalogue, admin-managed types, employees: no `owner_id`, SKILL.md Step 1): the
shared database holds every other test's rows, so each list test scopes its calls by a filter only
its own rows match (a fresh parent id, a unique `query` token) and never asserts a page's absolute
contents or totals, nor that a table is empty; row 15 doesn't apply (nobody owns the record), and the
golden "a list shows only yours" test has no counterpart: drop it (with tenancy, the
`TenantIsolationIntegrationTest` covers who sees what). Row 14
carries the access decision: a plain USER gets 403 `ACCESS_DENIED` **with a valid body** on every
write (and on every read the decision restricts), the record unchanged; the writing role succeeds
(golden `MetadataIntegrationTest.onlyAdminsReachTheApi`, `UploadPolicyIntegrationTest`). Row 28 on a
shared table: seeded rows are seen by every unscoped list and dropdown, so give them `uuidv7()` ids
(the NEWEST order is id order; `gen_random_uuid()` breaks it), a unique name prefix, and delete them
in an `@AfterAll`. Seed other scopes beside the tested one so that it is **at most about 1% of the
seeded rows** (100 books of the tested author against 200 authors × 50 others): at 7% the planner
rightly walked `pk_books` backwards with a filter for "one author's books, NEWEST" instead of
`idx_books_author_id_id`, and the test read as a missing index. The whole-catalogue orders have no
scope column: the index test's scope assertion loops over the scope columns (none for them), never
`contains(scope)` with an empty list, which AssertJ refuses (golden `TodoIndexIntegrationTest`).

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
| 8 | `createWithoutRequiredFieldsIs400`; `writesWithoutAVersionAre400` | 400 `VALIDATION_FAILED`, `failed` names each field | TEST-08 |
| 9 | Per **bounded field** (list them first: every field × create and update, an optional one included, and tick each off) (`@MaxLength`, `@Min`/`@ConfiguredMax`, `@ConfiguredMaxSize`, `@LatestDate`, a pattern), **on create and on update**, both sides of each bound: the limit accepted (multi-byte text at a length limit), one past it 400 with the configured number; the maximum page `size` accepted | TEST-03 |
| 10 | `createWithAScriptTagStoresItAsText` | the documented `{{NotXss}}` behaviour, per free-text shape | TEST-03 |
| 11 | Per unique field: taken on create **and on update** is 409 `*_TAKEN`, the body without internals and a re-read showing the record unchanged (one row with the value); uniqueness ignores case and surrounding spaces. A **format-validated** field (`@Email`, a `@Pattern`) refuses surrounding spaces with 400 before uniqueness is reached: test the case only, and its `@Schema` says "ignoring case" without "and surrounding spaces" | DATA-05, TEST-04 |
| 12 | `concurrentCreatesWithTheSameXStoreOne` | latch race: one 201, the rest 409, **one row** in the database | TEST-05 |
| 13 | `concurrent<Action>sWithOneVersionApplyOnce` | latch race on update or a state action with the same version: one 200, the rest 409, the database `version` advanced once; every write endpoint that takes a version, the state actions (approve, reject) included | TEST-05 |
| 13b | Racers on **different rows that share one global row** (two "make default" calls on two different records) | they may both succeed one after the other, correctly: assert the **invariant** (exactly one default row, one of the 200s; every refusal its code), never "exactly one 200" (flaky) | TEST-05 |
| 14 | `everyEndpointNeedsAToken` (and the wrong role per endpoint when roles exist) | 401 `AUTHENTICATION_REQUIRED` / 403 `ACCESS_DENIED` | TEST-02 |
| 15 | `anotherUsersRecordIs404` (owned) | B gets 404 **with the code** on GET, PUT, actions, DELETE of A's record; A's record unchanged | TEST-02, 16 |
| 15b | Tenancy (`{{tenancy}}` not `none`), in its own class `EntityNameTenantIsolationIntegrationTest` | a member of another tenant (`newUserIn(newTenant())`) gets 404 **with the code** on GET, PUT, actions, DELETE; the record unchanged; that tenant's list doesn't show it, nor the reverse Records shared by the whole tenant (no owner): scope both tenants' lists by a unique `query` token in the records' names, and assert presence and absence in the same call | TEN-13 |
| 15c | Row mode (`{{tenancy}}` = `row`), in the same class | each **native query** of the entity's repository, run in the other tenant's scope (`TenantScope.callAs(other.id(), ...)` around the service or repository call), returns none of the caller's rows and changes none (RLS, not Hibernate, filters it); the catalogue guard `RowSecurityCatalogIntegrationTest` (once per project, never per entity) runs green with the new table: its policy forced, its keys carrying `tenant_id`, its entity a `TenantOwnedEntity`. A table that is the platform's is added to its `PLATFORM_TABLES` with the reason | TEN-30, TEN-32, TEN-33 |
| 16 | Cursor list: `cursorBatchesWalkTheWholeListInOrder` | walk every batch with `after`: every row once, in order; batches split **inside a group of equal sort keys** (the id tie-breaker) and, when a key is nullable through a generated column, across that boundary (undated rows last); the last batch `hasMore` false, no `nextCursor`. Per **text sort key** (a dropdown's label included): a batch ending on a value at its `@MaxLength` of control characters (`\u0001`, escaped in six bytes in the cursor's JSON) and 4-byte letters (`𐌰`) continues (golden `TodoListIntegrationTest.dropdownCursorOfTheLongestNameIsAccepted`; the cursor limit, SKILL.md 4.2) | TEST-11, 12, REST-08 |
| 16b | Offset list: `listIsOffsetPaged`, `listPastTheLastPageIsEmptyButNavigable` | `pageInfo` fields; the overrun page; size over the configured maximum is 400 | TEST-11, 12 |
| 17 | `cursorFromAnotherSortIsRefused`; a tampered cursor | 400 `REQUEST_PARAMETER_INVALID`, `parameter: after` | REST-08 |
| 18 | Per filter: `filterByXReturnsOnlyMatching` | its matching row present, its non-matching row **in the same scope** absent (a test scoped to one row passes with the filter ignored); **range filters include the boundary row** and exclude the next one | TEST-16, TEST-12 |
| 18b | `ids` (golden `filterByIdsReturnsOnlyMatching`, `idsAreBounded`, `filterByIdsRunsAConstantNumberOfQueries`) | the asked rows in the list's order, an unasked row absent (after a presence check), unknown and another user's ids simply absent; the maximum size accepted, one more 400 `VALIDATION_FAILED` with the configured number, a malformed id 400 `REQUEST_PARAMETER_INVALID` naming `ids`; the statement count of row 21 for 1 id and many | REST-23 |
| 19 | Per domain enum (filter or field; sort enums are covered by rows 16, 17 and 24): `@EnumSource` over every constant | each member works through the real endpoint | TEST-15 |
| 20 | `unconvertibleFilterValuesAre400`; unknown enum in a body; `unknownJsonFieldIs400` | `REQUEST_PARAMETER_INVALID` naming only the field (no `java.` in the body); `REQUEST_UNREADABLE` | REST-06, REST-22 |
| 21 | `listRunsAConstantNumberOfQueries` | `statistics()`: cursor lists and **dropdowns** exactly 1 statement for 1 row and a full batch; a nested member list 2 (the root's ownership check, then the batch); offset lists 1 for a partial page and 2 for a full one (the count), the same for every page size; **plus one per lookup the service makes before the query** (the caller's own row in another module, such as their employee record; the ids of a scope from another module's facade, such as a manager's team) **and one for the batch's parent-name lookup after it** (SKILL.md 4.3, the same module's parent or another module's: golden `PlanIntegrationTest.listRunsAConstantNumberOfQueries`, 2): say which in the test | TEST-06 |
| 22 | Race tests return status **and body** | every 409 of a race asserts its errorCode and that the body has no `uq_`, `fk_`, `SQL`, `Exception`, `java.`: races reach other code paths (`@Version`, the unique index) than the sequential tests | TEST-08, TEST-04 |
| 23 | Parent with children: `childWritesRacingAParentDeleteKeepTheInvariant` (golden `TodoIntegrationTest` names it `createsRacingTheListDeleteNeverOrphanATodo`), **one per foreign key** that children are written through | child creates held at their insert while the parent is deleted (`createsHeldAtTheirInsert(<children table>, create, delete)` in the base class: a `share mode` lock on the child table, held in the test's own transaction, lets reads and foreign key checks pass and holds every insert; `awaitLockWaiters`, then the delete, then the gate opens): assert the **exact** outcome, the delete 204, every create 404 `<PARENT>_GONE` without internals (never `*_IN_USE`, never 500), no child left (golden `TodoIntegrationTest`, `TimeEntryIntegrationTest`, `TodoTagsIntegrationTest`). Proves the insert side of the foreign key's mapping (DATA-33); nothing locks the parent (DATA-29). A child update that **moves** it to another parent gets the same race on the update side (golden `movesRacingTheListDeleteKeepEveryTodoInItsList`: every move 404 `<PARENT>_GONE`, each child still under its old parent). Moves of **one** child queue on its row lock, and those behind the first read the parent after the delete: they answer `<PARENT>_NOT_FOUND`, so assert 404 with either code there, and nothing moved. **When the child's write locks the parent** (SKILL.md Step 1's locked view, row 23b), the delete waits for the held writes instead: `writesHeldWhileTheParentWaits(<children table>, writes, delete)` in the base class holds the writes at their insert (one holds the parent's lock, the others wait for it: `HELD_CREATES` lock waiters), submits the delete on its own thread, awaits it as one more waiter and **asserts it is still waiting under the gate**, the assertion that goes red without the lock; then the gate opens. Assert every write 201 and the delete 409 `<PARENT>_IN_USE`, the children stored and the parent still there (golden `PlanIntegrationTest.createsRacingTheTodoDeleteAreKeptAndTheDeleteIsRefused`). Lock waits aren't FIFO: the delete may get the lock between two writes; the outcome holds anyway (the first write committed, so the delete finds a child), so assert the **set** of outcomes, never their order. `<PARENT>_GONE` is unreachable on that write: keep its mapping, don't document it on that endpoint. Every wait has a deadline (`awaitLatch`, `within`, `lockWaitDeadline()`), so a broken gate fails instead of hanging. **Self-referencing parent** (an employee's manager is an employee): a table gate on the children blocks the parent's own delete too, so gate on another foreign key's parent row instead (the child's `users` row held `FOR UPDATE`: the create waits at its insert's check of that key). Lives in the **child's** test class; the parent's `deleteWhileReferencedIs409` too, once the child exists. Not applicable when the parent's delete is refused for another reason (the default record can't be deleted): then row 23b | TEST-05 |
| 23b | A write that **checks another row under that row's lock** (a cap, or ship an order only while its account is active: the account's `findForUpdate…`, DATA-29) | raced against a change of that row (the account is suspended), **gated** so the change really waits on the lock: for a parent's state, `writesHeldWhileTheParentWaits(<children table>, List.of(write), change)` with **one** held write (with several, the change may get the lock between them and the later writes are 409: correct, but no fixed outcome to assert). The end state is the same with and without the lock (one write, the change applied), so what proves the lock is the helper's "the change is still waiting while the gate holds"; then the write 201, the change 200 after it, and the next write refused with the rule's code (golden `PlanIntegrationTest.completionRacingACreateWaitsForIt`, 409 `PLAN_TODO_DONE`). Plus the rule sequentially, both sides (golden `planForADoneTodoIs409`). Removing the lock turns the test red (TEST-09) | TEST-05 |
| 24 | Per sort, both directions | every keyset (each sort, each direction, the dropdown's `DESC`) walked to the end | TEST-12 |
| 25 | `query` limits | `search-query` at its limit and limit + 1 on every list and dropdown | TEST-03 |
| 25b | Range filters | a bound row included and the next value excluded at each end; both bounds equal; an inverted range 400 naming `<x>RangeOrdered` | TEST-12 |
| 25c | Calendar dates (`@NotAfterToday`, `@EarliestDate`, `@LatestDate`) | today computed **once** from the `Clock` bean before any request: today + tolerance accepted, one day later refused, on create **and** update, and on a state action that records a day (returned on), where a day before the record's own start is 400 with its code; the configured earliest and latest dates accepted and the day beyond each refused 400, a year PostgreSQL can't hold (`-300000-01-01`) refused 400, never 500; the same for date filters | JAVA-25, REST-09 |
| 25d | Optional text / pattern fields | blank and empty stored and returned as `null` | SMELL-27 |
| 25f | A rule from configuration on an update (a maximum length of a loan, a file count) | with the limit lowered below an existing record: an update that changes another field succeeds, one that changes the rule's inputs is refused with its code; both sides of the limit on create (golden `TodoAttachmentsIntegrationTest`'s shrunk policy) | SMELL-27, TEST-03 |
| 25e | Immutable resources (no PUT, PATCH or DELETE: an issued invoice) | each answers 405 `REQUEST_METHOD_NOT_ALLOWED`, and the record is unchanged | TEST-01 |
| 26 | Entity unit test (only when the entity has state methods) | state methods (`changeStatus`, `moveTo`): transitions, the timestamp rule, the impossible-state guard (`IllegalStateException`) | JAVA-16 |
| 28 | `everyScopedOrderReadsAnIndexInOrder` | for each offered order × scoping filter, the `ids` lookup included: `EXPLAIN` (with `enable_seqscan = off`) on seeded rows contains the intended index **by name**, and no sort step where the index gives the order. The SQL mirrors the statement the repository issues (same predicates, same order, the seek's `>` on later batches; copy it from the statement log when in doubt), since a hand-written query that differs proves nothing about the real one. A list whose rows show a parent's name explains **both** statements of SKILL.md 4.3: the batch query, and the second step's `<parents> where id in (...)` (its primary key; no join exists to explain). A scope that is a **low-cardinality status** (active loans): seed it at a realistic fraction (1 in 25, not 1 in 10), place a later batch's seek position inside the scope, and when the planner rightly walks the order's own index (the active loans are the newest, so the primary key serves NEWEST), assert that index and keep no status index; say why in the changeset. Later batches add the seek's predicate exactly as `Seek.after` writes it, the leading `>=` bound included, and assert the sort column in `Index Cond` (golden rows "a later batch": without that bound a later batch walks the index from its start); the `ids` lookups copy `TodoIndexIntegrationTest.everyIdsLookupProbesAnIndexById`. Shared tables: the paragraph above. Golden: `TodoIndexIntegrationTest` | DATA-10 |
| 28b | No overlapping ranges (an exclusion constraint, DATA-35; golden `PlanIntegrationTest`) | overlap on the first day, the last day, inside and around an existing range: 409 `<ENTITY>_OVERLAPS` without internals; the day before and after accepted; another scope's same range accepted; concurrent overlapping creates store exactly one, the others 409 `<ENTITY>_OVERLAPS` or 503 `DATABASE_CONTENTION` (a deadlock in the constraint: no scope lock), never 500; when the writes lock a parent for a state rule, race creates under two parents of the constraint's scope (golden `concurrentOverlappingCreatesStoreOne`: two todos of one owner), since one parent's writes queue | DATA-35, TEST-05 |
| 28c | A parent in another module (ARCH-04; golden `PlanIntegrationTest`) | an unknown and another owner's parent id: 404 with the parent module's code, nothing stored; the parent's delete while children exist 409 (the child module maps the foreign key); row 23's gated race through the facade (unlocked view: 404 `<PARENT>_GONE` from the child module's write-side mapping; locked view, golden: every write 201, the delete 409, and row 23b); a parent deleted with a child between the child's two reads: the list leaves the row out (200), the single GET answers the child's 404 (golden `listLeavesOutAPlanWhoseTodoVanishedBetweenTheReads`, `getOfAPlanWhoseTodoVanishedBetweenTheReadsIs404`: the deletion holds the parent table `ACCESS EXCLUSIVE`, which the first statement passes and the second waits for); a list filtered by the parent's id (presence, then absence; another owner's parent id matches none), walked in each order; its statement count one more than row 21 (the batch's facade lookup) | ARCH-04, TEST-05 |
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

## After generating
Run the class. Then **prove one guard can fail** (TEST-09): temporarily remove `@Valid` from the
create method, run test 8 and watch it go red, then restore it. Report the result in one line.
