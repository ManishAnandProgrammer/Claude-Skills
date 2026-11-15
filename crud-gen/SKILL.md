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
| `<ref>/planning/**` (`Plan`, `PlanCreateRequest`, `PlanFilter`, `PlanRepositoryImpl`, `PlanService`, `PlanTodoResponse`, `PlanningConstraintErrorCodes`, `0011-plans.sql`) + `<ref>/todo/api/Todos.java`, `TodoView.java` | **A parent in another module** (an id with a database foreign key, reached through the owner's facade; its view mapped into a response record of the plan's own), whose **state the create checks under its lock** (a plan only for an open todo: `Todos.getOwnedTodoViewForUpdate`, the view's `open` boolean, 409 `PLAN_TODO_DONE`; the todo's completion and delete wait for the create), reads that **survive a parent vanished between their two statements** (`findOwnedTodoView`, `withTodos`), a **cursor list filtered by another module's id** (`todoId`) whose rows get the parent's title in one facade lookup per batch, **no overlapping ranges** (an exclusion constraint mapped to 409; overlapping creates for two of the owner's todos may answer 503: the lock queues one todo's writes, not the owner's), a **range in a body** (`isDateRangeOrdered`) of a **configured maximum length** (`getLengthInDays`, `plan.max-days`), an **overlap filter with a lower index bound** (`plannedFrom`/`plannedTo`: `ends_on >= from`, `starts_on <= to` and the derived `starts_on >= from - (max - 1)`), planned dates (`@EarliestDate` and `@LatestDate` only), an immutable resource (no PUT or PATCH: 405) |

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
apply to generated code too. When this skill says "record it in the profiles", a review-profile
**Conventions** row is only for a departure from a house rule, with the owner's reason (it reads as an
override); a choice no rule forbids is recorded in the javadoc or the changeset comment instead.

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

