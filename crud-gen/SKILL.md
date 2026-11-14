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
| `<ref>/todo/dto/request/TodoFilter.java`, `TodoListFilter.java` | Cursor filter, offset filter, sort enum, the `ids` filter |
| `<ref>/todo/dto/response/TodoResponse.java`, `TodoSummaryResponse.java` | Detail and list-row records |
| `<ref>/todo/mapper/TodoMapper.java` | Hand-written mapper |
| `<ref>/todo/repository/**` | Repository, custom interface, Criteria **projection** impl with keyset or offset paging |
| `<ref>/todo/service/TodoService.java`, `TodoListService.java` | Transactions, ownership, version checks, parent locking |
| `<ref>/todo/controller/api/*Api.java`, `controller/*Controller.java` | OpenAPI interface, thin package-private controller |
| `~/.claude/skills-reference/src/test/java/com/example/app/todo/*IntegrationTest.java` | The tests every entity ships with |
| `<ref>/todo/domain/Tag.java` + `TagService`, `TagFilter`, `TagColor` | A root with an **active flag** (dropdown `activeField`, list filter `status`), a **pattern field**, an offset list |
| `<ref>/todo/domain/TodoTag.java` + `Todo.replaceTags`, `TodoTagsRequest`, `PUT /todos/{id}/tags`, `TodoService.withTags`, `0006-todo-tags.sql` | **Variant N**: a many-to-many between two aggregates |
| `<ref>/todo/domain/TimeEntry.java` + the `*TimeEntry*` methods of `TodoApi` / `TodoController` / `TodoService` | **Variant M** (a member of the `Todo` aggregate), **range filters** (`@AssertTrue boolean is<X>RangeOrdered()`), a bounded number, a calendar date (`@NotAfterToday`) |

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
   disguise (rule 3). Adding a row to `AggregateArchTest.AGGREGATES` (or a member to a row) is not
   changing a test: that list is data, edited by design (Step 4.8). New behaviour gets a **new test
   class** named for the feature (`TodoTagsIntegrationTest`), reusing the existing class's fixture
   helpers via the test base or a shared support class, not copies.
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
(`@ManyToOne` / `@OneToOne`), the module, the `{{dropdownAnnotation}}` and whether its records carry uploaded files (Step 5).
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
| Id time-ordered per `{{idStrategy}}`, app-assigned, `updatable = false` (from `{{AuditBase}}`) | DATA-09 |
| Extends `{{AuditBase}}` (audit columns + `@Version`) | DATA-06 |
| Lombok `@Getter @SuperBuilder`, protected constructor, `@Setter` only on free fields; state changes are entity methods; relations `LAZY`, collections read-only | JAVA-16, SMELL-16 |
| String columns `text`, plain `@Column`, no length constants | DATA-12 |
| Starting values (a status, a boolean) in the entity with `@Builder.Default`; booleans primitive, `NOT NULL`, database default | JAVA-16 |
| Impossible-state guards throw `IllegalStateException` | JAVA-20 |
| No soft-delete flag; statuses are enums with a check constraint | DATA-07 |
| A changeset with `--rollback`; FKs named `fk_<table>_<column>` | DATA-13 |
| Unique text ignores case and surrounding spaces (one of the two shapes; partial when nullable) | DATA-05 |
| Indexes: every FK, one per list order and per scoping filter, ending with `id`; keyset sort keys non-null (a generated column for a nullable one) | DATA-10 |
| A child of an owned parent: the composite owner FK, `moveTo` or `updatable = false` on the parent column | DATA-33 |
| `Instant` for moments, `LocalDate` for picked dates, no time zones | JAVA-25 |
| The sequence exists for a display number | DATA-14 |

Golden examples: `~/.claude/skills-reference/src/main/resources/db/changelog/changes/000{2,3,4,5}-*.sql`.
Changesets use `--changeset {{changesetAuthor}}:NNNN-…` (the golden files say `dev`: a placeholder).

List **every** failure, of every entity in the run, in **one table** (check, what is wrong, the fix:
the entity change and a **new** changeset; never edit an applied one), then ask **once** with
**AskUserQuestion**: apply all, or pick. When nobody can be asked (a non-interactive run), apply all
and say so in the report. A declined fix: generate anyway and list it as an open item.

**Add every new changeset to `db.changelog-master.yaml` right away**, for all the entities you will
generate, before the first build: Hibernate validates every entity's table at startup, so a missing
child table fails the build of the parent too.

