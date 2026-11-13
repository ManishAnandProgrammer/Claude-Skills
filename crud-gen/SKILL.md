---
name: crud-gen
description: Generate full layered CRUD for a JPA entity in any Spring Boot project, following the house pattern so the result passes house-review with no findings. Generates like the golden module of the house reference application (~/.claude/skills-reference) - API interface with OpenAPI docs, controller, service, mapper, repository with a Criteria projection impl (offset or cursor paging), request/response records, filter, ErrorCodes and messages, configurable limits, and the integration test class, with optional dropdown and media endpoints. Pre-flights the entity and its migration. `/crud-gen bootstrap` copies the shared house infrastructure into a project from the reference application (error contract, wrappers, paging, limits, logging, config, security, test base, ArchUnit). Project specifics come from .claude/crud-profile.md, discovered from the codebase on first use.
argument-hint: "<path-to-Entity.java> | bootstrap"
allowed-tools: [Read, Write, Edit, Bash, Glob, Grep, AskUserQuestion]
---

# CRUD Generator

Target: **$ARGUMENTS**

- `bootstrap`: set up the shared house infrastructure once. Read
  `~/.claude/skills/crud-gen/references/bootstrap.md` and follow it; skip the rest of this file.
- a path to an entity: generate its CRUD (Steps 0–8 below).

## The goal: generated code passes house-review

**Generate like the reference, don't invent.** `~/.claude/skills-reference` (the skills repo's
`reference-app/`, linked by the installers) is a real application that builds and passes its tests.
Its `todo` module is the **golden output** of this skill. Every step below names the golden file to
follow; read it and produce the same shape for the target entity. If the link is missing, stop and
tell the user to run the skills installer.

| Golden file (`<ref>` = `~/.claude/skills-reference/src/main/java/com/example/app`) | Shows |
|---|---|
| `<ref>/todo/domain/Todo.java`, `TodoList.java` | Entity: `@Getter @SuperBuilder`, protected constructor, `@Setter` only on free fields, state methods, a generated sort column |
| `<ref>/todo/dto/request/TodoCreateRequest.java`, `TodoUpdateRequest.java`, `TodoStatusRequest.java` | Request records with `@MaxLength("<key>")`, `version` on updates |
| `<ref>/todo/dto/request/TodoFilter.java`, `TodoListFilter.java` | Cursor filter, offset filter, sort enum |
| `<ref>/todo/dto/response/TodoResponse.java`, `TodoSummaryResponse.java` | Detail and list-row records |
| `<ref>/todo/mapper/TodoMapper.java` | Hand-written mapper |
| `<ref>/todo/repository/**` | Repository, custom interface, Criteria **projection** impl with keyset or offset paging |
| `<ref>/todo/service/TodoService.java`, `TodoListService.java` | Transactions, ownership, version checks, parent locking |
| `<ref>/todo/controller/api/*Api.java`, `controller/*Controller.java` | OpenAPI interface, thin package-private controller |
| `~/.claude/skills-reference/src/test/java/com/example/app/todo/*IntegrationTest.java` | The tests every entity ships with |

The rules behind the shape are the house-review references, which are the source of truth:
`rest-api.md`, `error-contract.md`, `persistence.md`, `java.md`, `code-smells.md`, `lenses.md`
(TEST-xx), `architecture.md`, `config-baseline.md`. When the golden code and a reference disagree,
**report it** (one of them is wrong and must be fixed in the skills repo). Step 8 runs house-review
on the generated files, and it must come back clean.

Everything that differs between projects is a **profile key**, written `{{key}}`.

---

## Step 0: Load the project profile

Look for `.claude/crud-profile.md` at the project root. If it exists, read it: its values and
*Deviations* take precedence. Also read `.claude/review-profile.md` if it exists; its conventions
apply to generated code too.

If it doesn't exist, build it before generating anything:
1. Copy `~/.claude/skills/crud-gen/profile-template.md`.
2. Fill each key from the codebase; don't guess.
3. **If the house infrastructure is missing** (no ErrorCode/AppException contract, no
   `PaginationWrapper`/`CursorWrapper`, no `Limits`/`@MaxLength`, no test base), stop and offer
   `/crud-gen bootstrap`. Generated code depends on it.
4. Keys with no equivalent: `none` (see *Fallbacks* in the template).
5. Write the profile, show a short summary, and get confirmation **once**.

---

## Step 1: Read and analyse the entity

Extract the class name, every field with its JPA annotations, the FK relations
(`@ManyToOne` / `@OneToOne`), the module, the `{{dropdownAnnotation}}` and the media collection.
Derive `EntityName` / `entityName`, the URL path (kebab-case plural), and `ERROR_PREFIX`
(UPPER_SNAKE, e.g. `TODO_LIST`). `ID` means `{{idType}}`.

