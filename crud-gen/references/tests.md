# Integration tests (Step 6)

Every generated endpoint ships with its tests (house-review lenses.md TEST-01…18). Generate
`{{testRoot}}/<app path>/EntityNameIntegrationTest.java`:
- extends `{{testBase}}` (one shared Spring context; **never** add `@MockitoBean` or
  `@TestPropertySource`, which start another context);
- uses the base's HTTP client (`{{httpClient}}`: MockMvc or REST Assured) and its helpers
  (`{{loginAs}}(Role)`, `{{createUser}}`).

## Conventions
- Each test checks **one behaviour**, with a camelCase sentence name **and** `@DisplayName`.
- No `var`. JSON bodies as **text blocks** (`"""`), or built by a fixture helper in the class.
- Each test creates its own data (the database is shared; never assume an empty table). Unique
  values (`"name-" + UUID.randomUUID()`).
- **Setup that fails must fail the test** (TEST-10): fixture helpers assert their own 2xx and
  return the created id.
- **Negative tests assert the `errorCode`**, not only the status (TEST-08). Validation tests
  assert the field in `failed`. Each negative payload is otherwise valid, so the layer under test
  is the one that refuses (a test aimed at a service rule must not be stopped by bean validation).
- **Every absence assertion follows a presence assertion** (TEST-16).
- Exact assertions; no `isNumber()` / status-only checks.

## Fixture helpers (in the class)
```java
private String validPayload(String name) {
    return """
            {
              "name": "%s",
              "parentId": "%s"
            }
            """.formatted(name, parentId);
}

private UUID createEntityName(String token, String name) throws Exception {
    // POST, assert 201, read and return the id: never return without checking the status
}
```

## Required tests

| # | Test | Asserts | Rule |
|---|---|---|---|
| 1 | `createReturns201WithTheSavedEntity` | 201, every field echoed, `version` 0, audit fields set | TEST-01 |
| 2 | `getReturnsTheEntity` / `getUnknownIdIs404` | 200 + fields; 404 `ERROR_PREFIX_NOT_FOUND` | TEST-01, 08 |
| 3 | `updateChangesTheFieldsAndBumpsTheVersion` | 200, new values, `version` + 1 | TEST-01 |
| 4 | `updateWithAStaleVersionIs409` | 409 with the stale code; a re-read shows the earlier values | DATA-06, TEST-08 |
| 5 | `deleteReturns204AndTheEntityIsGone` | 204, then GET 404 | TEST-01 |
| 6 | `deleteUnknownIdIs404` | 404 `ERROR_PREFIX_NOT_FOUND` | TEST-08 |
| 7 | `deleteWhileReferencedIs409` (if referenced by other tables) | 409 `ERROR_PREFIX_IN_USE` | ERR |
| 8 | Per required field: `createWithoutXIs400` (parameterized) | 400 `VALIDATION_FAILED`, `failed` names the field | TEST-08 |
| 9 | Per sized field: `createWithXOverTheLimitIs400` | 400 naming the field (`length + 1` chars) | TEST-03 |
| 10 | `createWithAScriptTagIsRefused` (or stored escaped, per `{{NotXss}}` policy) | the documented behaviour, per free-text input shape | TEST-03 |
| 11 | Per unique field: `createWithATakenXIs409` | 409 `ERROR_PREFIX_X_TAKEN`; the body has no constraint name or SQL | DATA-05, TEST-04 |
| 12 | `concurrentCreatesWithTheSameXStoreOne` (unique fields) | N parallel creates through a `CountDownLatch`: exactly one 201, the rest 409, **one row** in the database | TEST-05 |
| 13 | `everyEndpointRefusesTheWrongRole` (parameterized over endpoints × denied roles) | 403 for each role below `{{roleAllowed}}`, and 401 with no token | TEST-02 |
| 14 | `anotherUsersRecordIs404` (when `{{ownerField}}` is set) | user B gets 404 on GET, PUT and DELETE of user A's record; a presence check as A first | TEST-02, 16 |
| 15 | `listIsPagedAndCapped` | create `pageSize + 1` own rows (filtered by a unique marker): page 0 is full with `pageInfo.number` 0, `pageInfo.hasNext` true and `pageInfo.hasPrevious` false; page 1 has ≥ 1 row; `size=1000` gives `pageInfo.size` 100 | TEST-11, TEST-06 |
| 16 | `listPastTheLastPageIsEmptyButNavigable` | an overrun page answers 200 with an empty `response`, `pageInfo.hasNext` false, `pageInfo.hasPrevious` true and the real `pageInfo.totalElements` | TEST-12 |
| 17 | Per filter (and `query`): `filterByXReturnsOnlyMatching` | its own matching row present (presence), its own non-matching row absent | TEST-16 |
| 18 | Per enum filter or field: `@EnumSource` over every constant | each member works through the real endpoint | TEST-15 |
| 19 | `unknownSortIs400` / `unknownJsonFieldIs400` | 400 `REQUEST_PARAMETER_INVALID` / `REQUEST_UNREADABLE` | REST-08, REST-22 |
| 20 | `listRunsAConstantNumberOfQueries` (when the profile has a query counter) | the same statement count for 1 row and for a full page | TEST-06 |

When the entity has a dropdown, add the dropdown tests from `references/dropdown.md`.

Variant B replaces 2, 5 and 15–17 with: GET before create (null object), PUT create, PUT
replace, and GET for an unknown parent (404 for the parent's code).

## After generating
Run the class. Then **prove one guard can fail** (TEST-09): temporarily remove `@Valid` from the
create method, run test 8 and watch it go red, then restore it. Report the result in one line.