---

## Step 3: Decide the variant and the list kind

- **Variant M: aggregate member** (from Step 1; golden `TimeEntry` and the `*TimeEntry*` methods of
  `TodoApi` / `TodoController` / `TodoService`): no service, controller or API of its own; endpoints
  nested under the root (`/<roots>/{<root>Id}/<members>`) in the root's API, controller and service.
  Ask: may a root with members be deleted (cascade), or is it 409 `<ROOT>_IN_USE`? Follow the golden
  methods for the rest (owner check on the root first, only create locks the root, the root's
  version untouched).
- **Variant N: many-to-many between aggregates** (golden `TodoTag`, `Todo.replaceTags`,
  `TodoTagsRequest`, `PUT /todos/{id}/tags`, `TodoService.withTags`, `0006-todo-tags.sql`). Ask:
  which aggregate **writes** the link (the link becomes a member `<A><B>` of that root, ARCH-15)?
  When B is deleted, do links simply disappear (`ON DELETE CASCADE` on the B side) or is it 409
  `<B>_IN_USE`? Two facts the golden code relies on:
  - A's mapped-by link set carries `@OptimisticLock(excluded = false)`, or A's version never moves
    when the set changes (DATA-30);
  - B's rows are locked through B's service with a native `FOR KEY SHARE … ORDER BY id`, so a rename
    of B isn't blocked and the lock order is fixed (DATA-29).

  Schema (composite owner FKs on both sides, DATA-33), API (`PUT /<as>/{id}/<bs>`, A's own PUT
  refuses `<b>Ids`) and the list (one grouped query for a batch's links) as in the golden files.
- **Variant A: list CRUD** (no FK with `unique = true`). **Variant B: one per parent** (a unique
  FK): a nested singleton resource; GET returns a null object when absent, PUT creates or
  replaces.
- **List kind** (`{{cursorLists}}` / `{{offsetLists}}`, entity names; else ask):
  - **cursor** (the default): user-facing or growing lists. `CursorFilter`, `CursorWrapper`,
    `pageInfo {nextCursor, hasMore}`, no count. Golden: `TodoFilter`, `TodoRepositoryImpl`;
  - **offset**: bounded admin tables that need page numbers. `PageFilter`, `PaginationWrapper`,
    the count only on a full page. Golden: `TodoListFilter`, `TodoListRepositoryImpl.findByFilter`.

- **A feature that needs configuration, not an entity** (rules an admin chooses per tenant or per
  context, read often, changed rarely): with the `metadata` piece, generate no entity. Rules:
  house-review `metadata.md` (META-01…09). Golden: `metadata/` (the fixed key
  `TODO_LIST_DEFAULTS`; the per-context key `MetadataKeys.todoListSettings(listId)`; the record
  `TodoListSettings`) and `MetadataIntegrationTest`, `CounterIntegrationTest`. A per-context value
  is an endpoint of the module that owns the context (`GET/PUT /todo-lists/{id}/settings`, with
  `version`), generated here with its tests (tests.md).

**Shapes without golden code yet** (money, exactly one current, no overlapping ranges, month
values, a parent in another module): follow the house-review rules and ask; don't invent a shape.

---

## Step 4: Generate

### Conventions (all generated code)
House-review's rules, as the golden files apply them: KISS and SRP (SMELL-29: queries written top to
bottom, plain helpers, no lambda-taking helpers), no tunables in code (SMELL-17), why-only comments
(SMELL-19), `Objects.isNull`, `Optional` only as a return type (JAVA-08), no `var` and text blocks
(JAVA-13), streams without side effects (JAVA-14), enums with `==` (JAVA-18), minimal visibility
(JAVA-03). **Naming** (SMELL-20): bodies `<Entity><Action>{{requestSuffix}}`, GET parameters
`<Entity>Filter`, responses `<Entity>Response`; list rows `<Entity>SummaryResponse` only when they
leave out heavy fields (a `text` column with a limit over 1,000 characters, or a collection).

### 4.1 Filter
Cursor: `extends {{CursorFilter}}` (golden `TodoFilter`). Offset: `extends {{PageFilter}}` (golden
`TodoListFilter`). One field per filterable attribute: FK ids (named like the request field,
`todoListId`), enums typed as enums, a `query` with `@MaxLength("search-query")`, a `sort` enum with
a default; `requireXPredicate()` per optional field (the repository builds predicates only from
these). A filter with `query` implements `QueryFilter`, so the shared text-search helper takes the
filter.

