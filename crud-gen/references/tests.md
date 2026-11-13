# Integration tests (Step 6)

Every generated endpoint ships with its tests (house-review lenses.md TEST-01…18). **Follow the
golden classes**: `~/.claude/skills-reference/src/test/java/com/example/app/todo/TodoIntegrationTest.java`
(cursor list, state action, child of a list) and `TodoListIntegrationTest.java` (offset list,
unique name, dropdown). Generate `{{testRoot}}/<app path>/EntityNameIntegrationTest.java`:
- extends `{{testBase}}` (one shared Spring context; **never** `@MockitoBean` or
  `@TestPropertySource`, which start another context);
- uses the base's client and helpers (`newUser()`, `getAs`/`postAs`/…, `read`, `sql`,
  `statistics()`).

## Conventions
- One behaviour per test, a camelCase sentence name **and** `@DisplayName`.
- No `var`. JSON bodies as text blocks, or built by fixture helpers in the class.
- Each test creates its own data (shared database). Unique values.
- **Setup that fails must fail the test** (TEST-10): fixture helpers assert their own 2xx.
- **Direct database fixtures go through `sql(...)`**, which commits in its own transaction. The pool
  runs with auto-commit off, so a bare `jdbc.update` is rolled back when its connection returns,
  and the test silently runs on data that isn't there.
- **Every refusal asserts the `errorCode`**, on **every** call of the test, not only the first
  (TEST-08). Validation tests assert the field in `failed`; limit tests assert the message with the
  configured number (`"name: must be at most 100 characters"`).
- **Every absence assertion follows a presence assertion** (TEST-16).
- **Fixtures for an order must give a different result under every order and direction**: create
  "Alpha", "Charlie", "Bravo", not rows already in the expected order, or the test passes with
  the sort ignored.
- **Special characters in a query parameter go through `.param(...)`**: MockMvc's
  `get(String)` treats the path as a template and encodes `%25` again.

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
| 9 | Per `@MaxLength` field, **on create and update**: the configured length is accepted (with multi-byte text), length + 1 is 400 with the configured number; the maximum page `size` accepted | TEST-03 |
| 10 | `createWithAScriptTagStoresItAsText` | the documented `{{NotXss}}` behaviour, per free-text shape | TEST-03 |
| 11 | Per unique field: taken on create **and on update** is 409 `*_TAKEN`; uniqueness ignores case and surrounding spaces | DATA-05, TEST-04 |
| 12 | `concurrentCreatesWithTheSameXStoreOne` | latch race: one 201, the rest 409, **one row** in the database | TEST-05 |
| 13 | `concurrent<Action>sWithOneVersionApplyOnce` | latch race on update or a state action with the same version: one 200, the rest 409, the database `version` advanced once | TEST-05 |
| 14 | `everyEndpointNeedsAToken` (and the wrong role per endpoint when roles exist) | 401 `AUTHENTICATION_REQUIRED` / 403 `ACCESS_DENIED` | TEST-02 |
| 15 | `anotherUsersRecordIs404` (owned) | B gets 404 **with the code** on GET, PUT, actions, DELETE of A's record; A's record unchanged | TEST-02, 16 |
| 16 | Cursor list: `cursorBatchesWalkTheWholeListInOrder` | walk every batch with `after`: every row once, in order; batches split **inside a group of equal sort keys** (the id tie-breaker) and, when a key is nullable through a generated column, across that boundary (undated rows last); the last batch `hasMore` false, no `nextCursor` | TEST-11, 12 |
| 16b | Offset list: `listIsOffsetPaged`, `listPastTheLastPageIsEmptyButNavigable` | `pageInfo` fields; the overrun page; size over the configured maximum is 400 | TEST-11, 12 |
| 17 | `cursorFromAnotherSortIsRefused`; a tampered cursor | 400 `REQUEST_PARAMETER_INVALID`, `parameter: after` | REST-08 |
| 18 | Per filter: `filterByXReturnsOnlyMatching` | its matching row present, its non-matching row absent; **range filters include the boundary row** and exclude the next one | TEST-16, TEST-12 |
| 19 | Per domain enum (filter or field; sort enums are covered by rows 16, 17 and 24): `@EnumSource` over every constant | each member works through the real endpoint | TEST-15 |
| 20 | `unconvertibleFilterValuesAre400`; unknown enum in a body; `unknownJsonFieldIs400` | `REQUEST_PARAMETER_INVALID` naming only the field (no `java.` in the body); `REQUEST_UNREADABLE` | REST-06, REST-22 |
| 21 | `listRunsAConstantNumberOfQueries` | `statistics()`: cursor lists and **dropdowns** exactly 1 statement for 1 row and a full batch; a nested member list 2 (the root's ownership check, then the batch); offset lists 1 for a partial page and 2 for a full one (the count), the same for every page size | TEST-06 |
| 22 | Race tests return status **and body** | every 409 of a race asserts its errorCode and that the body has no `uq_`, `fk_`, `SQL`, `Exception`, `java.`: races reach other code paths (`@Version`, the unique index) than the sequential tests | TEST-08, TEST-04 |
| 23 | Parent with children: `childWritesRacingAParentDeleteKeepTheInvariant` | latched child creates into a parent against one delete of it: either the parent is gone with no children or it remains with them; assert the exact outcome the test's gate produces (with a lock gate: every create 201, the delete 409 `PARENT_IN_USE`, all children present), not an either/or. Proves the parent lock (`FOR SHARE`). Lives in the **child's** test class; the parent's `deleteWhileReferencedIs409` too, once the child exists | TEST-05 |
| 24 | Per sort, both directions | every keyset (each sort, each direction, the dropdown's `DESC`) walked to the end | TEST-12 |
| 25b | Range filters | a bound row included and the next value excluded at each end; both bounds equal; an inverted range 400 naming the lower bound | TEST-12 |
| 25c | Calendar dates (`@NotAfterToday`) | today computed **once** from the `Clock` bean before any request: today + tolerance accepted, one day later refused, on create **and** update | JAVA-25 |
| 25d | Optional text / pattern fields | blank and empty stored and returned as `null` | SMELL-27 |
| 25 | `query` limits | `search-query` at its limit and limit + 1 on every list and dropdown | TEST-03 |
| 26 | Entity unit test (only when the entity has state methods) | state methods (`changeStatus`, `moveTo`): transitions, the timestamp rule, the impossible-state guard (`IllegalStateException`) | JAVA-16 |
| 28 | `everyListShapeUsesAnIndex` | for each offered order × scoping filter: `EXPLAIN` (with `enable_seqscan = off`) on seeded rows contains the intended index **by name**. Golden: `TodoIndexIntegrationTest` | DATA-10 |
| 27 | When the golden class has them: `lockedRowAnswers503` (a write against a row locked by another transaction: 503 `DATABASE_CONTENTION` + `Retry-After`, row unchanged) and `apiDocsShowTheLimits` (the configured `maxLength` in the OpenAPI schema) | one per project is enough: generate each only when the project's tests don't have one yet (grep for the test name) | ERR-05, REST-09 |

Dropdown: add the tests from `references/dropdown.md`. Variant B replaces 2, 6 and 16–18 with:
GET before create (null object), PUT create, PUT replace, and GET for an unknown parent.

## After generating
Run the class. Then **prove one guard can fail** (TEST-09): temporarily remove `@Valid` from the
create method, run test 8 and watch it go red, then restore it. Report the result in one line.
