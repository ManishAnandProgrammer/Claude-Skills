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
| 9 | Per `@MaxLength` field: the configured length is accepted (with multi-byte text), length + 1 is 400 with the configured number | TEST-03 |
| 10 | `createWithAScriptTagStoresItAsText` | the documented `{{NotXss}}` behaviour, per free-text shape | TEST-03 |
| 11 | Per unique field: taken on create **and on update** is 409 `*_TAKEN`; uniqueness ignores case and surrounding spaces | DATA-05, TEST-04 |
| 12 | `concurrentCreatesWithTheSameXStoreOne` | latch race: one 201, the rest 409, **one row** in the database | TEST-05 |
| 13 | `concurrent<Action>sWithOneVersionApplyOnce` | latch race on update or a state action with the same version: one 200, the rest 409, the database `version` advanced once | TEST-05 |
| 14 | `everyEndpointNeedsAToken` (and the wrong role per endpoint when roles exist) | 401 `AUTHENTICATION_REQUIRED` / 403 `ACCESS_DENIED` | TEST-02 |
| 15 | `anotherUsersRecordIs404` (owned) | B gets 404 **with the code** on GET, PUT, actions, DELETE of A's record; A's record unchanged | TEST-02, 16 |
| 16 | Cursor list: `cursorBatchesWalkTheWholeListInOrder` | walk every batch with `after`: every row once, in order, across a null-key boundary (undated rows last); the last batch `hasMore` false, no `nextCursor` | TEST-11, 12 |
| 16b | Offset list: `listIsOffsetPaged`, `listPastTheLastPageIsEmptyButNavigable` | `pageInfo` fields; the overrun page; size over the configured maximum is 400 | TEST-11, 12 |
| 17 | `cursorFromAnotherSortIsRefused`; a tampered cursor | 400 `REQUEST_PARAMETER_INVALID`, `parameter: after` | REST-08 |
| 18 | Per filter: `filterByXReturnsOnlyMatching` | its matching row present, its non-matching row absent; **range filters include the boundary row** and exclude the next one | TEST-16, TEST-12 |
| 19 | Per enum (filter or field): `@EnumSource` over every constant | each member works through the real endpoint | TEST-15 |
| 20 | `unconvertibleFilterValuesAre400`; unknown enum in a body; `unknownJsonFieldIs400` | `REQUEST_PARAMETER_INVALID` naming only the field (no `java.` in the body); `REQUEST_UNREADABLE` | REST-06, REST-22 |
| 21 | `listRunsAConstantNumberOfQueries` | `statistics()`: the same statement count for 1 row and a full batch (cursor lists: exactly 1) | TEST-06 |

Dropdown: add the tests from `references/dropdown.md`. Variant B replaces 2, 6 and 16–18 with:
GET before create (null object), PUT create, PUT replace, and GET for an unknown parent.

## After generating
Run the class. Then **prove one guard can fail** (TEST-09): temporarily remove `@Valid` from the
create method, run test 8 and watch it go red, then restore it. Report the result in one line.