**Every list gets `ids`** (REST-23; golden `TodoFilter.ids`, `TodoListFilter.ids`):
`List<@NotNull ID> ids` with `@ConfiguredMaxSize("cursor-page.max-size")` (offset:
`"offset-page.max-size"`), `requireIdsPredicate()` (non-null and non-empty), an `in` predicate in the
list query (offset: in the page **and** the count query; not in the dropdown), and
`@Schema(description = "Find by ids …: unknown or another user's ids are simply absent")`. The list's
description and its 400 response name it.

**Shapes** (golden `Tag` / `TimeEntry`):
- **Range filters:** `<x>From` / `<x>To` for dates, `min<X>` / `max<X>` for numbers, both inclusive;
  each bound carries the field's own constraints (`@Min`, `@ConfiguredMax`); the filter has one
  `@AssertTrue boolean is<X>RangeOrdered()` per range (true when either bound is absent), so an
  inverted range is a 400 naming `<x>RangeOrdered` (golden `TimeEntryFilter`).
- **Flags:** the list filters a flag as `status` (`DropdownStatus`, default `ALL`), the dropdown
  defaults to `ACTIVE`; a flag without rules is set by the update (`@Setter`).
- **Pattern fields:** `@Pattern` with a readable message instead of `@MaxLength` (the pattern bounds
  the length), normalised in the mapper (case), backed by a database `CHECK`.
- **"None" is null:** optional text and pattern fields store blank as `null`; the `@Schema` says
  "null, absent or blank for none (returned as null)".
- **Calendar dates:** `@NotAfterToday`, never `@PastOrPresent` (JAVA-25).

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
`*_IN_USE`). Never check-then-insert (DATA-05); every constraint kind maps (ERR-02). A state
conflict is 409, a wrong value 400 (ERR-05).
**Bootstrap brings no feature codes**: every code, mapping, message, constraint entry and limit key
is added here. Constraint mappings go in the module's own `ConstraintErrorCodes` bean (the handler
aggregates them). A child's composite owner FK `(parent_id, owner_id)` maps to the parent's
`*_IN_USE` (DATA-33).

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
- **Each method writes its query top to bottom** (SMELL-29): `builder`, `query`, `root`,
  `query.select(builder.construct(...))`, a `List<Predicate>`, `where`, `orderBy`, then the plain
  helpers. No helper that takes lambdas.
- Cursor: `Cursor cursor = Cursor.read(filter.getAfter(), sort)`; `if (cursor.isPresent())` add
  `Seek.after(builder, descending, key1, cursor.text(0), …, id, cursor.uuid(n))`;
  `query.orderBy(Seek.order(builder, descending, key1, …, id))` on **the same columns, id last**;
  `setMaxResults(size + 1)`; `CursorBatch.of(rows, size, row -> Cursor.write(sort, row.key1(), …,
  row.id()))`. Keys non-null (a generated sort column for a nullable one). **The service** turns the
  `CursorBatch` into a `CursorWrapper`.
- Offset: the select query with `QueryUtils.toOrders(pageable.getSort(), root, builder)` and its own
  count query, then `OffsetPages.page(rows, count, pageable)` (skips the count on a partial page);
  the service wraps the page in `PaginationWrapper.fromPage`.
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
transaction (DATA-01). Never lock "the current X" by a mutable column (DATA-32).

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
Role checks are meta-annotations such as `@AdminOnly` (REST-25, golden `MetadataController`).

### 4.11 Dropdown (when the entity carries `{{dropdownAnnotation}}`)
Read `references/dropdown.md`. Golden: `TodoListRepositoryImpl.findDropdownItems`,
`TodoListService.dropdown`, `TodoListController.dropdown`.

---

## Step 5: Media (only when the records carry files and the project has the `media` piece)
Read `references/media.md`: a new `UploadPurpose` with its policy seed row, the link entity and
its changeset, the `<x>Keys` / `add<X>Keys` / `remove<X>Ids` fields, the claim and release in the
owner's own write, and the files in the response. Rules: house-review `media.md` (MEDIA-xx).
Golden: the todo attachments (`TodoAttachment`, `TodoService`). Without the piece, offer
`/crud-gen bootstrap` (never hand-write an upload flow).

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
- [ ] No `{{…}}` left in the generated files.
- [ ] Build green, and one guard proven to fail (Step 7).
- [ ] house-review clean (Step 8).
