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
- a path to an entity: generate its CRUD (Steps 0–8 below). When the entity **already has generated
  code** (its service or repository exists), this is **update mode**: read *Updating an entity* first.

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
| `<ref>/todo/domain/Tag.java` + `TagService`, `TagFilter`, `TagColor` | A root with an **active flag** (dropdown `activeField`, list filter `status`), a **pattern field**, an offset list |
| `<ref>/todo/domain/TodoTag.java` + `Todo.replaceTags`, `TodoTagsRequest`, `PUT /todos/{id}/tags`, `TodoService.withTags`, `0006-todo-tags.sql` | **Variant N**: a many-to-many between two aggregates |
| `<ref>/todo/domain/TimeEntry.java` + the `*TimeEntry*` methods of `TodoApi` / `TodoController` / `TodoService` | **Variant M** (a member of the `Todo` aggregate), **range filters** (`@OrderedRange`), a bounded number, a calendar date (`@NotAfterToday`) |

The rules behind the shape are the house-review references, which are the source of truth:
`rest-api.md`, `error-contract.md`, `persistence.md`, `java.md`, `code-smells.md`, `lenses.md`
(TEST-xx), `architecture.md`, `config-baseline.md`. When the golden code and a reference disagree,
**report it** (one of them is wrong and must be fixed in the skills repo). Step 8 runs house-review
on the generated files, and it must come back clean.

Everything that differs between projects is a **profile key**, written `{{key}}`.

---

## Updating an entity (update mode)

The developer changed an entity that already has generated code (a field, a relation, a rule). The
steps are the same; these rules come first:
1. **Diff first.** `git diff` on the entity tells what changed; generate only what the change
   touches (requests, response, mapper, repository predicates, service, API docs, changeset,
   tests). Leave everything else byte-for-byte as it is, even if you would write it differently today
   (offer that as a separate refactoring).
2. **A new changeset, never an edit** of an applied one; existing rows need a default or a backfill
   for a new `NOT NULL` column (ask what the default is).
3. **No breaking change** (REST-20): a new response field and an optional request field are fine;
   a removed or renamed field, a new required request field, a new refusal on an existing call or
   a changed meaning are breaking. A PUT stays a full replace of what it covered before: a new
   relation that clients set gets **its own sub-resource** (`PUT /<roots>/{id}/<relation>`) rather
   than a field that old clients would send absent. When a breaking change is unavoidable, stop
   and ask; record it where the project lists breaking changes.
4. **Every existing test must pass unchanged.** A test that has to change is a breaking change in
   disguise (rule 3). New behaviour gets a **new test class** named for the feature
   (`TodoTagsIntegrationTest`), reusing the existing class's fixture helpers via the test base or a
   shared support class, not copies.
5. **Guard proof** (Step 7): prove a guard of the *new* behaviour fails without its fix.

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
| A child owned through its parent: the child's owner and the parent's owner are tied by a **composite FK** `(parent_id, owner_id) → parent(id, owner_id)`, which needs `UNIQUE (id, owner_id)` on the parent (its own changeset if the parent's is applied). The plain `owner_id` FK still gets its own index (the composite one doesn't lead with it). A child that can change parent has a `moveTo` method that checks the owner; one that can't has `updatable = false` on its parent column | DATA-05, DATA-10, SMELL-16 |
| Booleans: a primitive `boolean` column `NOT NULL` with a database default; its starting value in the entity (`@Builder.Default`, the builder ignores field initialisers). Editable with the rest of the record → `@Setter`; a transition with rules or side effects (`deactivate()` that also does X) → an entity method. In requests a boolean is a `Boolean` with `@NotNull` (no silent `false`) | JAVA-16, REST-09 |
| Time: `Instant` for moments (UTC; the client localises), `LocalDate` for calendar dates the user picks (stored and returned as sent, never converted). The backend handles no time zone: a date compared with "today" allows UTC today plus a configured tolerance (1 day) | owner's rule 2026-11-13 |
| The sequence exists for a display number | DATA-14 |

