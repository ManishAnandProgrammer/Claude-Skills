# Integration tests (Step 6)

Every generated endpoint ships with its tests (house-review lenses.md TEST-01…18). **Follow the
golden classes**: `~/.claude/skills-reference/src/test/java/com/example/app/todo/TodoIntegrationTest.java`
(cursor list, state action, child of a list) and `TodoListIntegrationTest.java` (offset list,
unique name, dropdown). Generate `{{testRoot}}/<app path>/EntityNameIntegrationTest.java`:
- extends `{{testBase}}` (one shared Spring context; **never** `@MockitoBean` or
  `@TestPropertySource`, which start another context);
- uses the base's client and helpers (`newUser()`, `newAdmin()` with the metadata piece,
  `getAs`/`postAs`/…, `read`, `sql`, `statistics()`, `randomIds(n)`).

## Conventions
The test rules are house-review's TEST-xx (lenses.md): one behaviour per test with `@DisplayName`,
own data, shared state reset with `@ResourceLock`, today computed once (TEST-07); setup asserts its
2xx and direct fixtures go through `sql(...)` (TEST-10); every refusal asserts its `errorCode`, on
every call (TEST-08); an absence follows a presence (TEST-16); order fixtures that differ under every
order (TEST-10); query parameters with special characters through `.param(...)`, and a 403 test with
a valid body (TEST-07). Limit tests assert the message with the configured number
(`"name: must be at most 100 characters"`). No `var`; JSON as text blocks or fixture helpers.

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
| 9 | Per **bounded field** (`@MaxLength`, `@Min`/`@ConfiguredMax`, `@ConfiguredMaxSize`, `@LatestDate`, a pattern), **on create and on update**, both sides of each bound: the limit accepted (multi-byte text at a length limit), one past it 400 with the configured number; the maximum page `size` accepted | TEST-03 |
| 10 | `createWithAScriptTagStoresItAsText` | the documented `{{NotXss}}` behaviour, per free-text shape | TEST-03 |
| 11 | Per unique field: taken on create **and on update** is 409 `*_TAKEN`; uniqueness ignores case and surrounding spaces | DATA-05, TEST-04 |
| 12 | `concurrentCreatesWithTheSameXStoreOne` | latch race: one 201, the rest 409, **one row** in the database | TEST-05 |
| 13 | `concurrent<Action>sWithOneVersionApplyOnce` | latch race on update or a state action with the same version: one 200, the rest 409, the database `version` advanced once | TEST-05 |
| 13b | Racers on **different rows that share one global row** (two "make default" calls on two different records) | they may both succeed one after the other, correctly: assert the **invariant** (exactly one default row, one of the 200s; every refusal its code), never "exactly one 200" (flaky) | TEST-05 |
| 14 | `everyEndpointNeedsAToken` (and the wrong role per endpoint when roles exist) | 401 `AUTHENTICATION_REQUIRED` / 403 `ACCESS_DENIED` | TEST-02 |
| 15 | `anotherUsersRecordIs404` (owned) | B gets 404 **with the code** on GET, PUT, actions, DELETE of A's record; A's record unchanged | TEST-02, 16 |
| 16 | Cursor list: `cursorBatchesWalkTheWholeListInOrder` | walk every batch with `after`: every row once, in order; batches split **inside a group of equal sort keys** (the id tie-breaker) and, when a key is nullable through a generated column, across that boundary (undated rows last); the last batch `hasMore` false, no `nextCursor` | TEST-11, 12 |
| 16b | Offset list: `listIsOffsetPaged`, `listPastTheLastPageIsEmptyButNavigable` | `pageInfo` fields; the overrun page; size over the configured maximum is 400 | TEST-11, 12 |
| 17 | `cursorFromAnotherSortIsRefused`; a tampered cursor | 400 `REQUEST_PARAMETER_INVALID`, `parameter: after` | REST-08 |
| 18 | Per filter: `filterByXReturnsOnlyMatching` | its matching row present, its non-matching row absent; **range filters include the boundary row** and exclude the next one | TEST-16, TEST-12 |
| 18b | `ids` (golden `filterByIdsReturnsOnlyMatching`, `idsAreBounded`, `filterByIdsRunsAConstantNumberOfQueries`) | the asked rows in the list's order, an unasked row absent (after a presence check), unknown and another user's ids simply absent; the maximum size accepted, one more 400 `VALIDATION_FAILED` with the configured number, a malformed id 400 `REQUEST_PARAMETER_INVALID` naming `ids`; the statement count of row 21 for 1 id and many | REST-23 |
| 19 | Per domain enum (filter or field; sort enums are covered by rows 16, 17 and 24): `@EnumSource` over every constant | each member works through the real endpoint | TEST-15 |
| 20 | `unconvertibleFilterValuesAre400`; unknown enum in a body; `unknownJsonFieldIs400` | `REQUEST_PARAMETER_INVALID` naming only the field (no `java.` in the body); `REQUEST_UNREADABLE` | REST-06, REST-22 |
| 21 | `listRunsAConstantNumberOfQueries` | `statistics()`: cursor lists and **dropdowns** exactly 1 statement for 1 row and a full batch; a nested member list 2 (the root's ownership check, then the batch); offset lists 1 for a partial page and 2 for a full one (the count), the same for every page size | TEST-06 |
| 22 | Race tests return status **and body** | every 409 of a race asserts its errorCode and that the body has no `uq_`, `fk_`, `SQL`, `Exception`, `java.`: races reach other code paths (`@Version`, the unique index) than the sequential tests | TEST-08, TEST-04 |
| 23 | Parent with children: `childWritesRacingAParentDeleteKeepTheInvariant` | latched child creates into a parent against one delete of it: either the parent is gone with no children or it remains with them; assert the exact outcome the test's gate produces (with a lock gate: every create 201, the delete 409 `PARENT_IN_USE`, all children present), not an either/or. Proves the parent lock (`FOR SHARE`). Lives in the **child's** test class; the parent's `deleteWhileReferencedIs409` too, once the child exists. Not applicable when the parent's delete is refused for another reason (the default record can't be deleted): then row 23b | TEST-05 |
| 23b | A write that **checks another row under `FOR SHARE`** (ship an order only while its account is active) | raced against a change of that row (the account is suspended), **gated** so the change really waits on the lock (hold it in the test's own transaction, or a latch, then `awaitLockWaiters`): the write succeeds and the change applies after it, or the change wins and the write is 409. Removing the lock turns the test red (TEST-09) | TEST-05 |
| 24 | Per sort, both directions | every keyset (each sort, each direction, the dropdown's `DESC`) walked to the end | TEST-12 |
| 25 | `query` limits | `search-query` at its limit and limit + 1 on every list and dropdown | TEST-03 |
| 25b | Range filters | a bound row included and the next value excluded at each end; both bounds equal; an inverted range 400 naming `<x>RangeOrdered` | TEST-12 |
| 25c | Calendar dates (`@NotAfterToday`) | today computed **once** from the `Clock` bean before any request: today + tolerance accepted, one day later refused, on create **and** update | JAVA-25 |
| 25d | Optional text / pattern fields | blank and empty stored and returned as `null` | SMELL-27 |
| 25e | Immutable resources (no PUT, PATCH or DELETE: an issued invoice) | each answers 405 `REQUEST_METHOD_NOT_ALLOWED`, and the record is unchanged | TEST-01 |
| 26 | Entity unit test (only when the entity has state methods) | state methods (`changeStatus`, `moveTo`): transitions, the timestamp rule, the impossible-state guard (`IllegalStateException`) | JAVA-16 |
| 28 | `everyScopedOrderReadsAnIndexInOrder` | for each offered order × scoping filter: `EXPLAIN` (with `enable_seqscan = off`) on seeded rows contains the intended index **by name**. Golden: `TodoIndexIntegrationTest` | DATA-10 |
| 29 | Variant N (golden `TodoTagsIntegrationTest`) | max and max + 1 on create and the link PUT; a repeated id counts once; unknown and foreign ids 404 on both; a malformed id 400 without internals; stale version, missing fields, no token; the version moves only when the set changes; A's PUT leaves the links and refuses `<b>Ids`; list rows and the `<b>Id` filter, a cursor walk under the filter, 2 statements; both deletes (cascade keeps A and its version, or `*_IN_USE`); the gated race of link writes against B's delete (presence in the responses, then absence); concurrent link PUTs apply once; a rename of B isn't blocked; the composite FK refusing a cross-owner link through `sql(...)`; `maxItems` in the docs | TEST-05, DATA-05 |

Dropdown: add the tests from `references/dropdown.md`. Variant B replaces 2, 6 and 16–18 with:
GET before create (null object), PUT create, PUT replace, and GET for an unknown parent.

Per-context configuration (metadata piece, `GET/PUT /<contexts>/{id}/<configuration>`): GET and PUT
with the version (stale 409, unchanged value keeps the version), the context's own rule (409, both
sides of its boundary), an unknown context 404, the value's bounds, 401/403, and that the generic
`/metadata/{key}` answers `METADATA_KEY_UNKNOWN` for the context's key. The context's create seeds
it and its delete removes it (with its counters).

## After generating
Run the class. Then **prove one guard can fail** (TEST-09): temporarily remove `@Valid` from the
create method, run test 8 and watch it go red, then restore it. Report the result in one line.