**Owned or shared** (SEC-01): does each row belong to one user (an owner column: golden `Todo`), or
is the table **shared** (a catalogue, reference data, staff records: nobody owns a row)? For a shared
root, ask **who may write** (create, update, actions, delete, its upload purposes) and **who may
read**, and record both in the profiles' conventions. **Without tenancy**, while signup is public
(the `sign-in` piece), every internet account is a USER: refuse `controllerSecurity: none` for a shared
root's writes; they carry a role (`@AdminOnly`, or the project's own role annotation, REST-25). **With
tenancy** the public signup reaches no tenant data: a tenant token needs a membership that an ADMIN of
that tenant granted (an account without one can't sign in, `TenantPlanesIntegrationTest
.accountWithoutMembershipIsRefused`), so "every member writes the tenant's customers" is a sound owner
decision: record it with that reason; the role check is then the owner's call, not a rule. A field that names a
person (a name, an email, a phone) on a shared root asks the same for reads: list, get, filters and
dropdown carry the role unless the owner decides that every account may search those people (record
it). The role that writes a shared root gets that resource named in its javadoc (`Role`: "ADMIN: desk
staff: the catalogue, loans, settings") **when its first write is generated**, whether or not the
convention was decided at bootstrap; when every role writes it, it is named on each. Check first that
`Role`'s javadoc is true for the pieces the project has (with tenancy the role is the membership's,
granted through `/tenant-members`; with the session version a change reaches the next request) and
correct it before adding to it.

**A shared root that other records will reference** asks two more questions before its first child
exists, while the answer is cheapest:
- **retired or deleted?** A catalogue entry (a course, a product) that children reference is either
  retired with an active flag (it leaves the dropdown, `activeField`, and old children keep pointing at
  it: the golden `Tag` shape, REST-13) or hard-deleted, and then refused while referenced (409
  `<ENTITY>_IN_USE`, DATA-34). Record the choice;
- **how is a person erased?** A root with personal fields (name, email, photo) that children will
  reference can no longer simply be deleted (DATA-07 says personal data is truly deletable; DATA-33's
  `*_IN_USE` refuses it): ask the erasure path (delete the children first, anonymise the person's
  fields, or cascade) and record it. Golden role checks: `UploadPolicyController` (open read, `@AdminOnly` write) and
`MetadataController` (admin-only), with their 403 tests (`UploadPolicyIntegrationTest`,
`MetadataIntegrationTest.onlyAdminsReachTheApi`). A shared root has no `ownerId`: `findById`, no
owner parameter, unscoped lists (tests.md *Shared tables*).

**Module** (if `{{modules}}` is set): every generated class goes under `{{appPackage}}` with
`{module}` substituted; cross-module access follows `{{moduleContracts}}` (architecture.md
ARCH-04/05/06): a parent in another module is referenced by id (a UUID column with a database
foreign key), never by an entity relation, in `api-facades` mode, and is validated through that
module's facade (golden `todo.api.Todos` + `TodoView`, used by `PlanService`). The facade's lookups,
each scoped like the parent's own reads:
- **`get<Parent>View(id)`, not locked**, for a child write that checks nothing of the parent: the
  foreign key refuses a child written after the parent's delete, 404 `<PARENT>_GONE` (DATA-29). The
  same shape as the next one without the lock and `MANDATORY` (same-module golden: `TodoService.createTimeEntry`).
- **`get<Parent>ViewForUpdate(id)`, locked**, `@Transactional(propagation = MANDATORY)` over the
  parent's `findForUpdateBy…`, for a child write that **checks a mutable column of the parent** (lend
  only an AVAILABLE book; golden: a plan only for an open todo, `PlanService.create`, 409
  `PLAN_TODO_DONE`). The lock names that rule (DATA-29: no constraint can check another table's column).
  **Only the writes that check the state take it**: a create, and an update that moves the child to
  another parent (it checks the new one). A write that checks nothing of the parent doesn't, even one
  that can trip an exclusion constraint scoped by that parent (a loan's new dates): the constraint
  holds that rule, and its rare simultaneous overlap answers DATA-35's 503 (document it). The parent's
  state change and delete then wait for a locked write: the delete answers 409 `<PARENT>_IN_USE`, and
  `<PARENT>_GONE` is unreachable on that write (keep its mapping, don't document it there); an
  exclusion constraint scoped by the locked parent answers 409 only among locked writes, one scoped
  wider (the owner's plans across todos) may still answer 503 (golden `PlanService.create`'s javadoc).
  Tests: rows 23 (locked outcome) and 23b, both through `writesHeldWhileTheParentWaits`.
- **The parent's state on the view** is a value the owner computes, a boolean for the rule
  (`TodoView.open`, `BookView.available`), never the parent's domain enum: `ModuleBoundaryArchTest`
  keeps `domain` types out of `api`. When consumers need the states themselves, the enum moves to
  `<module>.api` (golden `media.api.MediaFileStatus`).
- **`find<Parent>View(id)`, an `Optional`**, for a child read before it (the child's GET): the parent
  can't be deleted under its children, so a parent missing at the second read went after the child,
  and the answer is the child's own 404 `<ENTITY>_NOT_FOUND`, never the parent's code (golden
  `PlanService.findById`). **`get<Parent>Views(ids)`** for a list page, one statement per batch, absent
  ids simply absent (4.3: a row whose parent it misses is left out).

**A parent the child's create picks** (every FK in a create request: an enrollment's student and
course): ask, per parent, whether its screen picks it from a dropdown, and whether the update may
change it (a ticket moved to another customer: the update request carries the id, the move is tested
with its race, tests.md row 23; or the column is `updatable = false`). A parent without
`{{dropdownAnnotation}}` then gets one, generated for that parent in update mode (4.11); the answer
"the list's `query` is the picker" is recorded in the profiles' conventions. Never leave it unasked:
the picker is the first screen that needs it. Whichever it is, check that the picker can be used by
**every role that writes the child** and searches the **label the screen shows**: a list behind
`@AdminOnly` (the tenancy piece's `GET /tenant-members`, which searches emails) is no picker for a rota
every member writes. For a person reference with tenancy, a `Members`-backed members dropdown readable
by every member is the fitting picker; it is not in the tenancy piece yet, so a project that needs one
records it as an open item (or the owner accepts typing the person's id, recorded).

A module without a facade (the sign-in piece's `user`) gets one in the same change: an interface in
`<module>.api` that its service implements, with its view record. **With tenancy, other modules ask
`Members`** (the tenancy overlay's `user.api.Members` + `MemberView`, implemented by
`MembershipService`): `isMember(userId)`, `getMemberView(userId)` and `getMemberViews(userIds)` (one
statement, absent ids simply absent), each scoped by `TenantContext.current()`, answering
`TENANT_MEMBER_NOT_FOUND` (no second "member not found" code; golden `MembersIntegrationTest`). Never the
membership or user repositories: a reference to a person is validated there before the write. A
project that needs more about a member (a role rule) adds a method there, never a domain type in the
view. **The view never goes on the
wire:** the consuming module maps it into a response record of its own (golden `PlanTodoResponse`,
`TodoAttachmentResponse`; ARCH-06, enforced by `ModuleBoundaryArchTest`).

---

## Step 2: Pre-flight the entity and its migration

Check before generating:

| Check | Rule |
|---|---|
| Id time-ordered per `{{idStrategy}}`, app-assigned, `updatable = false` (from `{{AuditBase}}`) | DATA-09 |
| Extends `{{AuditBase}}` (audit columns + `@Version`) | DATA-06 |
| Lombok `@Getter @SuperBuilder`, protected constructor, `@Setter` only on free fields; state changes are entity methods; relations `LAZY`, collections read-only | JAVA-16, SMELL-16 |
| String columns `text`, plain `@Column`: no `columnDefinition`, no `length`, no length constants (the changeset types the column, `ddl-auto: validate` checks it; golden `Tag.color`) | DATA-12 |
| Starting values (a status, a boolean) in the entity with `@Builder.Default`; booleans primitive, `NOT NULL`, database default | JAVA-16 |
| Impossible-state guards throw `IllegalStateException` | JAVA-20 |
| No soft-delete flag; statuses are enums with a check constraint | DATA-07 |
| A changeset with `--rollback`; FKs named `fk_<table>_<column>` | DATA-13 |
| Unique text ignores case and surrounding spaces (one of the two shapes; partial when nullable). A column whose `CHECK` admits one spelling only (an ISBN of exactly 13 digits: no case, no spaces) takes a plain `UNIQUE`, and its `@Schema` says "unique" without "ignoring case" | DATA-05 |
| A unique **code or identifier** (a course code, an SKU): **ask** "store it normalised (`CS101` upper-cased on write), or keep what was typed?". Normalised is DATA-05's first shape: the mapper upper- or lower-cases it, a `CHECK`, one plain `UNIQUE` that serves uniqueness, every `code = ?` lookup and the CODE order. The `CHECK` is the format when the request has a `@Pattern` (`CHECK (code ~ '^[A-Z0-9-]{1,20}$')`, upper-cased: it implies the normalisation; `code = upper(trim(code))` alone checks the case and lets a fixture or an import store a code no PUT can send back), else the normalising expression. The column is `text COLLATE "C"` (DATA-12: a code compares and sorts byte for byte, as Java does; names keep the locale's collation). Kept as typed is the second: a unique index on `lower(trim(code))`, an order on the code needs its own `(code, id)` index, and a lookup must name the same expression, never `WHERE code = ?` (a sequential scan). Prefer normalised for codes; record the answer | DATA-05 |
| An **email** field: stored lower-cased (the first shape, golden `users`: `ck_<table>_email_normalized`, plain unique); the request shape is 4.1's *Email* | DATA-05 |
| A value the mapper normalises (an email lower-cased and trimmed) has a `CHECK` with the same expression (`email = lower(trim(email))`, golden `users`) | DATA-05 |
| A unique rule on an entity with a terminal status (WITHDRAWN, CANCELLED): **ask** whether it spans every row or only live ones (re-enrolling after a withdrawal); live only is a partial unique index `WHERE status = '<LIVE>'` with a test that the terminal row doesn't block a new one. Its "taken on update" test (tests.md row 11) means the writes that move a row **into** the live status or change the pair; when none does (the pair fixed at the create, no move back to live), the create's test is the whole duty: say so. When the entity also has a **date range**, ask the follow-up: "does a finished record (past its `endsOn`, still `<LIVE>`) still block a new one?" (a student who completed a course retaking it). If not, the rule is either no overlapping live ranges, DATA-35's exclusion constraint `WHERE (status = '<LIVE>')`, or a decided terminal status (`COMPLETED`, set by a person or a job), never "live" read from the dates (DATA-37) | DATA-05, DATA-35, DATA-37 |
| Indexes: every FK; keyset sort keys non-null (a generated column for a nullable one). **While the entity's changeset is not applied**, ask the list's filters and orders now (Step 3's list kind, 4.1's filters, sorts and dropdown), so their indexes (4.1) go into that changeset, not into a second one that repeats its FK indexes | DATA-10 |
| A child of an owned parent: the composite owner FK, `moveTo` or `updatable = false` on the parent column | DATA-33 |
| `Instant` for moments, `LocalDate` for picked dates, no time zones | JAVA-25 |
| The sequence exists for a display number (single-tenant only; with tenancy a display number is a per-tenant counter, the TEN-18 row below, never a sequence) | DATA-14 |

Golden examples: `~/.claude/skills-reference/src/main/resources/db/changelog/changes/000{2,3,4,5}-*.sql`.
**Applied** means the changeset has run on any database that outlives a test run (a teammate's, a
shared dev or staging one, production) or has been pushed, where someone else may run it; a changeset
that only ever ran on Testcontainers is still yours to edit. Committed alone isn't applied; a run of
the app is: look for the changeset's id in `databasechangelog` of the local database (with
`{{tenancy}}` = `schema`, of the template and every tenant schema) when a local stack has started
since it was written. When unsure, it is applied: write a new changeset. Changesets use `--changeset {{changesetAuthor}}:NNNN-…` (the golden files say `dev`: a placeholder).

List **every** failure, of every entity in the run, in **one table** (check, what is wrong, the fix:
the entity change and a **new** changeset; never edit an applied one), then ask **once** with
**AskUserQuestion**: apply all, or pick. When nobody can be asked (a non-interactive run), apply all
and say so in the report. A declined fix: generate anyway and list it as an open item.

**Add every new changeset to `db.changelog-master.yaml` right away**, for all the entities you will
generate, before the first build: Hibernate validates every entity's table at startup, so a missing
child table fails the build of the parent too.

**With `{{tenancy}}` = `schema`** (house-review `tenancy.md`): the master is the **tenant** changelog,
`db/changelog/tenant.yaml` (every tenant schema and the template get the table; Hibernate validates
against the template, TEN-24); never `platform.yaml`, which holds the platform's own tables. The table
has **no `tenant_id`** (the schema is the tenant), the entity's `@Table` names no schema, and a
native query names none either (TEN-21): the connection hook picks the schema. A reference to a person
is a foreign key to the schema's own `members (user_id)` (`fk_<table>_owner_id`), never to
`platform.users`, and a person the request names (an assignee) is checked through `Members` (Step 1)
before the write: no key leaves the tenant schema (TEN-16, `SchemaCatalogIntegrationTest`), so the schema
can move to a database of its own. Ownership checks are unchanged: they separate users inside one tenant.
A display number is a per-tenant counter (`Counters`, TEN-18): a **fixed** counter name (`TICKET_NUMBER`),
seeded at 0 by an insert in the entity's tenant changeset (it runs in every tenant schema and the
template), never `perContext` with the tenant id.

**With `{{tenancy}}` = `row`** (house-review `tenancy.md`, TEN-16…19 and TEN-30…38; golden: the `tenancy-row` blocks of
the reference's `0002`–`0006` changesets and its todo entities, which bootstrap uncommented in the
project's copies, and the row overlay's `0017`–`0019` for the keys added since), add these pre-flight rows for every tenant table (a platform table is one the user
names as such; it joins `RowSecurityCatalogIntegrationTest.PLATFORM_TABLES` with its reason):

| Check | Rule |
|---|---|
| The entity extends `TenantOwnedEntity` (`@TenantId`), never `{{AuditBase}}` directly; no `tenantId` in its builder calls, requests, filters, repositories or services | TEN-32, TEN-01 |
| `tenant_id uuid NOT NULL` with **no default** and `fk_<table>_tenant_id → tenants (id)`: every insert names its tenant, so a forgotten one fails at once. The golden `0002`–`0011` blocks still show a `current_setting` default; it predates the rule and the row overlay's `0016` drops it (an applied changeset is never edited): never copy it into a new changeset | TEN-30 |
| `ENABLE` + `FORCE ROW LEVEL SECURITY` and the one `tenant_isolation` policy (`USING` and `WITH CHECK`, the same predicate) **in the changeset that creates the table** | TEN-30 |
| Every unique constraint, exclusion constraint (`tenant_id WITH =`) and list index has `tenant_id` among its leading equality columns: the owner (or a parent column) first when the table has one (`(owner_id, tenant_id, sort…, id)`, `(customer_id, tenant_id, id)`: it also serves the key's check), `tenant_id` first when it has none or a list filters by the tenant alone (`(tenant_id, sort…, id)`); names as in single-tenant. A scope column that determines the tenant (an owner, a member) gets `CREATE STATISTICS st_<table>_<column>_tenant_id (dependencies) ON <column>, tenant_id` in the same changeset (golden row overlay `0023-owner-tenant-statistics.sql`): without it the planner estimates a person's rows at one once other tenants fill the table | TEN-33, DATA-10 |
| A reference to another tenant table: a composite key `(parent_id[, owner_id], tenant_id) → parent (id[, owner_id], tenant_id)`, the parent's unique key with `tenant_id` last, added **in the child's changeset** (the parent's is usually applied by then; the key exists for the child). A reference to a person (an owner, an assignee) is to the tenant's own members, never to `users` or `tenant_memberships` (platform tables): `(owner_id, tenant_id) → members (user_id, tenant_id)` as `fk_<table>_<column>` (golden: the row overlay's `0018-owners-reference-members`); a person the request names (an assignee) is checked through `Members` (Step 1) before the write, since the key also accepts a member who left. A member's row is never deleted (a removal marks it left), so the key never blocks a removal and the record keeps its owner; a project whose records must be reassigned before a removal says so (an open item: the members API has no such check) | TEN-33, TEN-16, TEN-17 |
| The replica identity: `ALTER TABLE <t> REPLICA IDENTITY USING INDEX <uq_…>` **in the changeset that creates the table**, on a unique key that includes `tenant_id` (the parent key `(id, …, tenant_id)` of its composite references, or `(tenant_id, key)`); a table without one gets `uq_<table>_id_tenant_id (id, tenant_id)` | TEN-38 |
| A display number (an invoice or order number) is a per-tenant counter (the metadata piece's `Counters`), never an identity column or a sequence: one sequence serves every tenant. `counters` is tenant data, so it is a **fixed** name (`TICKET_NUMBER`), not `perContext` with the tenant id; it needs the TEN-34 seed pair (a listener with `Counters.createIfAbsent(name, 0)` plus a backfill changeset), and the create calls `createIfAbsent` before `next` too (a tenant activated by an instance without the listener, during a rolling deploy, still numbers from 1). A `uq_<table>_number (tenant_id, number)` backs it up. Tests: rows 27b–27d | TEN-18, TEN-34 |
| A changeset already applied gets no new comment (its checksum changes): a later note (a TEN-36 search warning) goes in the runbook or the entity's javadoc | DATA-13 |
| No seed rows in the entity's changeset: a new tenant's starting rows come from a `TenantProvisioned` listener in the module (golden `TodoAttachmentPolicySeed`, `TodoListDefaultsSeed`), the existing tenants' from a backfill changeset that lifts `FORCE` for its own transaction (golden `0010`'s and `0014`'s `…-existing-tenants`). The backfill also reaches a tenant still PENDING, so **the listener creates its row only when missing** (`Uploads.definePolicy`, `Metadata.createIfAbsent`, `Counters.createIfAbsent`): a plain insert fails the approval on the unique key, on every retry, and the tenant can never be activated. Tests (golden `TodoSeedsBackfillIntegrationTest`): the backfill for a tenant without the row, and a PENDING tenant approved after the backfill (ACTIVE, one row, its owner signs in) | TEN-34 |
| A native query on a tenant table names its tenant: `AND tenant_id = :tenantId` once per tenant table it reads or writes (a subquery's too), a `UUID tenantId` first parameter, the service passing `TenantContext.id()` (a job's batch runs under `TenantScope.runAs`, so it gets the tenant the job runs for); an insert lists `tenant_id`. The policy is the fallback, not the filter (golden: the row overlay's `MediaFileRepository`, guarded by `NativeQueryTenantIntegrationTest`). A search on `jsonb @>`, `LIKE`/`ILIKE` or range `&&` is flagged as a filter over the tenant's rows | TEN-37, TEN-36 |
| Statuses `text` with a check constraint, never a PostgreSQL enum (its `=` isn't leakproof) | DATA-07, TEN-36 |

The master (`db.changelog-master.yaml`) lists the platform's changesets first; the new changeset goes
at its end.

---

## Step 3: Decide the variant and the list kind

- **Variant M: aggregate member** (from Step 1; golden `TimeEntry` and the `*TimeEntry*` methods of
  `TodoApi` / `TodoController` / `TodoService`): no service, controller or API of its own; endpoints
  nested under the root (`/<roots>/{<root>Id}/<members>`) in the root's API, controller and service.
  Ask: may a root with members be deleted (cascade), or is it 409 `<ROOT>_IN_USE`? Follow the golden
  methods for the rest (owner check on the root first, the root read without a lock on create: its
  foreign key refuses an entry after the root's delete, 404 `<ROOT>_GONE`; the root's version
  untouched).
- **Variant N: many-to-many between aggregates** (golden `TodoTag`, `Todo.replaceTags`,
  `TodoTagsRequest`, `PUT /todos/{id}/tags`, `TodoService.withTags`, `0006-todo-tags.sql`). Ask:
  which aggregate **writes** the link (the link becomes a member `<A><B>` of that root, ARCH-15)?
  When B is deleted, do links simply disappear (`ON DELETE CASCADE` on the B side) or is it 409
  `<B>_IN_USE`? Two facts the golden code relies on:
  - A's mapped-by link set carries `@OptimisticLock(excluded = false)`, or A's version never moves
    when the set changes (DATA-30);
  - B's rows are read through B's service without a lock (`getOwned<B>s`): a link written after B's
    delete is refused by its foreign key, mapped on the write side to 404 `<B>_GONE` (DATA-29,
    DATA-33).

  Schema (composite owner FKs on both sides, DATA-33), API (`PUT /<as>/{id}/<bs>`, A's own PUT
  refuses `<b>Ids`) and the list (one grouped query for a batch's links) as in the golden files.
- **Versioned document** (a rate, a price list, terms or a form whose records must keep the version
  they were made from; DATA-36): **no golden code yet**, so crud-gen does not generate it. Say so,
  and offer the plain entity plus a hand-written version table that follows DATA-35's version-chain
  shape and DATA-36 (version id and snapshot columns on the records, `draftFrom`, the drift test).
  A status the clock changes (`EXPIRED`, `CURRENT`) is never generated as a stored enum (DATA-37).
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

**Shapes without golden code yet** (money, exactly one current, month values, a record two parties
see with different rights (REST-16), a use case writing two aggregates (a `<UseCase>Manager`,
ARCH-15's shape)): follow the house-review rules and ask; don't invent a shape. A **status workflow**
(several states, only some moves allowed) copies the tenant lifecycle with tenancy, or its shape
without it: the moves as one `canBecome(target)` switch on the status enum (`TenantStatus`), the
entity's change method refusing the rest with 409 `<ENTITY>_STATUS_CHANGE_INVALID` naming both
states, and a test per allowed and per refused move. A move to the record's **current** status is
refused like any move the switch doesn't list (409, tested), unless the owner decides it is a no-op
(golden `Todo`'s status is a flag, not a workflow: there it is a no-op); say which in the API text. A
workflow's refusals are 409s through the change method, so its unit test (tests.md row 26) asserts
those, not an `IllegalStateException`. A target that no state may move to (a status
only the create sets, `ACTIVE` for an enrollment, `PENDING` for a tenant) is refused the same way, 409
with that code: the move is valid in itself, the lifecycle refuses it (ERR-05). **Ask about the
terminal statuses** (WITHDRAWN, CANCELLED, CLOSED): may a record in one still be edited (PUT, the
member and file writes) or deleted? When it is frozen, the entity's edit method guards it **first
after the version check**, before any other rule (a configured maximum, a parent's state): 409
`<ENTITY>_<STATUS>` (`ENROLLMENT_WITHDRAWN`, ERR-04's names), documented on each refused write, with
tests.md row 26b (whose write would also break another rule, so the order is tested); when only the delete stays open, say so in the API text. Their tests take each
party × each action, and for a Manager each aggregate's refusal rolling both back.

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
these). A filter whose `query` predicate is shared with the entity's dropdown implements
`QueryFilter` (golden `TodoListFilter`, `TagFilter`), so one helper serves both; a list without a
dropdown needs no interface (golden `TodoFilter`). The dropdown's `query` searches its label only;
when the list's `query` searches more (a name **or** an email), the two predicates differ, and the
filter doesn't implement `QueryFilter`.

**Then the indexes** (DATA-10), now that the orders and filters are chosen: one per offered order
and per scoping filter, ending with the id (none when the sort column is unique and not null), in a
new changeset if the entity's own one is applied (Step 2). Write the list's **cell matrix** while you
choose them (tests.md, *Index tests*): each order × each filter (each value of an enum filter), a later
batch or page, the `ids` lookup, each second step, each delete-side key check, each naming the index
that serves it and the most rows it may read; test row 28 runs it. A cell no index serves gets one or is
not offered: **a range filter is offered under its own column's order only** (under another order it is a
400 naming the rule, golden `TodoFilter.isDueOnOrBeforeSortedByDueDate`), unless a bounded scope holds it
(one todo's time entries), recorded. An order index
that leads with a foreign key column (`(author_id, id)`) also serves that key's checks: it
**replaces** the key's own index from the pre-flight (`idx_books_author_id`), never sits beside it
(golden `0003-todos.sql`'s comment). **A low-cardinality enum filter** (a status, a priority) is
judged per value and per offered order, over the rows its scope leaves (the owner's rows; on a shared
root, the whole table): a value is **rare** when it holds a small share of them (about one in ten or
fewer: the owner's open todos among years of done ones, a book catalogue's withdrawn books, the active
loans among the history). A filter with a rare value is **scoping**, and gets one index per offered
order (`(<scope>, status, <sort keys>, id)`; golden `idx_todos_owner_id_status_due_sort_id` and
`…_status_id`): walking the order's index with the filter reads every other row that comes first, and
the rare rows come last under some order (active loans have the latest days, so `STARTS_ON` walks the
whole history first; measured 165 ms against 0.06 ms with the index). Leave it to the order's index
only when (record which and why, so the review doesn't ask again):
- no value is rare (golden `0003`: priority, three values, none rare); or
- the scope bounds the walk to a few pages whatever the value (status within one list: a list is
  small); or
- every offered order brings the rare value's rows first (the newest-first order for the newest
  status), checked per order, never for the default one alone.

**Every list gets `ids`** (REST-23; golden `TodoFilter.ids`, `TodoListFilter.ids`):
`List<@NotNull ID> ids` with `@ConfiguredMaxSize("cursor-page.max-size")` (offset:
`"offset-page.max-size"`), `requireIdsPredicate()` (non-null and non-empty), an `in` predicate in the
list query (offset: in the page **and** the count query; not in the dropdown), and
`@Schema(description = "Find by ids …: unknown or another user's ids are simply absent")`; a record
shared by the tenant (no owner scope) says "unknown ids, or another tenant's, are simply absent" (the
cross-tenant test proves it), and one shared by everyone "unknown ids are simply absent". The list's
description and its 400 response name it.

**Shapes** (golden `Tag` / `TimeEntry`):
- **Range filters:** `<x>From` / `<x>To` for dates, `min<X>` / `max<X>` for numbers, both inclusive;
  each bound carries the field's own constraints (`@Min`, `@ConfiguredMax`); the filter has one
  `@AssertTrue boolean is<X>RangeOrdered()` per range (true when either bound is absent), so an
  inverted range is a 400 naming `<x>RangeOrdered` (golden `TimeEntryFilter`).
- **Flags:** the list filters a flag as `status` (`DropdownStatus`, default `ALL`), the dropdown
  defaults to `ACTIVE`; a flag without rules is set by the update (`@Setter`).
- **Pattern fields:** `@Pattern` with a readable message instead of `@MaxLength`, **only when the
  pattern bounds the length**: closed classes, every quantifier `{m,n}`, nothing unbounded anywhere
  (golden `TagColor`: `(#[0-9A-Fa-f]{6})?`; `@Pattern` matches the whole value). Never `\s*` or `.*` around it to tolerate
  spaces: surrounding spaces of a format-validated value are a 400 (and test row 11 tests case
  only); if spaces must be accepted, add `@MaxLength` too (REST-09). Normalised in the mapper (case),
  backed by a database `CHECK`. The message names the **exact** character classes and length, never a
  looser word: `[A-Za-z0-9-]{1,20}` is "1 to 20 ASCII letters (A-Z, a-z), digits or hyphens", not
  "letters" (`ÄB1` is a letter and is refused).
- **Ranges in a body** (a plan's `startsOn`/`endsOn`): one `@AssertTrue @Schema(hidden = true)
  boolean is<X>RangeOrdered()` on the request record, like a range filter, backed by a `CHECK`
  (golden `PlanCreateRequest`); the API text says whether the ends are inclusive. An updatable range
  changes through **one** entity method that takes both ends and guards them (`reschedule(from, to)`,
  `IllegalStateException` on an inverted range; no golden code: `Plan` is immutable), never a setter
  per end. When the range's scope column (the exclusion constraint's `owner_id WITH =`, a shift's
  member) is updatable too, it changes through an action of its own (`handOverTo(member)`), not a
  third parameter of `reschedule`: each guards its own rule and is tested on its own. Its API is a
  `PATCH /<entities>/{id}/<scope>` (`PATCH /on-call-shifts/{id}/member`) with a
  `<Entity><Scope>Request {<scope>Id, version}`; a person is checked through `Members` (404
  `TENANT_MEMBER_NOT_FOUND` for a removed one), and moving to the current value is a no-op answering
  200 with the version unchanged.
- **A filter for "what is on during [from, to]"** is an overlap, not the range `&&` operator (not
  leakproof under row-level security, TEN-36). Two comparisons alone (`endsOn >= :from AND startsOn
  <= :to`) give the index **no lower bound**: every index orders by the start, so `endsOn >= :from`
  filters the scope's whole history before `:to`, and "who is on call this week" reads years (DATA-10).
  So **ask the maximum range length** (a plan spans at most a month, a shift a week) and make it a
  configured limit (`limits.yml` `<entity>.max-days`, with the comment "raise it any time, lower it
  only once no stored range is longer"): checked on create and update by `@ConfiguredMax("<entity>.max-days")`
  on a hidden getter `getLengthInDays()` (null while a date is missing or the range inverted), and used
  by the repository for a third predicate, `startsOn >= :from - (max - 1)` (golden `PlanCreateRequest`,
  `PlanFilter.plannedFrom`/`plannedTo`, `PlanRepositoryImpl.filterPredicates`). Without a maximum
  (open-ended ranges), record the decision that the filter scans the scope's history. Tests: tests.md
  rows 25b and 28.
- **Email** (golden: the sign-in piece's `SignupRequest.email`, `User.normalizeEmail`):
  `@NotBlank @Email @MaxLength("<entity>.email") @Pattern(regexp = "\\p{ASCII}*", message = "must
  contain only ASCII characters")` (254 in `limits.yml`; `*`, not `+`: an empty value gets only "must not
  be blank"), `@Schema` "Email address (ASCII only);
  stored lower-case; unique ignoring case". The mapper stores `email.strip().toLowerCase(Locale.ROOT)`
  (another module can't call `User.normalizeEmail`: the two lines are repeated), backed by
  `CONSTRAINT ck_<table>_email_normalized CHECK (email = lower(trim(email)))` and a plain
  `UNIQUE (email)`, which a lookup by email uses (DATA-05). Tests: a malformed address 400; the
  length bound both sides with an address `@Email` accepts (64 characters before the `@`, domain
  labels of at most 63: `"a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." +
  "d".repeat(58) + ".io"` is 254); surrounding spaces 400 (row 11 tests the case only); a case-only
  duplicate 409 `<ENTITY>_EMAIL_TAKEN`, on create and update. **That is the platform's `users` shape.** On
  a **tenant table** (row mode) the key is `uq_<table>_email UNIQUE (tenant_id, email)` (also its
  replica identity, TEN-38), the `@Schema` says "unique in your organisation", and a cross-tenant test
  proves the same address is accepted in another tenant (schema mode: the plain key, per schema, and the
  same test).
- **Phone numbers:** ask the shape and record it: a format (`@Pattern` of E.164, `^\+[1-9][0-9]{6,14}$`,
  stored as sent, a `CHECK` with the same expression and the message naming it), or "as typed" (free
  text with `@MaxLength`, no uniqueness, the decision recorded). Never a phone as a key without the
  format.
- **"None" is null:** optional text and pattern fields store blank as `null`; the `@Schema` says
  "null, absent or blank for none (returned as null)".
- **Calendar dates:** every date that reaches a column or a query, filters included, carries
  `@EarliestDate("<entity>.<field>")` (`app.limits.earliest-date`): a `LocalDate` reaches years
  PostgreSQL's `date` can't hold, and `-300000-01-01` is otherwise a 500 at the database (REST-09).
  A date that records the past (a day worked, a birth date) also takes `@NotAfterToday`, never
  `@PastOrPresent` (JAVA-25); a filter on it a sanity `@LatestDate`. **A state action that records a
  day** (returned on, approved on) takes it in the action's request, `@NotNull @EarliestDate
  @NotAfterToday` (the user's own today, sent by the client), checked against the record's own dates
  (a return before the loan's start: 400 `<ENTITY>_<FIELD>_BEFORE_<OTHER>`), never
  `LocalDate.now(clock)`: the server's UTC day is yesterday for a desk east of UTC until midnight UTC
  (JAVA-25). A moment the action records stays the server's `clock.instant()` (golden
  `Todo.changeStatus`'s `completedAt`); the day's shape is `TimeEntryCreateRequest.spentOn` on the
  action's record (no golden action takes one). A planned date (a leave, a plan)
  lies ahead or behind: no `@NotAfterToday`, a sanity `@LatestDate` (golden `plan.dates`), and the
  rules the business gives (within one calendar year: name it so, e.g. `isInOneCalendarYear`; no
  golden code).

### 4.2 Requests (records)
`<Entity>Create{{requestSuffix}}`, `<Entity>Update{{requestSuffix}}` (with `@NotNull Long version`),
one per state action (`<Entity>Status{{requestSuffix}}`). **Every String carries `@MaxLength("<entity>.<field>")`**, the
elements of a String collection too (`Set<@NotBlank @MaxLength("…") String>`), and its
number is added to `app.limits.text` in `config/limits.yml`; collections carry a configured size
limit (`@ConfiguredMaxSize("<entity>.<field>")`) and `@NotNull` elements, plus `@NotEmpty` only when
an empty collection is meaningless (an optional set of tags may be empty); a date with a meaningful upper bound carries `@LatestDate("<entity>.<field>")`
(`app.limits.latest-date`), e.g. below a sort sentinel. `@Schema` on every component. Golden:
`TodoCreateRequest`. **A text field a cursor carries** (a cursor list's text sort key, a dropdown's
label) sizes the cursor: `app.limits.text.cursor` must hold `(6 × its @MaxLength + 100) × 4/3`
(Base64url JSON, a control character escaped in six bytes), so raise it when that is larger, and add
the field's key to `LimitsIntegrationTest.CURSOR_TEXT_KEYS`; otherwise the next batch after a long
value is a 400 on the cursor the server handed out (tests.md rows 16, 24).

### 4.3 Responses (records)
`<Entity>Response` (every field, FKs as ids, ending with `{{auditFields}}`; a reference to another
aggregate that clients display is `{id, <the parent's display field under its own name>}`, golden
`PlanTodoResponse {id, title}`: a student's `{id, fullName}`, a course's `{id, title}`, never a
field renamed to a literal `name`; one record per referenced aggregate (`<Entity><Parent>Response`),
a list of them for a many-to-many) for the detail; for list
rows a `<Entity>SummaryResponse` without heavy columns (long text), still ending with the audit
fields. Golden: `TodoResponse`, `TodoSummaryResponse`. A list row's displayed parent name comes in
**two steps**, in the same module as across modules: the batch, then one lookup of the batch's parent
ids for their names (golden `PlanService`'s facade batch lookup; statement count + 1), never a join in
the keyset query (with tenancy the join carries the tenant predicate twice and its plan turns on the
cursor's position). The two statements see the database at two moments: a row whose parent the
lookup misses (deleted with it, or moved and its old parent deleted, in between) is **left out** of the
batch, never mapped with a null parent into a 500; the cursor still continues after the batch's last
row (golden `PlanService.withTodos`, test `listLeavesOutAPlanWhoseTodoVanishedBetweenTheReads`). A
single read of the child, then its parent, answers the child's own 404 when the parent is gone (Step 1's
`find<Parent>View`; golden `PlanService.findById`). That two-read rule is for **another module's**
parent (an id column and a facade). Three more shapes:
- **a same-module parent on the detail** (a `@ManyToOne(fetch = LAZY)`, golden `Todo.todoList`) is read
  **with the child in one statement**: an `@EntityGraph(attributePaths = "<parent>")` finder or a fetch
  join of the to-one. A lazy second read is the proxy's initialisation, and a parent deleted between the
  statements surfaces as `EntityNotFoundException`, a 500 (tests.md row 28f). List rows keep the batch
  lookup above;
- **a write's response** (an update, a status action) maps its parents exactly like the single read,
  never through an unlocked lazy relation or a bare facade call: a write that changes nothing flushes no
  `UPDATE` and so holds no row lock, and the parent can go between its statements as for a GET (the
  child's own 404, tests.md row 28f);
- **a person reference** (an owner, an assignee, a member on a rota) never "vanishes": a removal deletes
  the membership, which `Members` reads, while the tenant's `members` row stays, marked left. Left out or
  404 would hide a removed person's records for good (their coming shifts gone from the rota, a GET 404
  while PUT and DELETE still find the record). Ask how a former member's records show, and record it:
  the person by id with a `null` name (the default), or a `left` flag on the view; every row shown, the
  GET answers, and a create or hand-over **to** a removed member is 404 `TENANT_MEMBER_NOT_FOUND`
  (tests.md row 28g).

### 4.4 ErrorCodes and messages (error-contract.md)
`ERROR_PREFIX_NOT_FOUND`, `ERROR_PREFIX_<FIELD>_TAKEN` per unique field, `ERROR_PREFIX_IN_USE` when
referenced; a unique rule over **several columns** is named after the rule, not a field:
`ERROR_PREFIX_EXISTS` for a pair unique over every row (golden `TENANT_MEMBER_EXISTS`,
`uq_tenant_memberships_user_id_tenant_id`), `ERROR_PREFIX_ALREADY_<STATUS>` for one partial on a live
status (`ENROLLMENT_ALREADY_ACTIVE`: the student is enrolled in that course already), each with a
message that says it in words (error-contract ERR-04's names); **a status per code in `ErrorCodeHttpStatusMapper`** (the error contract's mapper, not the
entity's; `ErrorCodeContractTest.everyErrorCodeHasAStatus` catches a missing one); a message in every
`{{messagesFile}}`; the handler's
`{{constraintCodeMap}}` entries (unique index names → `*_TAKEN`; FKs pointing **to** this table →
`*_IN_USE` for the parent's delete, and `ERROR_PREFIX_GONE`, a 404 without placeholders, for a
child's insert or update after it: `byForeignKeyOnWrite`). Never check-then-insert (DATA-05); every constraint kind maps (ERR-02), except a key that can't trip once the service checked its target first: a person's key to `members` (checked through `Members`, whose row is never deleted) stays unmapped, with that reason in the module's `ConstraintErrorCodes` javadoc. A state
conflict is 409, a wrong value 400 (ERR-05). A child's foreign key adds the parent's `*_IN_USE` to
**the parent's** delete docs (`@ApiResponse(409)`) in the same change, asserted in **the child's**
test class (with the parent's `deleteWhileReferencedIs409`): the parent's existing tests stay unchanged
(*Updating an entity* rule 4). Never document a code before the constraint that raises it exists (no
`*_IN_USE` on a delete nothing references yet).
**Bootstrap brings no feature codes**: every code, mapping, message, constraint entry and limit key
is added here. Constraint mappings go in the module's own `ConstraintErrorCodes` bean (the handler
aggregates them): the module **whose changeset declares the constraint** maps it, even to another
module's code (golden `PlanningConstraintErrorCodes`: `fk_plans_todo_id_owner_id` → `TODO_IN_USE`).
A child's composite owner FK `(parent_id, owner_id)` maps twice: to the parent's `*_IN_USE` in
`byConstraintName` (the delete side) and to the parent's `*_GONE` in `byForeignKeyOnWrite` (the
insert/update side; a cascading key only there), golden `TodoConstraintErrorCodes` (DATA-33). A
code answered from a constraint gets a message without `{0}` (the handler has no argument for it);
an apostrophe is written `''`, and a year or an id passed as a number `{0,number,#}` (ERR-06). No
overlapping ranges: an exclusion constraint mapped to `<ENTITY>_OVERLAPS`, with no scope lock (a
simultaneous overlap answers a retryable 503; DATA-35, golden `0011-plans.sql`, `PlanService.create`).
A lock a write already takes for a state rule (Step 1) queues the writes it covers, so their overlaps
answer 409; never take a lock only to turn that 503 into a 409 (DATA-29).

### 4.5 Mapper
Golden `TodoMapper`: `toEntity(request, resolvedParents…, ownerId)`, `update(entity, request,
resolvedParents…)` (only mutable fields; moves through the entity method), `toResponse(entity)`. No
`toResponses`: list pages are projections (4.7).

### 4.6 Repository
`<Entity>Repository extends {{BaseRepository}}<Entity, ID>, Custom<Entity>Repository`, plus
`findByIdAndOwnerId` when owned, and a lock only for a rule no constraint, foreign key or
`@Version` holds (DATA-29), with a one-line javadoc naming that rule: a
`@Lock(PESSIMISTIC_WRITE) findForUpdateBy…` when a write changes its member rows (files, links) or
deletes it (golden `TodoRepository`), also taken on a parent before a cap, a multi-row rule or a check
of the parent's state (row 23b; golden: the todo's `findForUpdateByIdAndOwnerId`, taken by a plan's
create through `Todos.getOwnedTodoViewForUpdate`); a conditional `@Modifying` update with a
rows-affected check for a claim (golden `RefreshTokenRepository.revokeIfLive`). No lock on a parent
that children are written into for anything else: the foreign key holds it. Always by id and owner, so nobody queues behind a row they can't see.

### 4.7 Custom repository and Criteria projection impl
Golden: `TodoRepositoryImpl` (cursor), `TodoListRepositoryImpl` (offset + dropdown).
- **Projections only**: `builder.construct(<Row>.class, …)`; never entities on a list page.
- **Each method writes its query top to bottom** (SMELL-29): `builder`, `query`, `root`,
  `query.select(...)`, the predicates, `where`, `orderBy`, then the batch. Long parts are plain named
  steps that return a value (golden `TodoRepositoryImpl`, `TimeEntryRepositoryImpl`,
  `PlanRepositoryImpl`: `summaryOf`/`rowOf` for the select list, `filterPredicates` for the filter's
  predicates (the caller adds the scope), `seekAfter` and `orderOf` for the sort's keyset on the same
  columns, `taggedWith` for a subquery), so the method stays within `maxMethodLines` and
  `maxParams`. No helper that takes lambdas.
- **Derived values** (days between two dates, the days left of a balance) are computed by one
  static method of the entity, which the entity and the projection's constructor both call; never
  the formula written twice.
- Cursor: `Cursor cursor = Cursor.read(filter.getAfter(), sort)`; `if (cursor.isPresent())` add
  `Seek.after(builder, descending, key1, cursor.text(0), …, id, cursor.uuid(n))`;
  `query.orderBy(Seek.order(builder, descending, key1, …, id))` on **the same columns, id last**
  (no id after a sort column that is itself unique and not null: DATA-10);
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
`entity.checkVersion(request.version())` (409 `OPTIMISTIC_LOCK_CONFLICT`), then `flush()`; a write
that changes member rows (files, links) or deletes the root loads it `findForUpdateBy…` first
(golden `TodoService.update`, `replaceTags`, `delete`); member rows that another aggregate's delete
can remove by cascade (a tag's delete removes its `todo_tags` rows) are deleted with one statement
before the root's own delete (golden `TodoTagRepository.deleteAllOfTodo`), since a versioned delete
per row misses the removed ones (409); delete loads (404) then flushes; a state change on an entity
goes through a method that guards the state (an `edit(...)` calling `requirePending()`; no golden
code), never public setters a later caller can use in the wrong state; ownership through
`findByIdAndOwnerId` (another user's record is 404); parents written into are read with the plain
owned lookup (`getOwned<Parent>`, not locked, unless the write checks the parent's state: Step 1); list sizes from `{{Paging}}`. **A parent in the same
module** is reached through its service, public once a child needs it: `getOwned<Parent>(id)` returns
the entity (golden `TodoListService.getOwnedTodoList`); a batch lookup for a write needs every id
(`getOwned<Parent>s(ids)`, 404 naming a missing one, golden `TagService.getOwnedTags`), one for a
list page's names returns those found, under its own name (`findOwned<Parent>Views(ids)`: absent ids
simply absent) and as a constructor projection of the view's columns, never whole entities (DATA-11;
golden `Todos.getOwnedTodoViews` over `TodoRepository.findViewsByIdInAndOwnerId`); both answer
`List.of()` for no ids without a statement. These methods change **the parent's generated
service**: its private `get<Parent>` becomes public (with a javadoc saying who calls it), and the
batch lookup is added beside it. Name both in the report's changed shared files, and review that
service with the child (Step 8). No golden module has a same-module parent shown on a list: follow
`TodoListService.getOwnedTodoList` and `TagService.getOwnedTags` (a shared root: `get<Parent>` /
`get<Parent>s`, without the owner). **A rule from configuration** (a maximum loan
length, an upload policy's file count) on an update runs only when the fields it reads change, so a
lowered limit never blocks correcting another field of an existing record (golden
`TodoService.changeAttachments`: the file count is checked only when the files change; tests.md
row 25f). The exception is a range's configured maximum length (`@ConfiguredMax` on the update's
`getLengthInDays()`, 4.1): it is checked on every update, which is safe because the maximum may only
ever be lowered once no stored range is longer (its `limits.yml` comment says so). No remote call inside a
transaction (DATA-01). Never lock "the current X" by a mutable column (DATA-32).

**An access-changing write with the session version** (`tokenRevocation: session-version`, the
`session-version` piece): a generated write that changes what a person may do (a role or status field
of an account or membership, a grant table, a lock) raises the session version **in its own
transaction**, through the owner's bump (golden `MembershipService.changeRole` calling
`bumpSessionVersion`; without tenancy `UserService.bumpSessionVersion`, `Propagation.MANDATORY`), after
the checks that can still refuse it; where it takes access away, the same transaction revokes the
refresh tokens the code doesn't already refuse. Redis is never written in the transaction (the bump
publishes `SessionVersionsRaised`). The write still checks the caller's current role in the database
(rung 3). Tests: the older token 401 `SESSION_VERSION_STALE` on the next request, the refresh carries
the new access, a rolled-back write raises nothing (golden `SessionVersionTenancyIntegrationTest`,
`SessionVersionServiceIntegrationTest`; house-review SEC-07).

### 4.9 API interface (OpenAPI)
Golden `TodoApi`: `@Tag`; `@Operation` with text-block descriptions; an `@ApiResponse` for every
status and errorCode the endpoint can return (except `API_VERSION_UNSUPPORTED`: a customizer adds it
to every operation; error `@Content` needs no `mediaType`, a customizer publishes it as
`application/problem+json`): 200/201/204, 400 (`VALIDATION_FAILED`,
`REQUEST_UNREADABLE`, `REQUEST_PARAMETER_INVALID` for a bad id, enum, date or cursor), 401, 403
(roles), 404 (incl. a parent's `*_GONE` on a child's write), 409 (`*_TAKEN`, `OPTIMISTIC_LOCK_CONFLICT`,
`*_IN_USE`), 503. A delete documents
`OPTIMISTIC_LOCK_CONFLICT` only when it can happen: an unlocked delete can lose a race (golden
`TodoListApi`, `PlanApi`); a delete that loads its row `findForUpdateBy…`, takes no version and
removes its cascadable member rows set-based can't (golden `TodoApi`; the race test
`TodoTagsIntegrationTest.todoDeleteRacingATagDeleteSucceeds`). A write whose member rows another
aggregate's delete may remove meanwhile documents the 409 for that case (golden `replaceTags`). An `@Schema` states exactly what the validation does ("unique ignoring case" for a
format-validated field, whose surrounding spaces are a 400).
List descriptions explain the paging (cursor: `after` / `pageInfo.nextCursor`; offset: `page` /
`size`, limits from configuration). Filters are `@ParameterObject`. A description that is exactly one
error code (`"AUTHENTICATION_REQUIRED"`, `"TODO_LIST_NAME_TAKEN"`) is fine: the reference's
`config/server.yml` sets `springdoc.disable-i18n: true`, so annotation texts are published as written
(with springdoc's i18n on, such a description is resolved as a message key and the docs publish the
code's message instead; golden `TodoListIntegrationTest.apiDocsShowTheConfiguredLimits` asserts the
codes). Add the case where it helps the client (`"TODO_NOT_FOUND (missing, or another user's)"`).

### 4.10 Controller
Golden `TodoController`: package-private, `implements <Entity>Api`, class-level
`@RequestMapping("{{urlPrefix}}/<resource-plural>")`, methods with relative paths and
`version = "v1+"` (the current baseline; REST-07), 201/204 with `@ResponseStatus`, `{{controllerSecurity}}`, nothing but the service call. A
shared root's writes (and its personal-data reads) carry the role Step 1 decided. A user-level resource needs no
`@PreAuthorize` (every authenticated principal is a user); the dropdown carries
`{{dropdownSecurity}}` explicitly because it is often shared by screens of different roles.
Role checks are meta-annotations such as `@AdminOnly` (REST-25, golden `MetadataController`).

### 4.11 Dropdown (when the entity carries `{{dropdownAnnotation}}`)
Read `references/dropdown.md`. Golden: `TodoListRepositoryImpl.findDropdownItems`,
`TodoListService.dropdown`, `TodoListController.dropdown`. The label is a text key the cursor carries:
size the cursor limit for it (4.2).

---

## Step 5: Media (only when the records carry files and the project has the `media` piece)
Read `references/media.md`: a new `UploadPurpose` with its policy seed row, the link entity and
its changeset, the `<x>Keys` / `add<X>Keys` / `remove<X>Ids` fields, the claim and release in the
owner's own write, and the files in the response. Rules: house-review `media.md` (MEDIA-xx).
Golden: the todo attachments (`TodoAttachment`, `TodoService`). Without the piece, offer
`/crud-gen bootstrap` (never hand-write an upload flow).

## Step 6: Integration tests (always)
Read `references/tests.md` and follow the golden test classes. With `{{tenancy}}` not `none`, also
generate `EntityNameTenantIsolationIntegrationTest` (golden: the skills repo's
`overlays/tenancy/src/test/java/com/example/app/todo/TodoTenantIsolationIntegrationTest.java`):
another tenant's member holding the role that writes the record (`newUserIn(newTenant())`, or
`newUserIn(newTenant(), "ADMIN")` for an `@AdminOnly` root: row 15b) gets 404 with the code on GET,
PUT, actions and DELETE of the record, which stays unchanged, and that tenant's lists never show it (TEN-13). With
`{{tenancy}}` = `row`, also tests.md row 15c (a native query of the entity, and the catalogue guard run
with the new table).

## Step 7: Build and test
Run `{{buildCommand}}`. Fix every compile error or test failure in the generated code; never report
done on a red build. Then prove one guard can fail (tests.md).

## Step 8: Self-review (house-review)
Run house-review on the generated and changed files: `/house-review uncommitted` before the commit,
or `/house-review commit <sha>` for a commit the run made (a range `<a>..<b>` for several). Either
way the report is written to `reportDir` as `<date>-<entity>.md` (house-review Step 6), so every
entity of a run leaves one. **The goal is zero findings.** A finding in
code that follows the golden files means the reference or this skill is wrong: fix the generated
file, then offer to fix `~/Documents/claude-skills` (reference-app and skill text together, verified
with `scripts/verify.sh`). **A run of several entities** reviews each entity as soon as it is
generated and green (its own files and the shared files it touched), not all of them at the end:
findings in the first shape the next ones. When the review is delegated to a separate reviewer, the
checklist's review item stays open until that reviewer's report is clean.

### Final checklist
- [ ] No `{{…}}` left in the generated files.
- [ ] The aggregate's row in `AggregateArchTest.AGGREGATES` **and** in both profiles' `aggregates`.
- [ ] A shared root: writes carry a role, personal-data reads decided, both in the profiles (Step 1).
- [ ] A convention changed during the run (`controllerSecurity`, what a role means): the class
      javadoc of the services generated earlier, `Role`'s javadoc and the profiles say the new rule
      (grep them). A role that now writes a shared root names it in `Role`'s javadoc (Step 1).
- [ ] A text key a cursor carries: `app.limits.text.cursor` holds it and its key is in
      `LimitsIntegrationTest.CURSOR_TEXT_KEYS` (4.2).
- [ ] Asked, and recorded: each picked parent's dropdown (Step 1), whether a terminal status freezes
      the record (Step 3), a unique code's shape (Step 2).
- [ ] A same-module parent's service changed (4.8): listed among the changed shared files.
- [ ] Row 28: the list's cell matrix in its index test (every order × every filter, every enum value,
      later batches, lookups), each cell's index existing and its read bound asserted (4.1); a range
      filter offered under its own order only; row 18b: the `ids` filter's statement count.
- [ ] A review-profile Conventions row only for a departure from a house rule, with the owner's reason;
      a choice no rule forbids goes in the javadoc or the changeset comment.
- [ ] With the session version: every generated write that changes access bumps it in its transaction
      and has its 401-then-refresh test (4.8, SEC-07).
- [ ] Build green, and one guard proven to fail (Step 7).
- [ ] house-review clean (Step 8).
