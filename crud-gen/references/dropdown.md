# Dropdown endpoint (Step 4.11)

One shape for every dropdown in the API, so the frontend writes one dropdown component and one
fetch helper. **The rules are house-review REST-13** (path, parameters, response, how each
parameter adjusts the annotation's defaults); this file says what to generate.

Applies when the entity carries `{{dropdownAnnotation}}` (`@DropdownCandidate`). The shared types
come with bootstrap's `core` piece. **Golden code:**
`~/.claude/skills-reference/src/main/java/com/example/app/common/dropdown/` (the types, with the
annotation's full contract) and, for one entity, `todo/repository/impl/TodoListRepositoryImpl`
(`findDropdownItems`), `todo/service/TodoListService` (`dropdown`),
`todo/controller/TodoListController` (`dropdown`), `common/dropdown/DropdownFor`. An entity with mandatory or
optional filters subclasses `{{DropdownFilter}}` as `<Entity>DropdownFilter`, one field per dot-path entry,
`@NotNull` for mandatory ones. **The golden dependent dropdown** is the todo dropdown under its list
(`GET /todos/dropdown?for=PLAN_CREATE_OWN&todoListId=`): `todo/dto/request/TodoDropdownFilter` (the parent
`@NotNull`), `TodoRepositoryImpl.findDropdownItems` (the parent a plain condition beside the policy's scope),
`TodoService.dropdown`, changeset `0034-todos-dropdown.sql` (its index: the parent and the owner first), and its
tests `todo/TodoDropdownIntegrationTest`.

**One dropdown per resource, named by the action it serves** (house-review AUTHZ-08): every screen that picks the
resource (an attendance sheet, a fees report, a todo's create) calls the same endpoint with a required
`for=<Permission>`: the permission of the action the options feed. The server refuses it (403 `ACCESS_DENIED`)
unless the caller holds it, narrows the options to its reach (`Permission.reach()`), and answers 400
`DROPDOWN_REACH_UNSUPPORTED` at `for` for a reach this dropdown doesn't list. Ask the developer which reaches the
dropdown lists (an owned entity: `OWN`; a shared root: `ALL`; `ASSIGNED`: what assigns them) and which permissions
its screens will name (recorded in *Decisions per entity*); never a dropdown per screen, never a READ permission of
its own, never public. **A dropdown stays one query:** the `for` check is in memory, the reach is one more
condition of the same Criteria query (none for `ALL`, the owner column for `OWN`, one indexed join for `ASSIGNED`),
and a **dependent dropdown** (classes of a school, a todo list's todos) takes its parent id as a mandatory filter,
a plain condition of that same `WHERE`, with **no separate parent check and no walk up the chain**: an
out-of-reach parent yields an empty page, and nothing leaks. The write the options feed checks everything again.

## What to generate per entity (naming fixed: `findDropdownItems`, `dropdown`, `dropdown`)
1. **Repository** (Criteria, never `@Query`): a **projection**
   `builder.construct(DropdownItem.class, value, label)`, written top to bottom with `Cursor` /
   `Seek` (sorts `NAME_ASC` / `NAME_DESC`) over the keyset `(sortField, valueField)`, returning a
   `CursorBatch`; predicates for mandatory, optional, status and search; scoped like the
   resource's reads (ownership, tenant). Indexes `(scope columns, sortField, valueField)`. When
   `sortField` isn't the `displayField` (a display order), the item carries no sort key to write the
   cursor from: project a row record of the repository's own (`<Entity>DropdownRow(value, label,
   sortKey)`), write the cursor from it, and map the batch to `DropdownItem` before it leaves the
   repository; the cursor's key is then the sort column's (its size in `app.limits.text.cursor`, 4.2,
   when it is text).
2. **Service:** `CursorWrapper<DropdownItem> dropdown(Permission action, <Filter> filter)` first calls
   `DropdownFor.requireReach(action, currentUser.permissions(), EnumSet.of(<the reaches it lists>))`, passes the reach's
   condition (the caller's id for `OWN`) to the repository, and builds the wrapper from the repository's
   `CursorBatch`; refuses an inapplicable
   `status`; the batch size from `Paging.cursorSize(filter)`. `cacheable = true` only when the
   developer asked for it (SKILL.md Step 1, *Ask, don't guess*), with `@CacheEvict` on writes and a shared cache when `{{instances}}` is
   `multiple`.
3. **API interface:** `@Operation(summary = "Options for the <label> dropdown")`, the description
   lists the defaults, the batching and what `for` does; `for` is a parameter of the interface method
   (`@Parameter(name = DropdownFor.PARAMETER, required = true, example = "<a permission its screens name>")`, its
   schema the Permission enum); `@ApiResponse` 200, 400 (`DROPDOWN_REACH_UNSUPPORTED`, `for` missing or
   unknown, then the filter's), 401, 403 (`ACCESS_DENIED`: the permission `for` names isn't held), 503.
4. **Controller:** `@SignedIn`, `dropdown(@RequestParam(DropdownFor.PARAMETER) Permission action, @Valid <Filter>
   filter)` returning the service result (`for` is a Java keyword, so it is a parameter of its own, never a filter
   property). A dropdown never lists items outside the action's reach.

When the pre-flight finds a resource that other screens pick from (another entity references it)
but no `@DropdownCandidate`, ask whether to add one.

## Options a rule can refuse (a write's selectable options)
Generated only when the developer asks for it (SKILL.md Step 1: **ask which**, never assume): a write's screen
where a domain rule can refuse an option **right now** (a done todo for a new plan, a withdrawn or already lent book
for a loan, a driver already assigned, a room booked at the asked time) and the screen shows it **disabled with its
reasons**. The other answer, a plain filter on the shared dropdown (`free=true`, the refused ones left out), stays
the developer's choice per screen; record the answer in *Decisions per entity*. House-review AUTHZ-08 has the rules.
**Golden:** a new plan's todos, `GET /plans/todo-options?todoListId=` (`PlanService.todoOptions`,
`PlanPolicy.create`, `Todos.getOwnedTodoViewBatch`, `common/dropdown/SelectableDropdownItem`, tests
`planning/PlanOptionsIntegrationTest`).

1. **Where:** the write's own module, beside the write (`GET /<write's resources>/<resource>-options`): its flags
   are that write's rules, which the resource's module can't call. `@Requires(<the write's permission>)`, no
   `for` (it serves that one action); the reach is the write's (the caller's own records for `OWN`).
2. **Shape:** `CursorWrapper<SelectableDropdownItem>`, each `{value, label, selectable}`, `selectable` an
   `AllowedAction` from `AllowedActionMapper.toAllowedAction("<action>", <the write's permission>, policy.<write>(...))`: **the policy method
   the write calls** (the write calls `policy.<write>(...).orThrow()` before its own guards), so a reason is the
   code and message the write answers. Never an optional `selectable` on `DropdownItem`: the plain dropdown stays
   exactly `{value, label}`.
3. **One statement:** the options' batch with each rule's data in it: a column of the projection (the todo's
   `open`), or an `EXISTS` on the module's own table (a loan overlapping the asked dates). The resource in another
   module comes through its facade's batch (`get<Owned><Thing>ViewBatch(parent, query, after, size, scope)`, its
   views carrying the state the owner computes); a rule whose data is the write's module's own table and whose
   candidates are another module's then takes one more statement for the whole batch (ids `IN` the batch), never
   one per option (ARCH-04 forbids the join).
4. **The write still decides:** its own checks, locks and constraint stay as they are (DATA-35's exclusion
   constraint for an overlap), answering the same code.
5. **Tests:** each option exactly `{value, label, selectable}`; an allowed one with no reasons; a refused one with
   every reason, each `errorCode` the write's and `message` equal to the write's Problem Details `detail` (the
   write called first, its `detail` read); the statement count, for one option and a full batch; an out-of-reach
   parent an empty batch.

## Tests (golden: `TodoListIntegrationTest`)
- Default order and shape: fixtures in an order different from the expected one; only the caller's
  items; exactly `value` and `label`. On a **shared root** (no owner: every caller sees every item, other
  tests' rows included) every dropdown test scopes its calls by a `query` token only its own items' labels
  carry, as the list tests do (tests.md *Shared tables*), and "only the caller's items" becomes "another
  tenant's items never appear" (the cross-tenant test).
- `sortDirection=DESC` reverses it.
- `query` matches part of the label ignoring case (searchable), or is 400 (not searchable).
- Batches: walk `size=2` with `after` to the end (`hasMore` false, no `nextCursor`).
- When `displayField` isn't unique (customer names repeat; golden todo titles repeat,
  `TodoDropdownIntegrationTest.equalTitlesWalkInIdOrderInBothDirections`): a group of equal labels split across
  batches, walked in both directions, comes back in id order within the label (the tie-breaker), every item once.
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
- `for`: missing 400 `REQUEST_PARAMETER_MISSING`, unknown 400 `REQUEST_PARAMETER_INVALID` at `for`; a permission
  the caller doesn't hold 403 `ACCESS_DENIED` with no statement run (`IntegrationTest.holding` with every other
  permission); a held permission of a reach the dropdown doesn't list 400 `DROPDOWN_REACH_UNSUPPORTED` at `for`;
  each permission its screens name lists the same options (golden `theDropdownNeedsFor`,
  `aForTheCallerDoesntHoldIs403`, `aForOfAnotherReachIs400`, `everyOwnReachForListsTheSameOptions`).
- One statement per call, `for` included (golden `dropdownRunsOneStatement`); a dependent dropdown also with an
  out-of-reach parent (another owner's, an unknown id): an empty page, one statement, never a 404 (golden
  `TodoDropdownIntegrationTest.anOutOfReachParentIsAnEmptyBatch`); the parent missing is 400 naming it.
- Cacheable: a write evicts it.
