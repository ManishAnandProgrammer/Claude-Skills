# Dropdown endpoint (Step 4.11)

One shape for every dropdown in the API, so the frontend writes one dropdown component and one
fetch helper. **The rules are house-review REST-13** (path, parameters, response, how each
parameter adjusts the annotation's defaults); this file says what to generate.

Applies when the entity carries `{{dropdownAnnotation}}` (`@DropdownCandidate`). The shared types
come with bootstrap's `core` piece. **Golden code:**
`~/.claude/skills-reference/src/main/java/com/example/app/common/dropdown/` (the types, with the
annotation's full contract) and, for one entity, `todo/repository/impl/TodoListRepositoryImpl`
(`findDropdownItems`), `todo/service/TodoListService` (`dropdown`),
`todo/controller/TodoListController` (`dropdown`). An entity with mandatory or optional filters
subclasses `{{DropdownFilter}}` as `<Entity>DropdownFilter`, one field per dot-path entry,
`@NotNull` for mandatory ones.

## What to generate per entity (naming fixed: `findDropdownItems`, `dropdown`, `dropdown`)
1. **Repository** (Criteria, never `@Query`): a **projection**
   `builder.construct(DropdownItem.class, value, label)`, written top to bottom with `Cursor` /
   `Seek` (sorts `NAME_ASC` / `NAME_DESC`) over the keyset `(sortField, valueField)`, returning a
   `CursorBatch`; predicates for mandatory, optional, status and search; scoped like the
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
- When `displayField` isn't unique (customer names repeat; golden todo-list names are unique per
  owner, so the golden can't show it): a group of equal labels split across batches, walked in both
  directions, comes back in id order within the label (the tie-breaker), every item once.
- A cursor from the other direction, and a tampered cursor: 400 `REQUEST_PARAMETER_INVALID`.
- `size` at the configured maximum accepted, one more 400 naming `size` with the configured number
  (golden `dropdownSizeFollowsTheConfiguredMaximum`; tests.md row 9).
- `status`: with an active flag, `ACTIVE`/`INACTIVE`/`ALL` behave as above; without one, a 400 for
  each constant but `ACTIVE`; an unknown value (`ARCHIVED`) is 400 `REQUEST_PARAMETER_INVALID` either
  way (golden `dropdownUnknownStatusIs400`, in both `TagIntegrationTest` and `TodoListIntegrationTest`).
- A `query` matching nothing: an empty batch, `hasMore` false, no `nextCursor` (golden
  `dropdownWithNoMatchIsEmpty`).
- The label at its `@MaxLength`, of control characters and 4-byte letters: the batch it ends
  continues (golden `dropdownCursorOfTheLongestNameIsAccepted`; the cursor limit, SKILL.md 4.2).
- Mandatory filter missing: 400 naming it. Optional filter: a presence and absence pair.
- No token: 401. Scoped reads: another user's items never appear (a presence check first).
- Cacheable: a write evicts it.
