# Dropdown endpoint (Step 4.11): the house dropdown standard

One shape for every dropdown in the API, so the frontend writes **one** dropdown component and
one fetch helper for all of them. house-review checks it as REST-13.

Source: the user's `DropdownCandidate` contract (Espranza), merged with the house decisions of
2026-11-13: `DropdownItem` is a record; the service builds the wrapper; every dropdown accepts one
generic parameter object; **dropdowns are cursor-paged**, so a large set (thousands of cities) is
browsed in batches or narrowed with `query`.

Applies when the entity carries `{{dropdownAnnotation}}` (`@DropdownCandidate`). The shared types
come with bootstrap's `core` piece. **Golden code:**
`~/.claude/skills-reference/src/main/java/com/example/app/common/dropdown/` (the types, with the
annotation's full LLM contract) and, for one entity, `todo/repository/impl/TodoListRepositoryImpl`
(`findDropdownItems`), `todo/service/TodoListService` (`dropdown`),
`todo/controller/TodoListController` (`dropdown`).

## The shape
- `GET {{urlPrefix}}/<resource-plural>/dropdown`; a parent in the path only for a true child
  resource; never named sub-paths (`by-x-id/dropdown`) or loose `@RequestParam`s.
- Parameters: `DropdownFilter extends CursorFilter`: `size` (default and maximum from
  `app.limits.cursor-page`), `after` (the previous batch's `pageInfo.nextCursor`), `status`,
  `sortDirection`, `query` (`@MaxLength("search-query")`). An entity with mandatory or optional
  filters subclasses it (`<Entity>DropdownFilter`), one field per dot-path entry, `@NotNull` for
  mandatory ones.
- Response: `CursorWrapper<DropdownItem>`, items exactly `{value, label}`, `pageInfo {nextCursor,
  hasMore}`; no count.

## How the request adjusts the annotation's defaults

| Parameter | Effect |
|---|---|
| `status` | With an `activeField`: `ACTIVE` (default) → `= TRUE`, `INACTIVE` → `= FALSE`, `ALL` → no predicate. **Without one, anything but `ACTIVE` is a 400** `REQUEST_PARAMETER_INVALID` (`parameter: status`), never silently ignored |
| `sortDirection` | Flips `sortField`'s direction. Each direction is its own keyset (`NAME_ASC`, `NAME_DESC`), so a cursor from one direction is refused in the other |
| `size`, `after` | The batch and the position; over the maximum or a foreign or tampered cursor is a 400 |
| `query` | Only when `searchable`: `TextSearch.contains` on `displayField`. On a non-searchable dropdown it is a 400, never ignored |

## What to generate per entity (naming fixed: `findDropdownItems`, `dropdown`, `dropdown`)
1. **Repository** (Criteria, never `@Query`): a **projection**
   `builder.construct(DropdownItem.class, value, label)` in a `ProjectionQuery`, fetched with the
   injected `keysetPages` (returning a `CursorBatch`) over the keyset
   `(sortField, valueField)`; predicates for mandatory, optional, status and search; scoped like the
   resource's reads (ownership, tenant). Indexes `(scope columns, sortField, valueField)`.
2. **Service:** `CursorWrapper<DropdownItem> dropdown(<Filter> filter)` builds the wrapper from the
   repository's `CursorBatch`; refuses an inapplicable
   `status`; the batch size from `Paging.cursorSize(filter)`. `cacheable = true` only for static
   master data, with `@CacheEvict` on writes and a shared cache when `{{instances}}` is
   `multiple`.
3. **API interface:** `@Operation(summary = "Options for the <label> dropdown")`, the description
   lists the defaults and the batching; `@ApiResponse` 200, 400, 401, 503 (403 only when
   `{{dropdownSecurity}}` checks a role).
4. **Controller:** `dropdown(@Valid <Filter> filter)` returning the service result;
   `{{dropdownSecurity}}` (normally `isAuthenticated()`; ask when the resource is more restricted).
   A dropdown never lists items the caller couldn't fetch.

When the pre-flight finds a resource that other screens pick from (another entity references it)
but no `@DropdownCandidate`, ask whether to add one.

## Tests (golden: `TodoListIntegrationTest`)
- Default order and shape: fixtures in an order different from the expected one; only the caller's
  items; exactly `value` and `label`.
- `sortDirection=DESC` reverses it.
- `query` matches part of the label ignoring case (searchable), or is 400 (not searchable).
- Batches: walk `size=2` with `after` to the end (`hasMore` false, no `nextCursor`).
- A cursor from the other direction, and a tampered cursor: 400 `REQUEST_PARAMETER_INVALID`.
- `size` over the configured maximum: 400 naming `size`.
- `status`: with an active flag, `ACTIVE`/`INACTIVE`/`ALL` behave as above; without one, a 400.
- Mandatory filter missing: 400 naming it. Optional filter: a presence and absence pair.
- No token: 401. Scoped reads: another user's items never appear (a presence check first).
- Cacheable: a write evicts it.