**Aggregate** (ARCH-15, `{{aggregates}}`): is the entity an aggregate **root** (its own service,
controller and row in `AggregateArchTest.AGGREGATES`), or a **member** of another root's aggregate
(no service of its own: the root's service owns its repository and rules)? Use the profile; when
the entity isn't listed, ask: "is `<Entity>` changed only together with `<Parent>` and meaningless
without it (member), or does it have its own lifecycle (root)?". Record the answer in both profiles.

**Module** (if `{{modules}}` is set): every generated class goes under `{{appPackage}}` with
`{module}` substituted; cross-module access follows `{{moduleContracts}}` (architecture.md
ARCH-04/05/06): a parent in another module is referenced by id (a UUID column with a database
foreign key), never by an entity relation, in `api-facades` mode.

---

## Step 2: Pre-flight the entity and its migration

Check before generating:

| Check | Rule |
|---|---|
| Id time-ordered per `{{idStrategy}}`, assigned in the application, `updatable = false` (from `{{AuditBase}}`) | DATA-09 |
| Extends `{{AuditBase}}` (audit columns + `@Version`) | DATA-06 |
| Lombok: `@Getter @SuperBuilder`, protected no-args constructor, `@Setter` only on freely editable fields; state changes are entity methods | JAVA-16, SMELL-16 |
| Relations `@ManyToOne(fetch = LAZY)`; collections read-only with `add…` / `remove…` | JAVA-16 |
| **String columns are `text` in the changeset**; the entity has a plain `@Column` (no `length`, no `columnDefinition`: `validate` accepts `text` for a `String`) and no length constants; the length contract is `@MaxLength` on the request with its number in `app.limits.text` | DATA-12 |
| A state field (status) has its **starting value in the entity** (`@Builder.Default`), never in the mapper; impossible-state guards in entity methods throw `IllegalStateException` (500, logged), never `IllegalArgumentException` (400) | JAVA-16, ERR-04 |
| No soft-delete flag; statuses are enums with a check constraint | DATA-07 |
| A changeset exists, with `--rollback`; FKs named `fk_<table>_<column>` | DATA-13 |
| A unique text column ignores case and surrounding spaces: **either** the code stores the normalised form (a check constraint `col = lower(trim(col))`) and the column gets a plain unique constraint, **or** the stored text keeps its case and gets a unique index on `lower(trim(col))`, which **only a query on that same expression can use** (a lookup `col = ?` scans the table). Partial (`WHERE col IS NOT NULL`) when nullable | DATA-05 |
| An index on every FK column, and **one index per offered list order and per filter that scopes the list** (`todoListId`: the list's own screen), leading with the scope columns and **ending with `id`** | DATA-10 |
| **A keyset sort key is non-null.** A nullable one (a due date) gets a stored generated column, e.g. `due_sort date GENERATED ALWAYS AS (coalesce(due_date, date '9999-12-31')) STORED`, mapped read-only | Session 2; DATA-10 |
| A child owned through its parent: the child's owner and the parent's owner are tied by a **composite FK** `(parent_id, owner_id) → parent(id, owner_id)` and a `moveTo` method that checks the owner | DATA-05, SMELL-16 |
| The sequence exists for a display number | DATA-14 |

Golden examples: `~/.claude/skills-reference/src/main/resources/db/changelog/changes/000{2,3}-*.sql`.

For each failure: report it, offer the fix with **AskUserQuestion** (the entity change and a **new**
changeset; never edit an applied one), apply it on approval. If declined, generate anyway and list
it as an open item.

**Add every new changeset to `db.changelog-master.yaml` right away**, for all the entities you will
generate, before the first build: Hibernate validates every entity's table at startup, so a missing
child table fails the build of the parent too.

---

## Step 3: Decide the variant and the list kind

- **Variant M: aggregate member** (from Step 1): no service, controller or API interface of its
  own. Its endpoints are nested under the root (`/{roots}/{rootId}/{members}`) in the **root's**
  controller and API interface; its operations are methods of the **root's** service, which loads
  the root (ownership, version, lock) before touching the member; its repository is used only
  there. Everything else below (requests, responses, mapper, repository, tests) applies.
- **Variant A: list CRUD** (no FK with `unique = true`). **Variant B: one per parent** (a unique
  FK): a nested singleton resource; GET returns a null object when absent, PUT creates or
  replaces.
- **List kind** (`{{cursorLists}}` / `{{offsetLists}}`, entity names; else ask):
  - **cursor** (the default): user-facing or growing lists. `CursorFilter`, `CursorWrapper`,
    `pageInfo {nextCursor, hasMore}`, no count. Golden: `TodoFilter`, `TodoRepositoryImpl`;
  - **offset**: bounded admin tables that need page numbers. `PageFilter`, `PaginationWrapper`,
    the count only on a full page. Golden: `TodoListFilter`, `TodoListRepositoryImpl.findByFilter`.

---

## Step 4: Generate

### Conventions (all generated code)
- `Objects.isNull` / `Objects.nonNull`; enums compared with `==`; no `var`; text blocks for every
  multi-line string.
- Streams for pure pipelines with method references; a `for` loop when the body has side effects.
- `Optional` as a return type only.
- Lombok only where used.
- Visibility: controllers and `*RepositoryImpl` package-private; fields `private final`.
- **Comments only for *why***, on logic the code can't explain (a lock, a security choice, a database
  behaviour). No class or method Javadoc that restates the name. OpenAPI annotations are not
  comments and are always written.
- **Naming:** HTTP bodies are `<Entity><Action>{{requestSuffix}}` (default `Request`:
  `TodoCreateRequest`, `TodoUpdateRequest`, `TodoStatusRequest`; read the golden `*Request` names
  with the project's suffix); GET parameters are `<Entity>Filter`; responses `<Entity>Response`, list rows
  `<Entity>SummaryResponse` when they leave out heavy fields.
- **No tunables in code.** Every limit, size, timeout or window is a configuration key
  (`app.limits.*`, `app.jobs.*`, ...); code names the key. Invariants of an algorithm or a format
  stay in code with a why-comment.

### 4.1 Filter
Cursor: `extends {{CursorFilter}}` (golden `TodoFilter`). Offset: `extends {{PageFilter}}` (golden
`TodoListFilter`). One field per filterable attribute: FK ids (named like the request field,
`todoListId`), enums typed as enums, a `query` with `@MaxLength("search-query")`, a `sort` enum with
a default; `requireXPredicate()` per optional field (the repository builds predicates only from
these). A filter with `query` implements `QueryFilter`, so the shared text-search helper takes the
filter.

### 4.2 Requests (records)
`<Entity>Create{{requestSuffix}}`, `<Entity>Update{{requestSuffix}}` (with `@NotNull Long version`),
one per state action (`<Entity>Status{{requestSuffix}}`). **Every String carries `@MaxLength("<entity>.<field>")`** and its
number is added to `app.limits.text` in `config/limits.yml`; collections carry `@NotEmpty` and a
`@ConfiguredMax` size; a date with a meaningful upper bound carries `@LatestDate("<entity>.<field>")`
(`app.limits.latest-date`), e.g. below a sort sentinel. `@Schema` on every component. Golden:
`TodoCreateRequest`.

### 4.3 Responses (records)
`<Entity>Response` (every field, FKs as ids, ending with `{{auditFields}}`) for the detail; for list
rows a `<Entity>SummaryResponse` without heavy columns (long text), still ending with the audit
fields. Golden: `TodoResponse`, `TodoSummaryResponse`.

### 4.4 ErrorCodes and messages (error-contract.md)
`ERROR_PREFIX_NOT_FOUND`, `ERROR_PREFIX_<FIELD>_TAKEN` per unique field, `ERROR_PREFIX_IN_USE` when
referenced; the mapper entries; a message in every `{{messagesFile}}`; the handler's
`{{constraintCodeMap}}` entries (unique index names → `*_TAKEN`; FKs pointing **to** this table →
`*_IN_USE`). Never check-then-insert (DATA-05).
**Bootstrap brings no feature codes**: every code, mapping, message, constraint entry and limit key
is added here. Constraint mappings go in the module's own `ConstraintErrorCodes` bean (the handler
aggregates them). A child's composite owner FK `(parent_id, owner_id)` maps to the parent's
`*_IN_USE` (it fires on the parent's delete); it can mean "in use" only because the service loads
the parent by id **and owner** before writing a child, so a foreign parent is a 404 first.

### 4.5 Mapper
Golden `TodoMapper`: `toEntity(request, resolvedParents…, ownerId)`, `update(entity, request,
resolvedParents…)` (only mutable fields; moves through the entity method), `toResponse(entity)`. No
`toResponses`: list pages are projections (4.7).

### 4.6 Repository
`<Entity>Repository extends {{BaseRepository}}<Entity, ID>, Custom<Entity>Repository`, plus
`findByIdAndOwnerId` when owned, and a `@Lock(PESSIMISTIC_READ) findForShareBy…` when children are
written into it (golden `TodoListRepository`).

### 4.7 Custom repository and Criteria projection impl
Golden: `TodoRepositoryImpl` (cursor), `TodoListRepositoryImpl` (offset + dropdown).
- **Projections only**: `builder.construct(<Row>.class, …)`; never entities on a list page.
- The projection is a `ProjectionQuery` (entity, row class, selections, predicates), passed to the
  injected `keysetPages` / `offsetPages` beans.
- Cursor: a `Keyset` per sort (keys non-null, last key the id): `keysetPages.fetch(query, keyset,
  after, size)` returns a `CursorBatch`; **the service** turns it into a `CursorWrapper`.
- Offset: `offsetPages.fetch(query, pageable)` with the sort from the filter's sort enum; the service
  wraps the page in `PaginationWrapper.fromPage`.
- Predicates from the `requireXPredicate()` methods; text search with `{{TextSearch}}.contains`.
- Child data on a list page: an aggregate subquery in the projection, or two steps (a page of ids,
  then one grouped query). Never a collection fetch (DATA-26).

### 4.8 Service
Golden `TodoService` / `TodoListService`. **Add the aggregate's row to the project's
`AggregateArchTest.AGGREGATES`** (a member: add it to its root's members). Another aggregate is
reached only through its service's entity methods, never its repository (ARCH-15); a use case that
writes several aggregates goes in a `<UseCase>Manager` that owns the transaction. Class
`@Transactional(readOnly = true)`, `@Transactional`
writes; create `saveAndFlush`; update/actions change the managed entity after
`entity.checkVersion(request.version())` (409 `OPTIMISTIC_LOCK_CONFLICT`), then `flush()`; delete loads (404) then flushes; ownership through
`findByIdAndOwnerId` (another user's record is 404); parents written into are locked with
`getOwned<Parent>ForWrite` (`MANDATORY`); list sizes from `{{Paging}}`. No remote call inside a
transaction (DATA-01).

### 4.9 API interface (OpenAPI)
Golden `TodoApi`: `@Tag`; `@Operation` with text-block descriptions; an `@ApiResponse` for every
status and errorCode the endpoint can return: 200/201/204, 400 (`VALIDATION_FAILED`,
`REQUEST_UNREADABLE`, `REQUEST_PARAMETER_INVALID` for a bad id, enum, date or cursor), 401, 403
(roles), 404, 409 (`*_TAKEN`, `OPTIMISTIC_LOCK_CONFLICT`, also on delete, `*_IN_USE`), 503.
List descriptions explain the paging (cursor: `after` / `pageInfo.nextCursor`; offset: `page` /
`size`, limits from configuration). Filters are `@ParameterObject`.

### 4.10 Controller
Golden `TodoController`: package-private, `implements <Entity>Api`, full paths, 201/204 with
`@ResponseStatus`, `{{controllerSecurity}}`, nothing but the service call. A user-level resource needs no
`@PreAuthorize` (every authenticated principal is a user); the dropdown carries
`{{dropdownSecurity}}` explicitly because it is often shared by screens of different roles.

### 4.11 Dropdown (when the entity carries `{{dropdownAnnotation}}`)
Read `references/dropdown.md`. Golden: `TodoListRepositoryImpl.findDropdownItems`,
`TodoListService.dropdown`, `TodoListController.dropdown`.

---

## Step 5: Media (only with a media collection and a media profile)
Read `references/media.md`.

## Step 6: Integration tests (always)
Read `references/tests.md` and follow the golden test classes.

## Step 7: Build and test
Run `{{buildCommand}}`. Fix every compile error or test failure in the generated code; never report
done on a red build. Then prove one guard can fail (tests.md).

## Step 8: Self-review (house-review)
Run house-review on the generated and changed files. **The goal is zero findings.** A finding in
code that follows the golden files means the reference or this skill is wrong: fix the generated
file, then offer to fix `~/Documents/claude-skills` (reference-app and skill text together, verified
with `scripts/verify.sh`).

### Final checklist
- [ ] Profile loaded; no `{{…}}` left; the golden files read.
- [ ] Pre-flight fixed or listed: `text` columns, indexes per order ending in `id`, non-null keyset
      keys, `lower(trim())` unique indexes, composite owner FK.
- [ ] `*Request` records with `@MaxLength` keys added to `config/limits.yml`; `version` on updates.
- [ ] List as a projection; cursor or offset per the profile; no counts on cursor lists.
- [ ] ErrorCodes, mapper entries, messages, constraint mappings.
- [ ] Service: transactions, version checks, ownership 404, parent locking, no remote call in a
      transaction.
- [ ] API interface documents every status and errorCode; thin package-private controller.
- [ ] Why-comments only; no tunables in code; no `var`.
- [ ] Changesets in the master changelog before the first build.
- [ ] Aggregate decided (root or member); `AggregateArchTest.AGGREGATES` and both profiles updated.
- [ ] Tests per tests.md; build green; one guard proven to fail; house-review clean.