Golden examples: `~/.claude/skills-reference/src/main/resources/db/changelog/changes/000{2,3,4,5}-*.sql`.
Changesets use `--changeset {{changesetAuthor}}:NNNN-…` (the golden files say `dev`: a placeholder).

For each failure: report it, offer the fix with **AskUserQuestion** (the entity change and a **new**
changeset; never edit an applied one), apply it on approval. If declined, generate anyway and list
it as an open item.

**Add every new changeset to `db.changelog-master.yaml` right away**, for all the entities you will
generate, before the first build: Hibernate validates every entity's table at startup, so a missing
child table fails the build of the parent too.

---

## Step 3: Decide the variant and the list kind

- **Variant M: aggregate member** (from Step 1; golden `TimeEntry`): no service, controller or API
  interface of its own. Endpoints nested under the root, `/<roots>/{<root>Id}/<members>`, in the
  **root's** API and controller; methods named `<members>`, `get<Member>`, `create<Member>`,
  `update<Member>`, `delete<Member>` in the root's service. Every operation loads the root first
  (owner check: `<ROOT>_NOT_FOUND`), then the member by `findByIdAnd<Root>IdAndOwnerId`
  (`<MEMBER>_NOT_FOUND`); only create locks the root (`findForShareByIdAndOwnerId`) and the root's
  version is untouched. The member's FK column is `updatable = false`; the root needs
  `UNIQUE (id, owner_id)` for the composite FK. Deleting a root that still has members is 409
  `<ROOT>_IN_USE` unless the owner chooses a cascade (ask). The nested list costs 2 statements
  (the ownership check, then the batch). Everything else below applies.
- **Variant N: many-to-many between aggregates** (a `@ManyToMany`, or two roots linked; golden
  `TodoTag`). Ask which aggregate **writes** the link: the link becomes a member entity of that root
  (`<A><B>`, extends `{{AuditBase}}`), used only by A's service (ARCH-15).
  - **Schema** (pre-flight, new changesets): the other side gets `UNIQUE (id, owner_id)`; the link
    table has `id`, `a_id`, `b_id`, `owner_id`, the audit columns and `version`; `UNIQUE (a_id, b_id)`
    (leading with `a_id`, it serves A's FK and the links of a batch); composite FKs
    `(a_id, owner_id) → a(id, owner_id)` and `(b_id, owner_id) → b(id, owner_id)` (the database
    refuses a link across owners); no FK on `owner_id` alone; index `(b_id, owner_id, a_id)`. Deleting
    B: `ON DELETE CASCADE` on the B side only when the owner says links simply disappear (a label);
    otherwise 409 `<B>_IN_USE`. Ask.
  - **Entity:** A holds a mapped-by `Set<<A><B>>` with `@OptimisticLock(excluded = false)` (Hibernate
    7.4 excludes mapped-by collections from the version by default), cascade PERSIST/REMOVE,
    orphan removal, no getter; `replace<Bs>(List<B>)` with the owner guard is the only writer.
  - **API:** `PUT /<as>/{id}/<bs> {<b>Ids, version}` replaces the set (A's own PUT stays a full
    replace and refuses `<b>Ids`); optional `<b>Ids` on create; `Set<@NotNull ID>` with
    `@ConfiguredMaxSize("<a>.max-<bs>")`, no `@NotEmpty`, a repeated id counts once (documented);
    responses carry `<bs>: [{id, <displayField>}]` in id order. The version changes only when the
    set does.
  - **Service:** B through B's service: `getOwned<Bs>ForWrite(ids, ownerId)` (`MANDATORY`; a native
    `FOR KEY SHARE … ORDER BY id`, so a rename of B isn't blocked and lock order is fixed; 404
    `<B>_NOT_FOUND` naming the lowest missing id); A locked for update before its link rows; A's
    delete removes its links through the aggregate.
  - **List:** the row projection without links, then **one grouped query** for the batch's links
    (`with<Bs>`): 2 statements per batch, 1 when empty; the `<b>Id` filter is an `exists` on the
    link table (no join to B).
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
- **KISS and SRP first** (SMELL-29): code a Java engineer with 2–3 years of experience reads without
  deep generics or reflection. Each repository method writes its query top to bottom and calls the
  plain helpers (`Cursor`, `Seek`, `CursorBatch.of`); never invent a helper that takes lambdas.
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
  `<Entity>SummaryResponse` when they leave out heavy fields (a `text` column with a limit over
  1,000 characters, or a collection); otherwise the list row is the `<Entity>Response`.
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

**Shapes** (golden `Tag` / `TimeEntry`):
- **Range filters:** `<x>From` / `<x>To` for dates, `min<X>` / `max<X>` for numbers, both inclusive;
  each bound carries the field's own constraints (`@Min`, `@ConfiguredMax`); the filter class
  carries `@OrderedRange(from = …, to = …)` (an inverted range is 400 naming the lower bound).
- **Flags:** the list filters a flag as `status` (`DropdownStatus`, default `ALL`), the dropdown
  defaults to `ACTIVE`; a flag without rules is set by the update (`@Setter`).
- **Pattern fields:** `@Pattern` with a readable message instead of `@MaxLength` (the pattern bounds
  the length), normalised in the mapper (case), backed by a database `CHECK`.
- **"None" is null:** optional text and pattern fields store blank as `null`; the `@Schema` says
  "null, absent or blank for none (returned as null)".
- **Calendar dates:** `@NotAfterToday` (UTC today + `app.limits.not-after-today.tolerance-days`),
  never `@PastOrPresent` (JAVA-25).

### 4.2 Requests (records)
`<Entity>Create{{requestSuffix}}`, `<Entity>Update{{requestSuffix}}` (with `@NotNull Long version`),
one per state action (`<Entity>Status{{requestSuffix}}`). **Every String carries `@MaxLength("<entity>.<field>")`** and its
number is added to `app.limits.text` in `config/limits.yml`; collections carry a configured size
limit (`@ConfiguredMaxSize("<entity>.<field>")`) and `@NotNull` elements, plus `@NotEmpty` only when
an empty collection is meaningless (an optional set of tags may be empty); a date with a meaningful upper bound carries `@LatestDate("<entity>.<field>")`
(`app.limits.latest-date`), e.g. below a sort sentinel. `@Schema` on every component. Golden:
`TodoCreateRequest`.

### 4.3 Responses (records)
`<Entity>Response` (every field, FKs as ids, ending with `{{auditFields}}`; a reference to another
aggregate that clients display is `{id, name}`, a list of them for a many-to-many) for the detail; for list
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
status and errorCode the endpoint can return (except `API_VERSION_UNSUPPORTED`: a customizer adds it
to every operation; error `@Content` needs no `mediaType`, a customizer publishes it as
`application/problem+json`): 200/201/204, 400 (`VALIDATION_FAILED`,
`REQUEST_UNREADABLE`, `REQUEST_PARAMETER_INVALID` for a bad id, enum, date or cursor), 401, 403
(roles), 404, 409 (`*_TAKEN`, `OPTIMISTIC_LOCK_CONFLICT`, also on delete, `*_IN_USE`), 503.
List descriptions explain the paging (cursor: `after` / `pageInfo.nextCursor`; offset: `page` /
`size`, limits from configuration). Filters are `@ParameterObject`.

### 4.10 Controller
Golden `TodoController`: package-private, `implements <Entity>Api`, class-level
`@RequestMapping("{{urlPrefix}}/<resource-plural>")`, methods with relative paths and
`version = "v1+"` (the current baseline; REST-07), 201/204 with `@ResponseStatus`, `{{controllerSecurity}}`, nothing but the service call. A user-level resource needs no
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
