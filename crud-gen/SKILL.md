---
name: crud-gen
description: Generate full layered CRUD for a JPA entity in any Spring Boot project, following the house pattern so the result passes house-review with no findings. Generates like the golden module of the house reference application (~/.claude/skills-reference) - API interface with OpenAPI docs, controller, service, mapper, repository with a Criteria projection impl (offset or cursor paging), request/response records, filter, ErrorCodes and messages, configurable limits, and the integration test class, with optional dropdown and media endpoints. Pre-flights the entity and its migration. `/crud-gen bootstrap` copies the shared house infrastructure into a project from the reference application (error contract, wrappers, paging, limits, logging, config, security, test base, ArchUnit). Project specifics come from .claude/crud-profile.md, discovered from the codebase on first use.
argument-hint: "<path-to-Entity.java> [--unattended] | bootstrap"
allowed-tools:
  - Read
  - Write
  - Edit
  - Glob
  - Grep
  - AskUserQuestion
  - Skill(house-review *)
  - Bash(./mvnw *)
  - Bash(${CLAUDE_SKILL_DIR}/../scripts/bootstrap-copy.sh *)
  - Bash(git -C ${CLAUDE_SKILL_DIR}/.. rev-parse *)
  - Bash(git -C ${CLAUDE_SKILL_DIR}/.. diff *)
  - Bash(git -C ${CLAUDE_SKILL_DIR}/.. worktree *)
  - Bash(git status *)
  - Bash(git diff *)
  - Bash(git log *)
  - Bash(git show *)
  - Bash(git ls-files *)
  - Bash(git rev-parse *)
  - Bash(git symbolic-ref *)
  - Bash(git branch --show-current)
  - Bash(git add *)
  - Bash(git commit *)
  - Bash(git rm *)
  - Bash(git init *)
  - Bash(git merge-file *)
  - Bash(ls *)
  - Bash(cat *)
  - Bash(head *)
  - Bash(tail *)
  - Bash(wc *)
  - Bash(grep *)
  - Bash(find *)
  - Bash(diff *)
---

# CRUD Generator

Target: **$ARGUMENTS**

- `bootstrap`: set up the shared house infrastructure once, or, on a project whose crud profile records a
  `skillsRelease`, upgrade it to the current one. Read `~/.claude/skills/crud-gen/references/bootstrap.md`
  and follow it; skip the rest of this file. Bootstrap always runs attended: with `--unattended` it stops at once
  and says so (its answers, from the pieces to each dependency, have no default to assume).
- a path to an entity: generate its CRUD (Steps 0–8 below). When the entity **already has generated
  code** (its service or repository exists), this is **update mode**: read *Updating an entity* first.
- `--unattended`, anywhere in the arguments: the **unattended mode** (below); the rest is the target.

**The skills repository** is `${CLAUDE_SKILL_DIR}/..`: this skill's own directory, which the installer links into
the repository (or a zip copy's folder), so the path resolves through the link. Its scripts run by that path,
exactly as written in this file, which the frontmatter's `allowed-tools` pre-approves; `references/` write it
`<skills>`. Never assume where the repository was cloned.

**Tools.** Every file is written with **Write** or **Edit**, never through Bash (`cat > file <<EOF`, `tee`, `sed -i`,
`python`, `perl`, `cp` over a file): the edit tools show each change and the permission layer sees it. Bash runs only
the build (`./mvnw`), git and the skills repository's `scripts/bootstrap-copy.sh`, plus read-only commands: the
frontmatter pre-approves exactly these, and nothing broad. The build is `./mvnw` as the profile's `buildCommand`
names it; a command with an environment prefix (`JAVA_HOME=… ./mvnw`) asks, since a rule that matched one would
match any command. Bootstrap's local-stack commands (`docker compose`, the first admin's `psql`) and an upgrade's
render from a scratch worktree of an older release ask too: bootstrap always runs attended.

### Unattended mode (`--unattended`)
For a run nobody can answer (a scheduled job, an evaluation harness). Everything else in this file holds.
- **No questions.** Never call AskUserQuestion and never end on a question. Where a step says ask, take the answer
  the request or the profiles give; else the skill's default (a golden value, the step's stated default); else the
  most restrictive answer that still lets the entity work: a shared root's writes and its personal-data reads only to
  `ADMIN`, a terminal status that freezes the record, a delete refused while referenced (409 `<ENTITY>_IN_USE`), no
  `@Public`, a retention with no purge (an open question, Step 1). A breaking change (update mode, rule 3) is never
  assumed: leave it out and report it.
- **Record every assumption** in the crud profile's *Decisions per entity*, marked **assumed**, and list them in the
  final report, so the owner confirms or changes each one later.
- **Profiles:** a missing one is built (Step 0) and written without the confirmation; its summary goes into the
  report instead. An existing one is used as it stands.
- **Step 2:** apply every pre-flight fix (as for any non-interactive run); a changeset the review profile doesn't
  record as unreleased is never completed in place (*Shipped*, Step 2).
- **Step 8:** the self-review runs as `/house-review <scope> --unattended` (report only), and nothing is offered:
  the fixes to the skills it would offer are listed in the report.
- The run ends with the report and the final checklist, on a green build (Step 7).

## The goal: generated code passes house-review

**Generate like the reference, don't invent.** `~/.claude/skills-reference` (the skills repo's
`reference-app/`, linked by the installers) is a real application that builds and passes its tests.
Its `todo` module is the **golden output** of this skill. Every step below names the golden file to
follow; read it and produce the same shape for the target entity. If the link is missing, stop and
tell the user to run the skills installer.

| Golden file (`<ref>` = `~/.claude/skills-reference/src/main/java/com/example/app`) | Shows |
|---|---|
| `<ref>/todo/domain/Todo.java`, `TodoList.java` | Entity: `@Getter @SuperBuilder`, protected constructor, `@Setter` only on free fields, state methods, a generated sort column |
| `<ref>/todo/dto/request/TodoCreateRequest.java`, `TodoUpdateRequest.java`, `TodoStatusRequest.java` | Request records with `@MaxLength("<key>")`, `version` on updates; a collection wrapped read-only in the compact constructor, null-safe (Step 7's static analysis) |
| `<ref>/todo/dto/request/TodoFilter.java`, `TodoListFilter.java` | Cursor filter, offset filter, sort enum, the `ids` filter |
| `<ref>/todo/dto/response/TodoResponse.java`, `TodoSummaryResponse.java` | Detail and list-row records; a collection copied (`List.copyOf`) in the compact constructor |
| `<ref>/todo/mapper/TodoMapper.java` | Hand-written mapper |
| `<ref>/todo/repository/**` | Repository, custom interface, Criteria **projection** impl with keyset or offset paging |
| `<ref>/todo/service/TodoService.java`, `TodoListService.java` | Transactions, ownership, version checks, parent locking |
| `<ref>/todo/controller/api/*Api.java`, `controller/*Controller.java` | OpenAPI interface, thin package-private controller |
| `~/.claude/skills-reference/src/test/java/com/example/app/todo/*IntegrationTest.java` | The tests every entity ships with |
| `<ref>/todo/domain/Tag.java` + `TagService`, `TagFilter`, `TagColor` | A root with an **active flag** (dropdown `activeField`, list filter `status`), a **pattern field**, an offset list |
| `<ref>/todo/domain/TodoTag.java` + `Todo.replaceTags`, `TodoTagsRequest`, `PUT /todos/{id}/tags`, `TodoService.withTags`, `0006-todo-tags.sql` | **Variant N**: a many-to-many between two aggregates |
| `<ref>/todo/domain/TimeEntry.java` + the `*TimeEntry*` methods of `TodoApi` / `TodoController` / `TodoService` | **Variant M** (a member of the `Todo` aggregate), **range filters** (`@CrossField(reportedOn = "<x>To") boolean is<X>RangeOrdered()`), a bounded number, a calendar date (`@NotAfterToday`) |
| `<ref>/todo/domain/Todo.java` (`number`, `requesterId`, `todoList`) + `TodoService` (`create`, `requireRequester`, `getEditableTodoAtVersion`, `toResponse`), `TodoRepository.findWithTodoListById`, `TodoResponse` (`todoList`, `number`, `requesterId`; with tenancy `requester`), `0025-todos-requester.sql`, `0026-todo-numbers.sql`, `<ref>/user/api/Users.java`, `NamedParty.java`, `PermissionHolders.java` (the picker `NamedPartyService`, `0042-users-dropdown.sql`); the tenancy overlay's `TodoRequesterResponse`, the row overlay's `TodoNumberSeed`; tests `TodoReferencesIntegrationTest`, the tenancy overlay's `TodoRequesterTenancyIntegrationTest`, the row overlay's `TodoSeedsBackfillIntegrationTest` | Three shapes added to an existing entity in update mode: a **display number** from a counter (`Counters.next` last in the create; one counter for the application, one per tenant with tenancy; the row-mode seed pair and the create's `createIfAbsent`; the backfill of the rows already there), a **reference to a person** of another module (an id with a foreign key to `users`, with tenancy to the tenant's `members`; checked through `Users` / `Members` whenever a write names someone new (an update only when the id changes), and a party who will act through `PermissionHolders` (active, holding the party's permission: 409 `TODO_REQUESTER_INELIGIBLE`; tests `TodoRequesterPickerIntegrationTest`, `PermissionHoldersIntegrationTest`); shown by name with tenancy, a former member by id alone), a **same-module parent's name** on the detail and every write's response (`todoList {id, name}`, read with the child in one statement where nothing locks the child) and on list rows (`TodoListName`, `TodoService.withTodoLists`: a second statement per batch) |
| `<ref>/planning/**` (`Plan`, `PlanCreateRequest`, `PlanFilter`, `PlanRepositoryImpl`, `PlanService`, `PlanTodoResponse`, `PlanningConstraintErrorCodes`, `0011-plans.sql`) + `<ref>/todo/api/Todos.java`, `TodoView.java` | **A parent in another module** (an id with a database foreign key, reached through the owner's facade; its view mapped into a response record of the plan's own), whose **state the create checks under its lock** (a plan only for an open todo: `Todos.getOwnedTodoViewForUpdate`, the view's `open` boolean, 409 `PLAN_TODO_DONE`; the todo's completion and delete wait for the create), reads that **survive a parent vanished between their two statements** (`findOwnedTodoView`, `withTodos`), a **cursor list filtered by another module's id** (`todoId`) whose rows get the parent's title in one facade lookup per batch, **no overlapping ranges** (an exclusion constraint mapped to 409; overlapping creates for two of the owner's todos may answer 503: the lock queues one todo's writes, not the owner's), a **range in a body** (`isDateRangeOrdered`) of a **configured maximum length** (`getLengthInDays`, `plan.max-days`), an **overlap filter with a lower index bound** (`plannedFrom`/`plannedTo`: `ends_on >= from`, `starts_on <= to` and the derived `starts_on >= from - (max - 1)`), planned dates (`@EarliestDate` and `@LatestDate` only), an immutable resource (no PUT or PATCH: 405) |
| `<ref>/todo/service/TagMergeManager.java` + `TagService.lockForMerge` / `finishMerge`, `TodoService.moveTagLinks`, `TagRepository.findForKeyUpdateByIdAndOwnerId`, `TodoRepository.lockAllCarryingTag`, `TodoTagRepository.addTargetWhereSourceIs`, `POST /tags/{id}/merge`; test `TagMergeIntegrationTest` | **A Manager** writing two aggregates in one transaction (`MANDATORY` halves, one lock order, a key lock, bulk statements, both rolled back on a failure) |
| `<ref>/todo/service/TodoService.java` (`findById`, `lockEditableTodo`) + `TodoPolicy` (`read`, `edit`, `editScope`, `listScope`), `TodoAllowedActionsResponse` (an `AllowedAction` per action, `common/security`), `TodoApi.NOT_YOURS`; tests `TodoPolicyTest`, `TodoRequesterIntegrationTest` | **A record two parties see with different rights**: the owner does everything, the requester reads (403 `TODO_NOT_YOURS` on the rest), anyone else 404 |
| `<ref>/user/service/RefreshTokenPurgeJob.java` + `TokenService.deleteExpiredBefore`, `RefreshTokenRepository.DELETE_EXPIRED`, `~/.claude/skills-reference/src/main/resources/config/jobs.yml` (`refresh-token-purge`); tests `RefreshTokenPurgeJobIntegrationTest`, `RefreshTokenIndexIntegrationTest` | **A purge job** for rows that accumulate (4.12): by the data's end of life, in batches of short transactions, its index, its settings |

The rules behind the shape are the house-review references: `rest-api.md`, `error-contract.md`,
`persistence.md`, `java.md`, `code-smells.md`, `lenses.md` (TEST-xx), `architecture.md`,
`config-baseline.md`. **Precedence, stated once for every file of this skill:** the house-review rules win
over `references/tests.md`, which wins over the golden code (the rules are what the review checks). On a
conflict, generate what the higher one says, report the conflict (file and line of both sides) and
**offer** a fix of the skills repo; never change or push the skills during a project run. Step 8 runs
house-review on the generated files, and it must come back clean.

Everything that differs between projects is a **profile key**, written `{{key}}`.

---

## Updating an entity (update mode)

The developer changed an entity that already has generated code (a field, a relation, a rule). The
steps are the same; these rules come first:
1. **Diff first.** `git diff` on the entity tells what changed; generate only what the change
   touches (requests, response, mapper, repository predicates, service, API docs, changeset,
   tests). Leave everything else byte-for-byte as it is, even if you would write it differently today
   (offer that as a separate refactoring).
2. **A new changeset, never an edit** of a shipped one (Step 2, *Shipped*); existing rows need a default or a backfill
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
5. **Guard mirrors** (Step 7): each guard test of the *new* behaviour names the golden test it mirrors.

## Step 0: Load the project profile

Look for `.claude/crud-profile.md` at the project root. If it exists, read it: its values and
*Deviations* take precedence. Also read `.claude/review-profile.md` if it exists; its conventions
apply to generated code too. When this skill says "record it in the profiles", a review-profile
**Conventions** row is only for a departure from a house rule, with the owner's reason (it reads as an
override); a choice no rule forbids is recorded in the crud profile's *Decisions per entity* (a section per
entity), never in the code's comments (SMELL-19: the code carries a decision through its names, its
permissions and its tests; a comment carries only a why the code can't say).

If it doesn't exist, build it before generating anything:
1. Copy `~/.claude/skills/crud-gen/profile-template.md`.
2. Fill each key from the codebase; don't guess.
3. **If the house infrastructure is missing** (no ErrorCode/AppException contract, no
   `PaginationWrapper`/`CursorWrapper`, no `Limits`/`@MaxLength`, no `@CrossField` for a rule across
   fields, no test base), stop and offer `/crud-gen bootstrap`: generated code depends on it. A project
   that declines takes the missing piece alone from the reference (`common/validation/CrossField`, and the handler's
   `reportedOn` lookup it relies on), never a hand-written variant.
4. Keys with no equivalent: `none` (see *Fallbacks* in the template).
5. Write the profile, show a short summary, and get confirmation **once**.

**Read every golden file as the project's mode sees it:** `${CLAUDE_SKILL_DIR}/../scripts/bootstrap-copy.sh
--render <{{tenancy}}> <golden file>` prints it with the mode's overlay version and its piece markers
applied, so no marker line or commented-out block reaches the project.

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
read**, and record both in the crud profile's *Decisions per entity*. **Without tenancy**, while signup is public
(the `sign-in` piece), every internet account is a USER: refuse a shared root whose writes USER may make; they
carry a permission only the writing roles hold (*Permissions* below). **With
tenancy** the public signup reaches no tenant data: a tenant token needs a membership that an ADMIN of
that tenant granted (an account without one can't sign in, `TenantPlanesIntegrationTest
.accountWithoutMembershipIsRefused`), so "every member writes the tenant's customers" is a sound owner
decision: record it with that reason; which roles hold the writes is then the owner's call, not a rule. A field that names a
person (a name, an email, a phone) on a shared root asks the same for reads: list, get, filters and
the dropdown's `for` need a permission the writers' roles hold unless the owner decides that every account may
search those people (record it). What a role may do is said once, by its grants (each `Role`'s
permission lines; the permission names say the resource and the action, `BOOK_WRITE`), with *Decisions per
entity* recording which roles get each; neither `Role` nor the service repeats it in prose.

**A shared root that other records will reference** asks two more questions before its first child
exists, while the answer is cheapest:
- **retired or deleted?** A catalogue entry (a course, a product) that children reference is either
  retired with an active flag (it leaves the dropdown, `activeField`, and old children keep pointing at
  it: the golden `Tag` shape, REST-13) or hard-deleted, and then refused while referenced (409
  `<ENTITY>_IN_USE`, DATA-34). Record the choice in the crud profile's *Decisions per entity*;
- **how is a person erased?** A root with personal fields (name, email, photo) that children will
  reference can no longer simply be deleted (DATA-07 says personal data is truly deletable; DATA-33's
  `*_IN_USE` refuses it): ask the erasure path (delete the children first, anonymise the person's
  fields, or cascade) and record it. Golden permission checks: `UploadPolicyController` (`@SignedIn` read,
`@Requires(Permission.UPLOAD_POLICY_MANAGE)` write) and `MetadataController` (`@Requires(Permission.METADATA_MANAGE)`),
with their 403 tests (`UploadPolicyIntegrationTest`, `MetadataIntegrationTest.onlyAdminsReachTheApi`) beside the generated
one (`EndpointContractIntegrationTest.everyRequiredPermissionIsChecked`). A shared root has no `ownerId`: `findById`,
no owner parameter, unscoped lists (tests.md *Shared tables*).

**Permissions** (house-review AUTHZ-01, AUTHZ-02; **ask, never guess**: they are business rules): ask the developer
(1) **which actions** the entity's handlers split into (the default offer: `READ`, `CREATE`, `EDIT`, `DELETE`; an
action of its own such as `APPROVE` when a state change is one; several handlers may share one, as a todo's
update, status, tags and time entries share `TODO_EDIT_OWN`); (2) **the reach** of each: `_OWN` (the caller's own
records: an owned entity), `_ASSIGNED` (records the module says are theirs to work: ask what assigns them), none
(every record: a shared root). **A detail several reaches read** (the requester, their manager, HR) gets each
reach's READ permission (`<ENTITY>_READ_OWN`, `_READ_ASSIGNED`, `_READ`), its handler `@Requires` all of them (any
one lets in) and its policy picks the reach; one reach's READ is never made the others' prerequisite so that one
permission could guard it (AUTHZ-03). Its lists are one per reach (REST-08: the bare collection `ALL`, `/mine`
`OWN`, `/team` or `/assigned` `ASSIGNED`), each `@Requires` that reach's READ and uses the policy's scope for it
(AUTHZ-05). **A state its owner releases** (a draft before its submit): ask, per other reach, from which state it
sees the record (an approver from the submit on? finance?); unattended, from the release on (a draft is the owner's
alone), recorded **assumed**. That reach's list scope and the policy's `read` carry the one condition (`status <>
DRAFT` in both, AUTHZ-05), and tests.md row 14c proves it; a runbook line naming what that reach reads (a new role's
*Operator tasks* line) says the recorded answer itself, "every claim past its draft" (the decided ones included),
never a narrower "every submitted claim" (OPS-07); (3) **which roles get each**, role by role (roles
don't include each other, so a permission two roles hold is on both lists; a role the developer names that doesn't exist yet is a new built-in
role: references/bootstrap.md, *A new built-in role*); (4) **its menu `Area`** (an existing one, or a new constant: ask its
name); (5) **the prerequisites** (an edit needs the read that opens the record: offer `EDIT`/`DELETE`/`CREATE` →
`READ`, and let the developer change it). Record the answers in *Decisions per entity*. The generation then adds
the constants to `{{Permission}}` (`<ENTITY>_<ACTION>[_OWN|_ASSIGNED]`, its area, reach and prerequisites: golden
`Permission`'s `TODO_*`) and each role's lines to `{{Role}}` (golden `Role.USER`, `Role.ADMIN`), in the same commit;
`RoleTest` fails a role missing a prerequisite, `PermissionTest` a reach that disagrees with its name or an unused
area. **With `authorization: roles-piece`** (house-review AUTHZ-12) the `Role` lines are the built-in roles' and
the tables grant: the same commit adds a **grant changeset** of the next free number that gives each new permission
to the built-in roles the developer named (ask which: never guess; `INSERT INTO role_permissions (id, role_id,
permission, created_at, updated_at, version) SELECT uuidv7(), r.id, p.permission, now(), now(), 0 FROM roles r CROSS
JOIN unnest(ARRAY['<ENTITY>_READ', ...]) AS p (permission) WHERE r.built_in = 'ADMIN';`, with its `--rollback`
deleting those rows), so an existing installation's copies get it on upgrade. `ADMIN` is always among those roles,
said rather than asked: safety rules 1 and 3 let an admin give a role, or change a person, only within what they
hold, so an `ADMIN` without the new permission could give no role that holds it (AUTHZ-12; `RoleTest`'s roles block); `BuiltInRolesIntegrationTest` fails a
`Role` line without its changeset. With a tenancy piece every tenant has its copies: row mode's changeset names each
row's tenant (`SELECT uuidv7(), r.tenant_id, r.id, ...`) and lifts `FORCE` on `role_permissions` and `roles` for its own
transaction, its rollback too (TEN-34); schema per tenant's is a tenant changeset (it runs in every schema); a new
tenant gets the permission from the enum (row mode) or the changeset (schema per tenant) at its provisioning. Roles an
admin built stay as they are: the admin gives the new permission to them. **An owned entity** (a reach other than none, or a second party such as a requester) gets a
`<Entity>Policy` in its service package (golden `TodoPolicy`, `PlanPolicy`, tested by a table like `TodoPolicyTest`): a `Decision`
per rule (an action's own; `read` and `edit` when anyone besides the owner sees the record) and the rules Step 1 and
Step 3 found (a second party's rights, a terminal status that freezes the record, an amount and the module setting
that bounds it; never a role's column). **"Own" is defined once, in one of two shapes, never mixed** (AUTHZ-05):
**the owner alone** (the `_OWN` reach, no second party, no other reach; golden `PlanService`, `PlanPolicy`) puts the
owner's id in every query, the list's Criteria query taking `ownerId` and the detail and each write loading
`findByIdAndOwnerId` (`findForUpdateByIdAndOwnerId` when locked), its policy holding the state's rules only, no
`listScope` or `read`; **anyone else too** (a second party, another reach; golden `TodoService`, `TodoPolicy`) lets
the policy say who sees what: the lists add its `listScope(callerId)` (a `PredicateSpecification` the Criteria list
adds; one per reach), the detail and each write load by id and ask `policy.read` / `policy.edit` (the not-found
refusal for a stranger), a locking load takes its `editScope`. A `listScope` beside a `findByIdAndOwnerId` load is
"own" defined twice. A response carries
`allowedActions` for every action whose answer can differ between records the caller sees, state rules included
(AUTHZ-07; golden `TodoAllowedActionsResponse`, `PlanAllowedActionsResponse`): each an `AllowedAction` (allowed and
every reason, a reason the code and its existing message) made by `AllowedActionMapper.toAllowedAction(name,
<the permission the action's endpoint @Requires>, policy.x(...))` from the same policy method the write calls (a
caller holding none of that permission gets `allowed: false` with `ACCESS_DENIED` alone, the call's own 403:
AUTHZ-07; the permission written there is proven equal to the endpoint's by an `AllowedActionContract` row per flag,
references/tests.md row 28l; the flag and its write read the caller's permission from one source, the request's,
`@Requires` and `CurrentUser.holds`: a write never re-reads the caller's own permission from the database, as
`PermissionHolders.holds` checks the account a write names and never the caller, or the flag says allowed where the
write refuses; "a stale party can't act" is that request check, made immediate by the session version where the
project has it), on the detail and on the list's rows from the row's
own fields (a parent's state from the page's one facade lookup, golden `PlanService.withTodos`); a rule with several
preconditions lists them all with plain `if`s into a `List<Decision.Refusal>` (golden `PlanPolicy.reschedule`), a
reach refusal alone. Actions the permission alone decides, creates, and a delete only a foreign key refuses get no
flag. Tests: the policy's table with each refusal's arguments (`PlanPolicyTest`), and the shape on the detail and a
list row, a reason's message equal to the write's `detail` (`PlanAllowedActionsIntegrationTest`), and the flags'
contract (row 28l).
**Options a rule can refuse, and free days.** For a write whose picker a domain rule can refuse an option of right
now (a done todo for a plan, a booked room, an assigned driver), ask whether its screen shows the refused options
disabled with their reasons (a write's selectable options, `GET /<write's resources>/<resource>-options`,
`SelectableDropdownItem`, golden `PlanService.todoOptions`; references/dropdown.md) or leaves them out (a plain filter
on the shared dropdown); never assume either. For a time-bound resource whose overlap an exclusion constraint guards
(DATA-35), ask whether its screen shows free and taken days: an availability read (`GET /<resources>/{id}/availability
?from&to`, or the caller's own, golden `PlanService.availability`), its span a configured limit (`plan.max-availability-days`).
Both are reads: the write keeps its own checks and its constraint.

**Module** (if `{{modules}}` is set): every generated class goes under `{{appPackage}}` with
`{module}` substituted; cross-module access follows `{{moduleContracts}}` (architecture.md
ARCH-04/05/06): a parent in another module is referenced by id (a UUID column with a database
foreign key), never by an entity relation, in `api-facades` mode, and is validated through that
module's facade (golden `todo.api.Todos` + `TodoView`, used by `PlanService`). The facade's lookups,
each scoped like the parent's own reads:
- **`get<Parent>View(id)`, not locked**, for a child write that checks nothing of the parent: the
  foreign key refuses a child written after the parent's delete, 404 `<PARENT>_DELETED_MEANWHILE` (DATA-29). The
  same shape as the next one without the lock and `MANDATORY` (same-module golden: `TodoService.createTimeEntry`).
- **`get<Parent>ViewForUpdate(id)`, locked**, `@Transactional(propagation = MANDATORY)` over the
  parent's `findForUpdateBy…`, for a child write that **checks a mutable column of the parent** (lend
  only an AVAILABLE book; golden: a plan only for an open todo, `PlanService.create`, 409
  `PLAN_TODO_DONE`). The lock names that rule (DATA-29: no constraint can check another table's column).
  **Only the writes that check the state take it**: a create, and an update that moves the child to
  another parent (it checks the new one). A write that checks nothing of the parent doesn't, even one
  that can trip an exclusion constraint scoped by that parent (a loan's new dates): the constraint
  holds that rule, and its rare simultaneous overlap answers DATA-35's 503 (the write's 503 `@ApiResponse` names it). The parent's
  state change and delete then wait for a locked write: the delete answers 409 `<PARENT>_IN_USE`, and
  `<PARENT>_DELETED_MEANWHILE` is unreachable on that write (keep its mapping, don't document it there); an
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
"the list's `query` is the picker" is recorded in the crud profile's *Decisions per entity* (Conventions
are for departures from a house rule). Never leave it unasked:
the picker is the first screen that needs it. Whichever it is, check that the picker can be used by
**every role that writes the child** and searches the **label the screen shows**: a list behind a
permission the writers don't hold (the tenancy piece's `GET /tenant-members`, `TENANT_MEMBERS_MANAGE`, which
searches emails) is no picker for a rota every member writes. A dropdown is shared by every screen that picks
the resource: the screen names the write in `for` (the child's `<CHILD>_CREATE[_OWN]`), and the dropdown lists that
permission's reach (AUTHZ-08, references/dropdown.md). For a person reference without tenancy, the picker is the
user module's `GET /users/dropdown?for=<the write's permission>` over the write's `user.api.NamedParty` constant (4.3,
golden `NamedPartyService`). For a person reference with tenancy, a `Members`-backed members dropdown readable
by every member is the fitting picker. It is a use case, not in the tenancy piece: a project that needs
one generates it with this skill as a dropdown of the user module (`GET /tenant-members/dropdown`, label
the full name, `query` on it, over `Members`, references/dropdown.md) and reviews it with house-review
like any other code; until then it records the open item (or the owner accepts typing the person's id,
recorded).

A module without a facade gets one in the same change: an interface in `<module>.api` that its service
implements, with its view record (golden: the sign-in piece's `user.api.Users`, without tenancy). **With tenancy, other modules ask
`Members`** (the tenancy overlay's `user.api.Members` + `MemberView`, implemented by
`MembershipService`): `isMember(userId)`, `getMemberView(userId)` and `getMemberViews(userIds)` (one
statement, absent ids simply absent), each scoped by `TenantContext.current()`, answering
`TENANT_MEMBER_NOT_FOUND` (no second "member not found" code; golden `MembersIntegrationTest`). Never the
membership or user repositories: a reference to a person is validated there before the write. A
project that needs more about a member (a role rule) adds a method there, never a domain type in the
view. **The view never goes on the
wire:** the consuming module maps it into a response record of its own (golden `PlanTodoResponse`,
`TodoAttachmentResponse`; ARCH-06, enforced by `ModuleBoundaryArchTest`).

**Ask, don't guess.** Ask the developer once per entity, in one batch with the questions above, unless the
request or the profile already says. Record each answer in the crud profile's *Decisions per entity*:
- **text and collection limits**: each String field's maximum length (4.2's `@MaxLength` number); each
  collection's maximum size and whether it may be empty (4.2);
- **date bounds**: each date's earliest and latest day (4.1's `@EarliestDate` / `@LatestDate` values);
- **filter indexes**: per offered filter, whether it gets its own index (4.1: the developer's call, from the
  table's size and the filter's selectivity; unattended, the business rules' answer, else none);
- **dropdown**: per dropdown, whether it is searchable (`searchable`, the `query` parameter), its label,
  and whether it is cached (`cacheable`); per write whose options a rule can refuse now, selectable options or a
  plain filter (references/dropdown.md); per time-bound resource, whether an availability read is wanted and its
  longest range;
- **parent by name**: whether the detail and the list rows show each referenced parent or person by name
  (4.3; without tenancy a person stays an id), or by id only;
- **named parties**: per person a write names who will act (an approver, an assignee), "who may be named as
  <party>?", default **an active account holding <ACTING_PERMISSION>** (the permission that party acts with), and
  what happens when they lose it (default: the owner reassigns while the record is not final; 4.3); **"may the
  owner name themselves as <party>?"**, default **no** for a party who decides on the owner's record (approves,
  rejects, reviews, signs off: four eyes), **yes** for one who only reads or is served (golden `TODO_REQUESTER` is a
  no by the owner's decision: a todo one asked oneself for is one nobody asked for); the answer is the constant's
  `Owner` (4.3). **A party whose
  action checks the owner** (approves, rejects, signs off): without tenancy, while signup is public, its permission
  is never held by the role signup grants (`USER`), or anyone signs up a second account, names it and approves
  their own record (SEC-01); it goes to a role an operator gives (`APPROVER`, `MANAGER`), and a request giving it to
  `USER` is refused. A party who only reads or is served (golden `TODO_REQUESTER`) needs no such role. The picker shows
  the holders' names to every caller holding the write's permission: a personal-data read, recorded as the owner's
  decision (SEC-04);
- **upload limits**: per upload purpose, the file sizes, file counts and content types (Step 5);
- **overlap race**: with an exclusion constraint, whether DATA-35's rare 503 is accepted (the default) or
  the endpoint's traffic makes it normal, so the scope is serialised;
- **retention** (house-review OPS-03): are the rows **kept** (a business record: an order, a todo, a person) or
  do they **accumulate** (a log, an event, a token or code, an attempt, a delivery, finished work: rows nobody edits
  again, added by every request or job of a kind)? For accumulating rows: **after how long** they are deleted, and
  **from which column** (`expires_at`, `finished_at`, `created_at`): the data's end of life, never a status change
  that still matters (a rotated token stays until it expires). The period is the developer's; an expiry column's
  own lifetime is the only default (the golden token's day after expiry). Accumulating rows kept by the owner's
  decision go in the review profile's `retentionExceptions` with the reason; otherwise 4.12 generates the purge.
A golden value (`plan.dates`, the golden upload seed's limits) is an example, never an answer. When
nobody can be asked (a non-interactive run), take the golden value, record it as **assumed**, and list it
in the report; a retention period has no golden value: generate no purge, and list the entity's retention as an
open question (OPS-03) in the report.

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
| An **email** field: stored as `Emails.normalize` gives it (the first shape, golden `users`: `ck_<table>_email_normalized`, plain unique); the request shape is 4.1's *Email* | DATA-05 |
| A value the mapper normalises (an email lower-cased and trimmed) has a `CHECK` with the same expression (`email = lower(trim(email))`, golden `users`); trimming alone (a name the mapper `strip()`s) needs none, as its unique index's `lower(trim(…))` already covers it (golden `TodoList`, `Tag`) | DATA-05 |
| A unique rule on an entity with a terminal status (WITHDRAWN, CANCELLED): **ask** whether it spans every row or only live ones (re-enrolling after a withdrawal); live only is a partial unique index `WHERE status = '<LIVE>'` with a test that the terminal row doesn't block a new one. Its "taken on update" test (tests.md row 11) means the writes that move a row **into** the live status or change the pair; when none does (the pair fixed at the create, no move back to live), the create's test is the whole duty: say so. When the entity also has a **date range**, ask the follow-up: "(a digits-only identifier, an ISBN, needs no `COLLATE "C"`: digits sort the same in every collation; a code with letters keeps it) does a finished record (past its `endsOn`, still `<LIVE>`) still block a new one?" (a student who completed a course retaking it). If not, the rule is either no overlapping live ranges, DATA-35's exclusion constraint `WHERE (status = '<LIVE>')`, or a decided terminal status (`COMPLETED`, set by a person or a job), never "live" read from the dates (DATA-37) | DATA-05, DATA-35, DATA-37 |
| Indexes: every FK; keyset sort keys non-null (a generated column for a nullable one). **While the entity's changeset is unreleased** (*Shipped*, below), ask the list's filters and orders now (Step 3's list kind, 4.1's filters, sorts and dropdown), and per offered filter **"index this filter?"** (4.1: the developer's call), so their indexes (4.1) go into that changeset, not into a second one that repeats its FK indexes | DATA-10 |
| A child of an owned parent: the composite owner FK, `moveTo` or `updatable = false` on the parent column; a `moveTo` brings its exclude entry (Step 7) | DATA-33, CFG-11 |
| Accumulating rows (Step 1's *retention*): the column the purge reads is `NOT NULL`, or the purge's index is partial on its set rows (`WHERE finished_at IS NOT NULL`: running work is never purged); its index goes into the entity's changeset (4.12). A table that references it by a foreign key: ask whether its rows go first or keep the parent; no `ON DELETE CASCADE` added for the purge without the changeset saying so | OPS-03, DATA-10 |
| `Instant` for moments (UTC), `LocalDate` for a date a person picks, stored as sent; the one zone `BusinessCalendar`'s, for a rule a date decides | JAVA-25 |
| A display number comes from the metadata piece's gapless `Counters` (golden `Todo.number`: a fixed counter seeded by its changeset, after the highest number of the rows already there; with tenancy one per tenant, the TEN-18 row below), or without that piece from a sequence; a fixture inserting rows splices in a number (tests.md) | DATA-14 |

Golden examples: `~/.claude/skills-reference/src/main/resources/db/changelog/changes/000{2,3,4,5}-*.sql`.
**Shipped** has one record for both skills: the review profile's `shippedRelease`, the release in production as
the upgrade path names it (`<pieces>,<project>`: the highest changeset number of each range it holds, the pieces'
below 0100 and the project's from 0100; tests.md, *Upgrading a used database*). house-review's DATA-13 reads the
same value.
- **A changeset above it in its range is unreleased:** this skill may complete it in place (the entity's own
  changeset, the indexes the DATA-10 row above puts into it). `none` means the project has never shipped: every
  changeset is unreleased.
- **At or below it, shipped:** never edited (DATA-13). The fix is a new changeset; refuse an in-place edit, even
  when asked, and say why.
- **No record** (no review profile, or one without the key): every changeset already committed counts as shipped.
  Ask the owner for the value and record it in the review profile; unattended, write a new changeset and list
  the question in the report.

A database that ran an unreleased changeset (the local stack, a staging database that deploys unreleased work)
refuses its changed checksum at its next start: the report names the completed changeset and says to recreate that
database or clear its checksums (Liquibase `clear-checksums`). Changesets use `--changeset {{changesetAuthor}}:NNNN-…` (the golden files say `dev`: a placeholder).
**Numbers:** the house pieces own 0001-0099; a project's own changesets are numbered from **0100** (the
next one after the project's highest at or above 0100, never after a piece's number, DATA-13), so a piece's
later changeset never shares a number with the project's.

List **every** failure, of every entity in the run, in **one table** (check, what is wrong, the fix:
the entity change and a **new** changeset, or the unreleased one completed in place; never edit a shipped one), then
ask **once** with **AskUserQuestion**: apply all, or pick. When nobody can be asked (a non-interactive run,
`--unattended`), apply all and say so in the report. A declined fix: generate anyway and list it as an open item.

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
template), never `perContext` with the tenant id. A tenant still PENDING when the changeset ships has no
schema yet; its approval creates the schema from the template or runs every tenant changeset, the seed
included, so it numbers from #1 with nothing added: test it (a tenant registered before the upgrade,
approved after it, numbers its first record #1; golden schema overlay
`UpgradeFromPreviousReleaseIntegrationTest`).

**With `{{tenancy}}` = `row`** (house-review `tenancy.md`, TEN-16…19 and TEN-30…38; golden: the `tenancy-row` blocks of
the reference's `0002`–`0006` changesets and its todo entities, which bootstrap uncommented in the
project's copies, and the row overlay's `0017`–`0019` for the keys added since), add these pre-flight rows for every tenant table (a platform table is one the user
names as such; it joins `RowSecurityCatalogIntegrationTest.PLATFORM_TABLES` with its reason):

| Check | Rule |
|---|---|
| The entity extends `TenantOwnedEntity` (`@TenantId`), never `{{AuditBase}}` directly; no `tenantId` in its builder calls, requests, filters, repositories or services | TEN-32, TEN-01 |
| `tenant_id uuid NOT NULL` with **no default** and `fk_<table>_tenant_id → tenants (id)`: every insert names its tenant, so a forgotten one fails at once. The golden `0002`–`0011` blocks still show a `current_setting` default; it predates the rule and the row overlay's `0016` drops it (a shipped changeset is never edited): never copy it into a new changeset | TEN-30 |
| `ENABLE` + `FORCE ROW LEVEL SECURITY` and the one `tenant_isolation` policy (`USING` and `WITH CHECK`, the same predicate) **in the changeset that creates the table** | TEN-30 |
| Every unique constraint, exclusion constraint (`tenant_id WITH =`) and list index has `tenant_id` among its leading equality columns: the owner (or a parent column) first when the table has one (`(owner_id, tenant_id, sort…, id)`, `(customer_id, tenant_id, id)`: it also serves the key's check), `tenant_id` first when it has none or a list filters by the tenant alone (`(tenant_id, sort…, id)`). Names: with an owner as in single-tenant (`idx_todos_owner_id_due_sort_id`: `tenant_id` after the owner is left out of the name, as the golden row blocks do); without one `tenant_id` is the scope and is named (`idx_customers_tenant_id_name_id`). A scope column that determines the tenant (an owner, a member) gets `CREATE STATISTICS st_<table>_<column>_tenant_id (dependencies) ON <column>, tenant_id` in the same changeset (golden row overlay `0023-owner-tenant-statistics.sql`, checked by `OwnerTenantStatisticsIntegrationTest`): without it the planner estimates a person's rows at one once other tenants fill the table | TEN-33, DATA-10 |
| A reference to another tenant table: a composite key `(parent_id[, owner_id], tenant_id) → parent (id[, owner_id], tenant_id)`, the parent's unique key with `tenant_id` last, added **in the child's changeset** (the parent's is usually shipped by then; the key exists for the child). A reference to a person (an owner, an assignee) is to the tenant's own members, never to `users` or `tenant_memberships` (platform tables): `(owner_id, tenant_id) → members (user_id, tenant_id)` as `fk_<table>_<column>` (golden: the row overlay's `0018-owners-reference-members`); a person the request names (an assignee) is checked through `Members` (Step 1) before the write, since the key also accepts a member who left. A member's row is never deleted (a removal marks it left), so the key never blocks a removal and the record keeps its owner; a project whose records must be reassigned before a removal says so (an open item: the members API has no such check) | TEN-33, TEN-16, TEN-17 |
| The replica identity: `ALTER TABLE <t> REPLICA IDENTITY USING INDEX <uq_…>` **in the changeset that creates the table**, on a unique key that includes `tenant_id` (the parent key `(id, …, tenant_id)` of its composite references, or `(tenant_id, key)`); a table without one gets `uq_<table>_id_tenant_id (id, tenant_id)` | TEN-38 |
| A display number (an invoice or order number) is a per-tenant counter (the metadata piece's `Counters`), never an identity column or a sequence: one sequence serves every tenant. `counters` is tenant data, so it is a **fixed** name (`TICKET_NUMBER`), not `perContext` with the tenant id; it needs the TEN-34 seed pair (a listener with `Counters.createIfAbsent(name, 0)` plus a backfill changeset for every tenant whatever its status, after its highest number), and the create calls `createIfAbsent` before `next` too (a tenant activated by an instance without the listener, during a rolling deploy, still numbers from 1). A `uq_<table>_number (tenant_id, number)` backs it up. Golden: `Todo.number`, `0026-todo-numbers.sql`'s row block, `TodoNumberSeed`. Tests: rows 27b–27d, 30 | TEN-18, TEN-34 |
| A shipped changeset (*Shipped* above) gets no new comment (its checksum changes): a later note (a TEN-36 search warning) goes in the runbook | DATA-13 |
| No seed rows in the entity's changeset: a new tenant's starting rows come from a `TenantProvisioned` listener in the module (golden `TodoAttachmentPolicySeed`, `TodoListDefaultsSeed`), the existing tenants' from a backfill changeset that lifts `FORCE` for its own transaction (golden `0010`'s and `0014`'s `…-existing-tenants`), its `--rollback` lines too: `NO FORCE`, the `DELETE` of the rows it added, `FORCE` (the rollback runs as the owner with no tenant set; `BackfillRollbacksIntegrationTest` checks it). The backfill also reaches a tenant still PENDING, so **the listener creates its row only when missing** (`Uploads.definePolicyIfAbsent`, `Metadata.createIfAbsent`, `Counters.createIfAbsent`): a plain insert fails the approval on the unique key, on every retry, and the tenant can never be activated. Tests (golden `TodoSeedsBackfillIntegrationTest`): the backfill for a tenant without the row, and a PENDING tenant approved after the backfill (ACTIVE, one row, its owner signs in) | TEN-34 |
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
  methods for the rest (a list reads its batch, and so its cursor, before the root's owner check: bad input
  is 400 before any lookup, the batch holding the owner's rows only; a write checks the owner of the root
  first; the root read without a lock on create: its
  foreign key refuses an entry after the root's delete, 404 `<ROOT>_DELETED_MEANWHILE`; the root's version
  untouched).
- **Variant N: many-to-many between aggregates** (golden `TodoTag`, `Todo.replaceTags`,
  `TodoTagsRequest`, `PUT /todos/{id}/tags`, `TodoService.withTags`, `0006-todo-tags.sql`). Ask:
  which aggregate **writes** the link (the link becomes a member `<A><B>` of that root, ARCH-15)?
  When B is deleted, do links simply disappear (`ON DELETE CASCADE` on the B side) or is it 409
  `<B>_IN_USE`? Two facts the golden code relies on:
  - A's mapped-by link set carries `@OptimisticLock(excluded = false)`, or A's version never moves
    when the set changes (DATA-30);
  - B's rows are read through B's service without a lock (`getOwned<B>s`): a link written after B's
    delete is refused by its foreign key, mapped on the write side to 404 `<B>_DELETED_MEANWHILE` (DATA-29,
    DATA-33).

  Schema (composite owner FKs on both sides, DATA-33), API (`PUT /<as>/{id}/<bs>`, A's own PUT
  refuses `<b>Ids`) and the list (one grouped query for a batch's links) as in the golden files.
- **Versioned document** (DATA-36, a use case): not generated. Generate the plain entity and say so.
  A status the clock changes (`EXPIRED`, `CURRENT`) is never generated as a stored enum (DATA-37).
- **Variant A: list CRUD** (no FK with `unique = true`). **Variant B: one per parent** (a unique
  FK): a nested singleton resource; GET returns a null object when absent, PUT creates or
  replaces.
- **List kind** (`{{cursorLists}}` / `{{offsetLists}}`, entity names, or the developer's request; else ask
  **who consumes the list**): REST-08's rule for that consumer, never a guess at the table's size.
  - **cursor** (the default): `CursorFilter`, `CursorWrapper`, `pageInfo {nextCursor, hasMore}`, no count.
    Golden: `TodoFilter`, `TodoRepositoryImpl`;
  - **offset**: `PageFilter`, `PaginationWrapper`, the count only on a full page. Golden: `TodoListFilter`,
    `TodoListRepositoryImpl.findByFilter`.

  The consumer and the kind go in **both** profiles (the crud profile's *Decisions per entity*, and
  `cursorLists` / `offsetLists` in each: the review profile's lists are REST-08's source).

- **A feature that needs configuration, not an entity** (rules an admin chooses per tenant or per
  context: META-01): with the `metadata` piece, generate no entity. Rules:
  house-review `metadata.md` (META-01…09). Golden: `metadata/` (the fixed key
  `TODO_LIST_DEFAULTS`; the per-context key `MetadataKeys.todoListSettings(listId)`; the record
  `TodoListSettings`) and `MetadataIntegrationTest`, `CounterIntegrationTest`. A per-context value
  is an endpoint of the module that owns the context (`GET/PUT /todo-lists/{id}/settings`, with
  `version`), generated here with its tests (tests.md).

**A use case writing two aggregates** (a `<UseCase>Manager`, ARCH-15's shape) copies golden
`TagMergeManager` (`POST /tags/{id}/merge`, `TagMergeRequest`; its `MANDATORY` halves
`TagService.lockForMerge` and `finishMerge`, `TodoService.moveTagLinks`; `TagMergeIntegrationTest`): one
documented lock order (two rows of one table by id; a row whose new references must wait for the use case
takes the key lock, a native `FOR UPDATE`, since JPA's write lock is `FOR NO KEY UPDATE`), the many rows of
the other aggregate locked in id order and changed by bulk statements whose count doesn't grow with them.
Tests: each refusal changing nothing, a failure after the first half rolling both back, the use case racing
itself in every direction (no 503), a write racing it, the statement count. **A record two parties see
with different rights** (REST-16) copies golden `TodoPolicy` (`read`: the owner or the requester, 404 for
anyone else; `edit`: the owner, 403 `TODO_NOT_YOURS` for the requester; `editScope`, the condition of the
statement that locks, so a refused caller never takes the owner's lock), used by `TodoService.findById` and
`lockEditableTodo`, tested by `TodoPolicyTest` and `TodoRequesterIntegrationTest` (each party by each call, a stranger, the relationship changing); ask for
the parties and their actions. **Status moves by different parties** (a leave request approved or rejected by
the manager, cancelled by the requester, revoked by HR; REST-03): each move is its own action endpoint
(`PATCH {{urlPrefix}}/<resources>/{id}/<verb>`, `POST` for a move that creates or starts something), each with its
own `@Requires`, policy method (the party's rule, then the status enum's `canBecome`) and `allowedActions` flag (its
contract row naming that endpoint, references/tests.md row 28l), and
a body holding only what the move takes (the `version`, a reason); one `PATCH /<resources>/{id}/status` with the
target, as golden `Plan`'s below, when one permission covers every move. **Money, exactly one current, month values** are use cases: no golden code,
the house-review rules are the bar; ask. A **status workflow**
(several states, only some moves allowed) copies golden `PlanStatus` and `Plan.changeStatus`
(`PATCH /plans/{id}/status`, `PlanStatusRequest`, `PlanStatusIntegrationTest`): the moves as one
`canBecome(target)` switch on the status enum (with tenancy also `TenantStatus`; the enum carries that alone, as
`PlanStatus` does: a predicate such as `isFinal()` only when the entity's guard and the policy's rule call it,
never beside a guard and a rule that each spell the states out, SMELL-18), the
entity's change method refusing the rest with 409 `<ENTITY>_STATUS_CHANGE_INVALID` naming both
states, and a test per allowed and per refused move. A move to the record's **current** status is
refused like any move the switch doesn't list (409, tested), unless the owner decides it is a no-op
(golden `Todo`'s status is a flag, not a workflow: there it is a no-op); say which in the API text. A
workflow's refusals are 409s through the change method, so its unit test (tests.md row 26) asserts
those, not an `IllegalStateException`. A target that no state may move to (a status
only the create sets, `ACTIVE` for an enrollment, `PENDING` for a tenant) is refused the same way, 409
with that code: the move is valid in itself, the lifecycle refuses it (ERR-05). **Ask about the
terminal statuses** (WITHDRAWN, CANCELLED, CLOSED): may a record in one still be edited (PUT, the
member and file writes) or deleted? When it is frozen, the entity's edit method guards it, and the entity's
policy names the same code **first after the version check**, before any other rule (a configured maximum, a
parent's state), since the frozen state decides a per-record flag (AUTHZ-07): 409
`<ENTITY>_<STATUS>` (`ENROLLMENT_WITHDRAWN`, ERR-04's names; golden `Plan.requirePlanned`, first in
`Plan.reschedule`, and `PlanPolicy.reschedule`, which `PlanService.update` asks before the entity's change and which
lists the plan's state before its todo's: 409 `PLAN_DONE`, `PLAN_CANCELLED`), documented on each refused write, with
tests.md row 26b (golden `PlanStatusIntegrationTest.aFinalPlanIsFrozen`, `aFinalPlanIsRefusedBeforeItsTodosState`) (whose write would also break another rule, so the order is tested); when only the delete stays open, say so in the API text.

---

## Step 4: Generate

Every file of this step and the next ones is written with **Write** (a new file) or **Edit** (a change), never by a
Bash command (*Tools*, above).

### Conventions (all generated code)
House-review's rules, as the golden files apply them: KISS and SRP (SMELL-29: queries written top to
bottom, plain helpers, no lambda-taking helpers), no tunables in code (SMELL-17), comments only for a why the names can't carry (SMELL-19)
(SMELL-19), `Objects.isNull`, `Optional` only as a return type (JAVA-08), no `var` and text blocks
(JAVA-13), streams without side effects (JAVA-14), enums with `==` (JAVA-18), minimal visibility
(JAVA-03). **Constructors** (JAVA-26): every generated bean (service, mapper, controller, `*RepositoryImpl`)
takes its collaborators through `@RequiredArgsConstructor` over `private final` fields, never a
hand-written constructor; a public class whose fields include a package-private type takes
`@RequiredArgsConstructor(access = AccessLevel.PACKAGE)` (JAVA-27: narrow the constructor, never widen the
type). A `@Qualifier` on such a field needs the project's `lombok.config` line (bootstrap copies the
file); a `@Value` never goes on a field (a `@ConfigurationProperties` record instead). **Settings are typed by what
they mean** (house-review CFG-12), wherever this skill writes one (a purge job's `Settings`, an `app.*` key, a limit):
a time span is a `Duration` (`5m`, `1d`; a span of calendar days a `Period`), a size a `DataSize`, an address a `URI`, a
schedule the project's `Cron` (golden `common/scheduling/Cron`), a mode an enum, one value per case a `Map<SomeEnum,
X>`; the unit lives in the value, never in the name (`keepAfterExpiry: 1d`, not `keepDays: 1`), and each bound is in
the record (`@DurationMin`, `@Positive`). A number stays a number only for a count, or for a limit on a request field,
in the field's own unit (a `limits.yml` key such as `max-minutes`). **Naming** (SMELL-20): bodies `<Entity><Action>{{requestSuffix}}`, GET parameters
`<Entity>Filter`, responses `<Entity>Response`; list rows `<Entity>SummaryResponse` only when they
leave out heavy fields (a `text` column with a limit over 1,000 characters, or a collection).

### 4.1 Filter
Cursor: `extends {{CursorFilter}}` (golden `TodoFilter`). Offset: `extends {{PageFilter}}` (golden
`TodoListFilter`). One field per filterable attribute: FK ids (named like the request field,
`todoListId`), enums typed as enums, a `query` with `@MaxLength("search-query")` (over the record's own
name-like text: a title, a name, a code; a free-form note or description is not a search target unless the
owner asks, recorded in *Decisions per entity*), a `sort` enum with
a default; `requireXPredicate()` per optional field (the repository builds predicates only from
these). A filter whose `query` predicate is shared with the entity's dropdown implements
`QueryFilter` (golden `TodoListFilter`, `TagFilter`), so one helper serves both; a list without a
dropdown needs no interface (golden `TodoFilter`). The dropdown's `query` searches its label only;
when the list's `query` searches more (a name **or** an email), the two predicates differ, and the
filter doesn't implement `QueryFilter`.

**A contains search (`query`)** is offered where the developer asks for it, alone or with other filters,
on any table (DATA-10, its trigram extension point included; golden: the tenancy overlay's `TenantFilter`,
whose `query` works alone).

**Then the indexes**, now that the orders and filters are chosen, by DATA-10's rules: one per offered order
(NEWEST's `(scope…, created_at, id)`; an order index leading with a foreign key replaces the key's own index),
every foreign key's, and the unique and exclusion constraints the rules require. **A filter's own index is the
developer's call** (the table's size, the filter's cardinality and selectivity: only they know them): ask per
offered filter, with the list's filters and sorts, "index this filter?". A yes is `(<scope>, <filter>, <sort
keys>, id)` per offered order (golden `0031-filter-indexes.sql`); a no, or no answer (unattended: the business
rules' answer, else no index, **assumed**), leaves the filter to the order's index. Record each answer in
*Decisions per entity*. **A scope's fixed condition** (a reach that leaves out the drafts, `status <> 'DRAFT'` in a team
or finance scope) is a filter like any other: no partial index for it by default, asked with the filters; the list's
order index serves it, as the first-page plan test shows (DATA-10). Range and overlap filters are offered under their
own order only; the rare-value partial index is DATA-10's extension point, not generated. They go in a new changeset if the entity's own one is
shipped (Step 2). Test row 28 checks the indexes that exist: each one is there, each list's first page and a
cursor list's batch after a cursor are planned on its order's index, and a requested filter index's first page
on that index (tests.md, *Index tests*).

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
  `@CrossField(reportedOn = "<x>To", message = …) boolean is<X>RangeOrdered()` per range (true when either
  bound is absent: `DateRanges.isOrdered` for days, `NumberRanges.isOrdered` for numbers, never the comparison
  written in the filter, whose fields static analysis then reads as never null-checked, CFG-11), so an inverted
  range is a 400 naming the range's end, the parameter a form shows the
  error under (`spentOnTo`, `maxMinutes`; golden `TimeEntryFilter`; ERR-07). Any other rule across fields
  takes the same shape, reported on the field that needs the other (golden
  `TodoFilter.isDueOnOrBeforeSortedByDueDate`, on `dueOnOrBefore`); a plain `@AssertTrue` is refused by
  ArchUnit, since it is reported on the getter's own name.
- **Flags:** the list filters a flag as `status` (`DropdownStatus`, default `ALL`), the dropdown
  defaults to `ACTIVE`; a flag without rules is set by the update (`@Setter`).
- **Pattern fields:** `@Pattern` with a readable message instead of `@MaxLength`, **only when the
  pattern bounds the length** (REST-09's test, its `\s*` case included; golden `TagColor`:
  `(#[0-9A-Fa-f]{6})?`). Surrounding spaces of a format-validated value are a 400 (test row 11 tests case
  only). Normalised in the mapper (case),
  backed by a database `CHECK`. The message names the **exact** character classes and length, never a
  looser word: `[A-Za-z0-9-]{1,20}` is "1 to 20 ASCII letters (A-Z, a-z), digits or hyphens", not
  "letters" (`ÄB1` is a letter and is refused).
- **Ranges in a body** (a plan's `startsOn`/`endsOn`): one `@CrossField(reportedOn = "endsOn", message = …)
  @Schema(hidden = true) boolean is<X>RangeOrdered()` on the request record, like a range filter (a 400 at
  `#/endsOn`), backed by a `CHECK`
  (golden `PlanCreateRequest`); the API text says whether the ends are inclusive. An updatable range
  changes through **one** entity method that takes both ends and guards them (`reschedule(from, to)`,
  `IllegalStateException` on an inverted range; golden `Plan.reschedule` through `PUT /plans/{id}`,
  `PlanUpdateRequest`, `PlanService.update`), never a setter
  per end. When the range's scope column (the exclusion constraint's `owner_id WITH =`, a shift's
  member) is updatable too, it changes through an action of its own (`handOverTo(member)`), not a
  third parameter of `reschedule`: each guards its own rule and is tested on its own (no golden code: a
  plan's owner is its todo's). Its API is a
  `PATCH /<entities>/{id}/<scope>` (`PATCH /on-call-shifts/{id}/member`) with a
  `<Entity><Scope>Request {<scope>Id, version}` (a person is a user id everywhere: `userId`, never
  `memberId`); a person is checked through `Members` (404
  `TENANT_MEMBER_NOT_FOUND` for a removed one), and moving to the current value is a no-op answering
  200 with the version unchanged. The version is checked **first** (a stale one is 409 even for a no-op),
  then the no-op; the person is checked only when the value changes, so a hand-over to the member the
  record already names, removed since, is that no-op too (4.3's updatable reference).
- **A filter for "what is on during [from, to]"** is an overlap, not the range `&&` operator (not
  leakproof under row-level security, TEN-36). Two comparisons alone (`endsOn >= :from AND startsOn
  <= :to`) give the index **no lower bound**: every index orders by the start, so `endsOn >= :from`
  filters the scope's whole history before `:to`, and "who is on call this week" reads years (DATA-10).
  So **ask the maximum range length** (a plan spans at most a month, a shift a week) and make it a
  configured limit (`limits.yml` `<entity>.max-days`, with the comment "raise it any time, lower it
  only once no stored range is longer"): checked on create and update by
  `@ConfiguredMax(value = "<entity>.max-days", reportedOn = "endsOn")` on a hidden getter `getLengthInDays()`
  (a 400 at `#/endsOn`, like an inverted range) (null while a date is missing or the range inverted), and used
  by the repository for a third predicate, `startsOn >= :from - (max - 1)` (golden `PlanCreateRequest`,
  `PlanFilter.plannedFrom`/`plannedTo`, `PlanRepositoryImpl.filterPredicates`). Without a maximum
  (open-ended ranges), record the decision that the filter scans the scope's history. Like any range
  filter, it is offered under its start's order only (above). Tests: tests.md rows 25b and 28.
- **Email** (golden: the sign-in piece's `SignupRequest.email`, core's `common.validation.Emails`):
  `@NotBlank @Email @MaxLength("<entity>.email") @AsciiOnly` (254 in `limits.yml`; `@AsciiOnly` passes an
  empty value, so it gets only "must not be blank"), `@Schema` "Email address (ASCII only);
  stored lower-case; unique ignoring case". The mapper stores `Emails.normalize(email)` and every lookup by
  email (an exists check, a find by email, a rate-limit key) normalises with the same method, never its own
  `strip().toLowerCase(…)` (no Unicode normalisation: every stored address is ASCII, `Emails`' javadoc says
  why), backed by
  `CONSTRAINT ck_<table>_email_normalized CHECK (email = lower(trim(email)))` and a plain
  `UNIQUE (email)`, which a lookup by email uses (DATA-05). Tests: a malformed address 400; the
  length bound both sides with an address `@Email` accepts (64 characters before the `@`, domain
  labels of at most 63: `"a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." +
  "d".repeat(58) + ".io"` is 254; on a table unique per tenant or per scope, a second use in the class
  is a 409, so each test builds its own with a fresh local part of the same length); surrounding spaces 400 (row 11 tests the case only); a case-only
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
  `@EarliestDate("<entity>.<field>")` (one key may serve a range's two ends, golden `plan.dates`;
  `app.limits.earliest-date`; the values are the developer's, Step 1's *Ask, don't guess*): a `LocalDate` reaches years
  PostgreSQL's `date` can't hold, and `-300000-01-01` is otherwise a 500 at the database (REST-09).
  A date that records the past (a day worked, a birth date) also takes `@NotAfterToday`, never
  `@PastOrPresent` (JAVA-25); a filter on it a sanity `@LatestDate`. **A state action that records a
  day** (returned on, approved on) takes it in the action's request, `@NotNull @EarliestDate
  @NotAfterToday` (the user's own today, sent by the client), checked against the record's own dates
  (a return before the loan's start: 400 `<ENTITY>_<FIELD>_BEFORE_<OTHER>`), never
  `LocalDate.now(clock)`: the server's UTC day is yesterday for a desk east of UTC until midnight UTC
  (JAVA-25). A moment the action records stays the server's `clock.instant()` (golden
  `Todo.changeStatus`'s `completedAt`). Golden: `PlanStatusRequest.doneOn` (given with DONE and only with
  it, a `@CrossField` on `doneOn`), checked in `Plan.changeStatus` against the plan's first day (400
  `PLAN_DONE_ON_BEFORE_STARTS_ON`), tests `PlanStatusIntegrationTest.doneOnIsAtMostToday` and
  `doneOnIsNeverBeforeTheFirstDay`. **A rule a date decides against today** (a request revoked only while its
  end date is today or later, a deadline at midnight) reads today from `BusinessCalendar.today()` (`common/time`:
  the calendar day of `app.business-calendar.zone` at the clock's instant; the zone is the owner's, `UTC` until they
  name the organisation's region id, so ask), in the service, which passes it to the policy method (the write
  and the flag then agree, and the policy's table test fixes the day): never `LocalDate.now(clock)` (UTC's day)
  and never a day the client sends (the rule is the organisation's, not the user's). The status the dates make
  (a leave `TAKEN` once it has begun) is derived when read, never stored (DATA-37). A planned date (a leave, a plan)
  lies ahead or behind: no `@NotAfterToday`, a sanity `@LatestDate` (golden `plan.dates`), and the
  rules the business gives (within one calendar year: name it so, e.g. `isInOneCalendarYear`; no
  golden code).

### 4.2 Requests (records)
`<Entity>Create{{requestSuffix}}`, `<Entity>Update{{requestSuffix}}` (with `@NotNull Long version`),
one per state action (`<Entity>Status{{requestSuffix}}`). **Every String carries `@MaxLength("<entity>.<field>")`**
(except a closed `@Pattern` field that bounds its own length, 4.1 *Pattern fields*: golden `TagColor`), the
elements of a String collection too (`Set<@NotBlank @MaxLength("…") String>`), and its
number (the developer's, Step 1's *Ask, don't guess*) is added to `app.limits.text` in `config/limits.yml`; collections carry a configured size
limit (`@ConfiguredMaxSize("<entity>.<field>")`) and `@NotNull` elements, plus `@NotEmpty` only when
the developer says it may not be empty (an optional set of tags may be empty); a configured upper bound of a number
carries `@ConfiguredMax("<entity>.<field>")`, a money amount's too (`BigDecimal`: the core's decimal validator, the
setting quoted, `max-amount: "10000.00"`; golden `LimitsIntegrationTest.decimalMaximumComesFromConfiguration`), never a
validator of the module's own; a decimal carries `@Digits(integer, fraction)` of its column, and the mapper stores it
at the column's scale where it strips text (`.amount(request.amount().setScale(2))` on the create and the update:
never a rounding, `@Digits` has refused more fraction digits), so the create's answer is the reads' `42.50`, never
the sent `42.5` or `1E+1` (house-review JAVA-15, tests.md row 25g); a date carries `@LatestDate("<entity>.<field>")`
(`app.limits.latest-date`) when the developer gives it a latest day or a sort sentinel lies above it. `@Schema` on every component. Golden:
`TodoCreateRequest`. **A text field a cursor carries** (a cursor list's text sort key, a dropdown's
label) sizes the cursor: `app.limits.text.cursor` must hold `(6 × its @MaxLength + 100) × 4/3`
(Base64url JSON, a control character escaped in six bytes), so raise it when that is larger, and add
the field's key to `LimitsIntegrationTest.CURSOR_TEXT_KEYS`; otherwise the next batch after a long
value is a 400 on the cursor the server handed out (tests.md rows 16, 24). A collection component is wrapped
read-only in the compact constructor, null-safe (`tagIds = Objects.isNull(tagIds) ? null :
Collections.unmodifiableSet(tagIds);`): Jackson builds the record before validation, so a null element still
reaches its constraint, and static analysis finds no exposed collection (Step 7).

### 4.3 Responses (records)
`<Entity>Response` (every field, FKs as ids, which stay beside a displayed parent's `{id, name}` record for
clients that only link (golden `TodoResponse`: `todoListId` and `todoList`), ending with `{{auditFields}}`; a reference to another
aggregate that clients display is `{id, <the parent's display field under its own name>}`, golden
`PlanTodoResponse {id, title}`: a student's `{id, fullName}`, a course's `{id, title}`, never a
field renamed to a literal `name`; one record per referenced aggregate (`<Entity><Parent>Response`),
a list of them for a many-to-many) for the detail; for list
rows a `<Entity>SummaryResponse` without the heavy fields (*Conventions*, Naming), still ending with the audit
fields. Golden: `TodoResponse`, `TodoSummaryResponse`. A collection component is copied in the compact
constructor (`tags = List.copyOf(tags);`, Step 7's static analysis). A list row's displayed parent name comes in
**two steps**, in the same module as across modules: the batch, then one lookup of the batch's parent
ids for their names (golden `PlanService`'s facade batch lookup; statement count + 1), never a join in
the keyset query (with tenancy the join carries the tenant predicate twice and its plan turns on the
cursor's position). The two statements see the database at two moments: a row whose parent the
lookup misses (deleted with it, or moved and its old parent deleted, in between) is **left out** of the
batch, never mapped with a null parent into a 500; the cursor still continues after the batch's last
row (golden `PlanService.withTodos`, test `listLeavesOutAPlanWhoseTodoVanishedBetweenTheReads`). A
single read of the child, then its parent, answers the child's own 404 when the parent is gone (Step 1's
`find<Parent>View`; golden `PlanService.findById`). That two-read rule is for **another module's**
parent (an id column and a facade). Three more shapes, golden on `Todo` (tests.md rows 27b-27d, 28f,
28g):
- **a same-module parent on the detail** (a `@ManyToOne(fetch = LAZY)`): `todoList {id, name}` read with
  the child in one statement (`TodoRepository.findWithTodoListById`, an `@EntityGraph`, the policy deciding after it), also
  for a write that may hold no row lock (a status change to the status it has); the writes that lock the
  child read the parent afterwards (safe: a locked child's parent can't be deleted while the lock is held). **List rows** carry it
  too, through the batch lookup above in the same module: a `dto/internal/<Parent>Name(id, name)` record
  (never on the wire), the parent's repository `find<Parent>NamesByIdIn…` as a JPQL constructor query of the
  two columns, the parent's service `findOwned<Parent>Names(ids, ownerId)` (`List.of()` for no ids), the
  mapper's `with<Parent>s(rows, names)` leaving out a row whose parent it misses; statement count + 1 (golden
  `TodoListName`, `TodoListRepository.findNamesByIdInAndOwnerId`, `TodoListService.findOwnedTodoListNames`,
  `TodoService.withTodoLists`, `TodoSummaryResponse.todoList`);
- **a reference to a person** (an owner, an assignee, who asked): an id with a foreign key (to `users`;
  with tenancy to the tenant's `members`, which a removal marks left and never deletes), checked through
  the person's module **whenever a write names someone new** (`TodoService.requireRequester`: `Users`, with
  tenancy `Members.isMember`, 404 `TENANT_MEMBER_NOT_FOUND`): the create, and an update only when the id
  changes. **A party who will act** (Step 1's *named parties*) is checked too: a `user.api.NamedParty` constant
  (the party, the permission it acts with, whether the owner may name themselves, the writes that name it; golden
  `TODO_REQUESTER`), and `PermissionHolders.holds(id, party.mustHold())` after `requireUser`, else 409
  `<ENTITY>_<PARTY>_INELIGIBLE` with the id (golden `TODO_REQUESTER_INELIGIBLE`; house-review AUTHZ-04). A party
  the owner may not be (`Owner.NEVER_NAMED`, Step 1's answer) is refused first, before the account is read: `if
  (!NamedParty.<PARTY>.mayBeTheOwner() && id.equals(currentUser.id()))`, 400 `<ENTITY>_<PARTY>_IS_OWNER` with its
  `errors` pointer (`#/<party>Id`; golden `TodoService.requireRequester`, `TODO_REQUESTER_IS_OWNER`). Its picker is
  the user module's `GET /users/dropdown?for=<the write's permission>`, which reads the same constant and leaves
  the caller out for `Owner.NEVER_NAMED` (golden `NamedPartyService`): add the constant, never a dropdown of your
  own (REST-13). Where the state freezes the update while the party still has to act (a
  submitted claim), the owner's reassignment is its own endpoint (`PATCH /<resources>/{id}/<party>`, the owner's
  EDIT permission, while the record is not final) with the same check; its docs say when it is open as the policy
decides it (a stale party is an example, "for example when the approver left", never a condition the code doesn't
check). Tests: tests.md row 27e. A PUT is a full replace and re-sends the person it read, who may have left since: checked again,
  every record of a former member would refuse every later edit, so the one it already names is kept
  (and can be cleared with `null`), golden `TodoService.update`, `TodoUpdateRequest.requesterId`. List rows
  carry the person as the id (`requesterId`); when the developer wants the name on rows (Step 1's *Ask,
  don't guess*), it comes through one
  `Members.getMemberViews` per page (statement count + 1), never per row. With tenancy the detail shows `{id, fullName}` while
  they are a member and the id alone once they left (every record still listed and editable); without
  tenancy the id alone, as every internet account is a USER and a name is personal data (Step 1). Ask
  how a former member's records show if the owner wants more than the default (a `left` flag), and record
  it;
- **a display number**: SKILL.md Step 2's TEN-18 row; the golden `Todo.number`, `0026-todo-numbers.sql`
  (the rows already there numbered in id order, the counter after the highest; row mode per tenant, for
  every tenant whatever its status), `TodoNumberSeed`, the create's `createIfAbsent` in row mode.

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
`*_IN_USE` for the parent's delete, and `ERROR_PREFIX_DELETED_MEANWHILE`, a 404 without placeholders, for a
child's insert or update after it: `byForeignKeyOnWrite`). Never check-then-insert (DATA-05); every constraint kind maps (ERR-02), except a key that can't trip once the service checked its target first: a person's key to `members` (checked through `Members`, whose row is never deleted) a `CHECK` that the mapper's own normalisation always satisfies (`ck_<table>_email_normalized`) and one the
request's validation always satisfies (`ck_<table>_dates` behind `is<X>RangeOrdered`) stay unmapped, with that reason in the crud profile's *Decisions per entity*. Every later write that names the person calls the same check; one that forgets it answers 500, which its guard proof shows (never map the key to hide a missing check). A state
conflict is 409, a wrong value 400 (ERR-05). A child's foreign key adds the parent's `*_IN_USE` to
**the parent's** delete docs (`@ApiResponse(409)`) in the same change, asserted in **the child's**
test class (with the parent's `deleteWhileReferencedIs409`): the parent's existing tests stay unchanged
(*Updating an entity* rule 4), except a line that pins the parent's old delete docs (an assertion that its
`409` doesn't exist): the new documented code is the same contract change, so that line goes and the
child's class asserts the code instead. Never document a code before the constraint that raises it exists (no
`*_IN_USE` on a delete nothing references yet).
**Bootstrap brings no feature codes**: every code, mapping, message, constraint entry and limit key
is added here. Constraint mappings go in the module's own `ConstraintErrorCodes` bean (the handler
aggregates them): the module **whose changeset declares the constraint** maps it, even to another
module's code (golden `PlanningConstraintErrorCodes`: `fk_plans_todo_id_owner_id` → `TODO_IN_USE`).
A child's composite owner FK `(parent_id, owner_id)` maps twice: to the parent's `*_IN_USE` in
`byConstraintName` (the delete side) and to the parent's `*_DELETED_MEANWHILE` in `byForeignKeyOnWrite` (the
insert/update side; a cascading key only there), golden `TodoConstraintErrorCodes` (DATA-33). A
code answered from a constraint gets a message without `{0}` (the handler has no argument for it);
an apostrophe is written `''`, and a year or an id passed as a number `{0,number,#}` (ERR-06). No
overlapping ranges: an exclusion constraint mapped to `<ENTITY>_OVERLAPS`, with no scope lock (a
simultaneous overlap answers a retryable 503; DATA-35, golden `0011-plans.sql`, `PlanService.create`).
An exclusion constraint with a plain column (`owner_id WITH =`) needs the `btree_gist` extension: the
project's first one creates it in a changeset of its own, before the table's (`CREATE EXTENSION IF NOT
EXISTS btree_gist;`, golden `0011-plans.sql`'s first changeset); without it the migration fails. Its
`docs/runbook.md` gets a line (a restore onto a server without the extension fails the same way). A
returned record that must keep the days it really held: the constraint's range is
`daterange(starts_on, least(coalesce(returned_on, ends_on), ends_on), '[]')` with no status predicate,
rather than `WHERE (status = '<LIVE>')`, which frees every day of a returned record.
A lock a write already takes for a state rule (Step 1) queues the writes it covers, so their overlaps
answer 409; never take a lock only to turn that 503 into a 409 (DATA-29).

### 4.5 Mapper
Golden `TodoMapper`: `toEntity(request, resolvedParents…, ownerId)`, `update(entity, request,
resolvedParents…)` (only mutable fields; moves through the entity method), `toResponse(entity)`. No
`toResponses`: list pages are projections (4.7).

### 4.6 Repository
`<Entity>Repository extends {{BaseRepository}}<Entity, ID>, Custom<Entity>Repository`, plus
`findByIdAndOwnerId` when the owner alone sees it (golden `PlanRepository`); an entity whose policy has a `listScope`
loads by id and asks the policy instead (golden `TodoService`): one shape per entity, Step 1's *An owned entity*, AUTHZ-05; and a lock only for a rule no constraint, foreign key or
`@Version` holds (DATA-29), with a one-line javadoc naming that rule: a
`@Lock(PESSIMISTIC_WRITE) findForUpdateBy…` when a write changes its member rows (files, links) or
deletes it (golden `TodoRepository`), also taken on a parent before a cap, a multi-row rule or a check
of the parent's state (row 23b; golden: the todo's `findForUpdate(id, TodoPolicy.editScope(...))`, taken by a plan's
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
- Cursor: `Cursor cursor = Cursor.read(filter.getAfter(), sort, CURSOR_KEYS)` (the keys every order
  writes, its sort keys and the id: a cursor with a key more or less is a 400); `if (cursor.hasPosition())` add
  `Seek.after(builder, descending, key1, cursor.text(0), …, id, cursor.uuid(n))` (one row-value
  comparison, never a hand-written seek: DATA-10; each key through its typed reader, `text`, `uuid`,
  `date`, `instant`, `number`, `enumValue`, which refuse what the column can't store: REST-08); **NEWEST is `createdAt` then the id**
  (`Seek.after(builder, true, root.get(X_.createdAt), cursor.instant(0), root.get(X_.id), cursor.uuid(1))`,
  an offset list's `builder.desc(root.get(X_.createdAt)), builder.desc(root.get(X_.id))`, the index `(scope…, created_at, id)`), never
  the id alone, whose primary key competes with the scope's index (DATA-10, golden `TodoRepositoryImpl`);
  `query.orderBy(Seek.order(builder, descending, key1, …, id))` on **the same columns, id last**
  (no id after a sort column that is itself unique and not null: DATA-10);
  `setMaxResults(size + 1)`; `CursorBatch.of(rows, size, row -> Cursor.write(sort, row.key1(), …,
  row.id()))`. Keys non-null (a generated sort column for a nullable one). **The service** turns the
  `CursorBatch` into a `CursorWrapper`.
- Offset: the sort enum is plain names (golden `TagSort`); the select query orders by the repository's
  `orderOf(builder, root, filter.getSort())`, an exhaustive `switch` over the metamodel (golden
  `TagRepositoryImpl.orderOf`; never `QueryUtils.toOrders` or a property string, REST-08), with its own
  count query, then `OffsetPages.page(rows, count, pageable)` (skips the count on a partial page); the
  service passes `paging.offset(filter)` and wraps the page in `PaginationWrapper.fromPage`.
- Predicates from the `requireXPredicate()` methods; text search with `{{TextSearch}}.contains`.
- Child data on a list page: an aggregate subquery in the projection, or two steps (a page of ids,
  then one grouped query). Never a collection fetch (DATA-26).

### 4.8 Service
Golden `TodoService` / `TodoListService`. **Add the aggregate's row to the project's
`AggregateArchTest.AGGREGATES`** (a member: add it to its root's members). Another aggregate is
reached only through its service's entity methods, never its repository (ARCH-15); a use case that
writes several aggregates goes in a `<UseCase>Manager` that owns the transaction (golden
`TagMergeManager`, Step 3). Class
`@Transactional(readOnly = true)`, `@Transactional`
writes; create `saveAndFlush`; update/actions change the managed entity after
`entity.checkVersion(request.version())` (409 `OPTIMISTIC_LOCK_CONFLICT`), then `flush()`. **One order of checks** on
every write (golden `PlanService.update`, `changeStatus`, `TodoService.getEditableTodoAtVersion`): who may write (the
scoped lookup's 404, the policy's 403 for a second party outside their part), then the version, then the state's rules
(a frozen status, a window, a parent's state); tests.md row 4 pins both seams. A write
that changes member rows (files, links) or deletes the root loads it `findForUpdateBy…` first
(golden `TodoService.update`, `replaceTags`, `delete`); member rows that another aggregate's delete
can remove by cascade (a tag's delete removes its `todo_tags` rows) are deleted with one statement
before the root's own delete (golden `TodoTagRepository.deleteAllOfTodo`), since a versioned delete
per row misses the removed ones (409); delete loads (404) then flushes; a state change on an entity
goes through a method that guards the state (an `edit(...)` calling `requirePending()`; golden
`Plan.reschedule` calling `requirePlanned()`, `Plan.changeStatus` through `PlanStatus.canBecome`), never public setters a later caller can use in the wrong state; ownership in the entity's one shape (Step 1's
*An owned entity*: `findByIdAndOwnerId` for the owner alone, a load by id and the policy's `read`/`edit` beside a
`listScope`; another user's record is 404); parents written into are read with the plain
owned lookup (`getOwned<Parent>`, not locked, unless the write checks the parent's state: Step 1); list sizes from `{{Paging}}`. **A parent in the same
module** is reached through its service, public once a child needs it: `getOwned<Parent>(id)` returns
the entity (golden `TodoListService.getOwnedTodoList`); a batch lookup for a write needs every id
(`getOwned<Parent>s(ids)`, 404 naming a missing one, golden `TagService.getOwnedTags`), one for a
list page's names returns those found, under its own name (`findOwned<Parent>Views(ids)`: absent ids
simply absent) and as a constructor projection of the view's columns, never whole entities (DATA-11;
golden `Todos.getOwnedTodoViews` over `TodoRepository.findViewsByIdInAndOwnerId`); both answer
`List.of()` for no ids without a statement. These methods change **the parent's generated
service**: its private `get<Parent>` becomes public, and the
batch lookup is added beside it. Name both in the report's changed shared files, and review that
service with the child (Step 8). A same-module parent's names on a list page: golden
`TodoListService.findOwnedTodoListNames` (4.3); a shared root's lookups are the same without the owner
(`get<Parent>` / `get<Parent>s` / `find<Parent>Names`). **A rule from configuration** (a maximum loan
length, an upload policy's file count) on an update runs only when the fields it reads change, so a
lowered limit never blocks correcting another field of an existing record (golden
`TodoService.changeAttachments`: the file count is checked only when the files change; tests.md
row 25f). A field's own configured cap (`@ConfiguredMax` on a full-replace PUT, a maximum year) is checked only when the value changes too (compare with the stored one in the service), or a lowered cap blocks editing anything else of an older record; its `limits.yml` comment says "lower it only once no stored value is higher" when it is checked on every write. The exception is a range's configured maximum length (`@ConfiguredMax` on the update's
`getLengthInDays()`, 4.1): it is checked on every update, which is safe because the maximum may only
ever be lowered once no stored range is longer (its `limits.yml` comment says so). No remote call inside a
transaction (DATA-01). Never lock "the current X" by a mutable column (DATA-32).

**An access-changing write with the session version** (`tokenRevocation: session-version`, the
`session-version` piece): a generated write that changes what a person may do (a role or status field
of an account or membership, a lock) raises the session version **in its own
transaction**, through the owner's bump (golden `MembershipService.changeRole` calling
`bumpSessionVersion`; without tenancy `UserService.bumpSessionVersion`, `Propagation.MANDATORY`), after
the checks that can still refuse it; where it takes access away, the same transaction revokes the
refresh tokens the code doesn't already refuse. Redis is never written in the transaction (the bump
publishes `SessionVersionsRaised`). The write still checks the caller's current role in the database
(rung 3). Tests: the older token 401 `SESSION_VERSION_STALE` on the next request, the refresh carries
the new access, a rolled-back write raises nothing (golden `SessionVersionTenancyIntegrationTest`,
`SessionVersionServiceIntegrationTest`; house-review SEC-07). **The exception, with `authorization: roles-piece`:**
the permissions are read from the tables on every request (AUTHZ-11) and no token carries them, so a write of
roles, personal grants or a role's contents (`user_roles`, `user_permissions`, `role_permissions`, and a generated
table that feeds them) raises nothing, as golden `PersonAccessService` and `AccessRoleManager` don't; the bump stays
for what the token carries or its refresh relies on (the account's status, the password, signing out everywhere,
with tenancy the membership).

**A retried POST (REL-11's two questions).** For each POST the entity gets (its create, each action that is a
POST): first, does a unique or exclusion constraint, or the version the write sends, already refuse an identical
second request? Then nothing more. Otherwise, does the duplicate move money, act outside the database beyond
recall (mail, SMS, a webhook, a paid API), use up something counted (stock, a quota, a balance, a gapless number),
land with another person (a submission routed to an approver), or carry legal or accounting weight (or have no
delete for its owner)? None: no key (a draft, a todo). Any: **ask** the developer (unattended: the business rules'
answer, else record it as an open item in the report); the house has no golden `Idempotency-Key` yet, so the
generation names the endpoint and REL-11's row and leaves the key to the developer rather than inventing one.
Record the verdict per POST in *Decisions per entity*.

### 4.9 API interface (OpenAPI)
Golden `TodoApi`: `@Tag`; `@Operation` with text-block descriptions; an `@ApiResponse` for every
status and errorCode the endpoint can return (except `API_VERSION_UNSUPPORTED`: a customizer adds it
to every operation; error `@Content` needs no `mediaType`, a customizer publishes it as
`application/problem+json`): 200/201/204, 400 (`VALIDATION_FAILED`,
`REQUEST_UNREADABLE`, `REQUEST_PARAMETER_INVALID` for a bad id, enum, date or cursor; a list's
`VALIDATION_FAILED` names an empty `sort`, which `@NotNull` refuses), 401, 403
(roles), 404 (incl. a parent's `*_DELETED_MEANWHILE` on a child's write), 409 (`*_TAKEN`, `OPTIMISTIC_LOCK_CONFLICT`,
`*_IN_USE`), 503. A delete documents
`OPTIMISTIC_LOCK_CONFLICT` only when it can happen: an unlocked delete can lose a race (golden
`TodoListApi`, `PlanApi`); a delete that loads its row `findForUpdateBy…`, takes no version and
removes its cascadable member rows set-based can't (golden `TodoApi`; the race test
`TodoTagsIntegrationTest.todoDeleteRacingATagDeleteSucceeds`). A write whose member rows another
aggregate's delete may remove meanwhile documents the 409 for that case (golden `replaceTags`). **A named party's write**
(an approver's approve and reject) documents each case by the order of the checks (4.8): once a reassignment has
committed, the former party gets the stranger's 404 (or the reader's 403 `<ENTITY>_NOT_YOURS`), said in that
code's description ("or no longer yours to decide"); the 409 `OPTIMISTIC_LOCK_CONFLICT` is a stale version, a
reassignment racing the call among them, never "after a reassignment". An `@Schema` states exactly what the validation does ("unique ignoring case" for a
format-validated field, whose surrounding spaces are a 400), and its `example` passes the field's own rules (its
length, pattern and bounds): a date bounded by today (`@NotAfterToday`, its `<day>To` filter too) a day already past
(golden `TimeEntryUpdateRequest.spentOn`), never one after today, which the field refuses until that day comes.
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
`version = "v1+"` (the current baseline; REST-07), 201/204 with `@ResponseStatus`, nothing but the service call.
**Every handler declares exactly one of** `@Requires(Permission.<ENTITY>_<ACTION>[_OWN])` (the permission Step 1's
*Permissions* gave it; a detail several reaches read names each reach's READ, `@Requires({Permission.<ENTITY>_READ_OWN,
Permission.<ENTITY>_READ_ASSIGNED, Permission.<ENTITY>_READ})`, any one letting in), `@SignedIn` (the dropdown, which checks its `for` in the service: `{{dropdownSecurity}}`) or
`@Public` (never without the owner's word, and then a public path in the security chain): ArchUnit
`handlersDeclareWhoMayCallThem` fails the build otherwise, and `EndpointContractIntegrationTest` calls every
`@Requires` handler without its permissions (403) with no test to write (house-review AUTHZ-03, AUTHZ-09). Never a
role annotation or a `@PreAuthorize` string (REST-25). The write paths call the policy (4.8: `policy.x(...).orThrow()`
after the load; a locking load goes through the policy's `editScope`, golden `TodoService.lockEditableTodo`). The
OpenAPI 403 `ACCESS_DENIED (without <PERMISSION>)`, `(without any of <A>, <B>)` for several, is written from `@Requires` (`PermissionOpenApiCustomizer`): the
API interface names only the entity's own 403 codes (`<ENTITY>_NOT_YOURS`). Its `SPRING_ENDPOINT` entry goes into the exclude file with it (Step 7).

### 4.11 Dropdown (when the entity carries `{{dropdownAnnotation}}`)
Read `references/dropdown.md`. Golden: `TodoListRepositoryImpl.findDropdownItems`,
`TodoListService.dropdown`, `TodoListController.dropdown`. The label is a text key the cursor carries:
size the cursor limit for it (4.2).

### 4.12 Purge job (accumulating rows, Step 1's *retention*)
Golden: the sign-in piece's `user/service/RefreshTokenPurgeJob` over `TokenService.deleteExpiredBefore` and
`RefreshTokenRepository.DELETE_EXPIRED`, `config/jobs.yml`'s `refresh-token-purge`, the test profile's `"-"`; tests
`RefreshTokenPurgeJobIntegrationTest`, `RefreshTokenIndexIntegrationTest`. Rules: house-review OPS-03. One small class
per table, like the golden one; no shared purge framework.
- **The job**, `<Entity>PurgeJob` in the module's `service/` package, package-private: `@Scheduled(cron =
  "${app.jobs.<entity>-purge.cron}", zone = "UTC")` with `@MultiInstanceSafe(reason = "...")` (ARCH-12: the reason, each batch
  locks its rows `FOR UPDATE SKIP LOCKED`), the cutoff taken once from the injected `Clock` (now minus the period), the
  loop over the service's batch until a batch comes back short, one log line with the cutoff and the count. Its
  `Settings` record (typed, CFG-12): `@NotNull Cron cron` (the core piece's `common/scheduling/Cron`: a bad
  expression stops startup; `"-"` is off), the period as a `Duration` named for the column (`keepAfterExpiry`,
  `keepAfterFinish`, `keepFor`) with `@NotNull @DurationMin(days = 0)`, `batchSize` `@Positive`; a table whose end is
  a `LocalDate` (a day, not an instant) keeps its period as a `Period` (`7d`), the cutoff `LocalDate.now(clock)`
  minus it.
- **The service** of the table's aggregate (ARCH-15; a member's rows through its root's service): `@Transactional
  int delete<Ended>Before(Instant cutoff, int batchSize)`, one batch in a transaction of its own.
- **The repository**: the statement as a constant, `DELETE FROM <table> WHERE id = ANY (ARRAY(SELECT id FROM <table>
  WHERE <column> < :cutoff LIMIT :batchSize FOR UPDATE SKIP LOCKED))` (DATA-29's shape), and its `@Modifying
  @Query(nativeQuery = true, value = …)` method. A table that references this one goes first in its own batch, or the
  statement keeps a parent a child still names (`AND NOT EXISTS (…)`), as Step 2 decided.
- **The index** in the entity's changeset: `idx_<table>_<column>` (partial `WHERE <column> IS NOT NULL` for a
  nullable end; with row tenancy `(tenant_id, <column>)`).
- **The settings** in `config/jobs.yml` under `app.jobs.<entity>-purge` (`cron` a night-time UTC time a few minutes
  from the other purges, the developer's period with its unit and its reason as the comment, `keep-after-expiry: 30d`,
  never a bare number, which a `Duration` reads as milliseconds; `batch-size: 1000`), and `cron: "-"` in
  `application-test.yml`, where the tests call the job.
- **With tenancy** a tenant table's purge runs once per tenant holding data, each under `TenantScope.runAs`, one
  tenant's failure logged and the next tenant purged (golden: the tenancy overlay's `MediaCleanupJob.cleanUp`); in row
  mode the statement names its tenant (`AND tenant_id = :tenantId` in the batch, TEN-37).
- **Tests:** tests.md row 34, and the batch in the entity's index test (row 28).

---

## Step 5: Media (only when the records carry files and the project has the `media` piece)
Read `references/media.md`: a new `UploadPurpose` with its policy seed row, the link entity and
its changeset, the `<x>Keys` / `add<X>Keys` / `remove<X>Ids` fields, the claim and release in the
owner's own write, and the files in the response. Rules: house-review `media.md` (MEDIA-xx).
Golden: the todo attachments (`TodoAttachment`, `TodoService`). Without the piece, offer
`/crud-gen bootstrap` (never hand-write an upload flow).

## Step 6: Integration tests (always)
Read `references/tests.md` and follow the golden test classes. **The test plan, before the first test class:** first
the entity's variants, each by name: its writes, its lists (each reach's) and each filter's values, its parties (row 14's,
an owner holding another reach among them), its states, its flags, its bounded, normalised or decimal fields, its
configured caps. Then each tests.md row that applies × each variant its *Asserts* column multiplies over, one line each naming
one row, one variant and the test method: `4 · approve · stale version → approveWithAStaleVersionIs409`, `28m ·
SUBMITTED × owner · delete → aSubmittedClaimRefusesItsWrites`, `16c · /team → aTeamRowIsItsDetail`. A line naming
several rows (`1, 2, 3 → …`) or a variant in the plural (`every flag`, `each state`) is split before the first test. A
row that applies only in part still gets its line (a NEWEST sort: row 16's times set against the ids, and its tie).
After Step 7's green build, tick each line against the written test: the method exists and asserts what the row's
*Asserts* column says, on the response and at the variant the line names; a gap is fixed and rebuilt before the line
is ticked. The ticked plan goes into the report line by line, never summarised by rows (unattended too); a file, if one helps, only under `target/` (Step 7).
With `{{tenancy}}` not `none`, also
generate `EntityNameTenantIsolationIntegrationTest` (golden: the skills repo's
`overlays/tenancy/src/test/java/com/example/app/todo/TodoTenantIsolationIntegrationTest.java`):
another tenant's member holding the role that writes the record (`newUserIn(newTenant())`, or
`newUserIn(newTenant(), "ADMIN")` for a root whose writes only ADMIN's role holds: row 15b) gets 404 with the code on GET,
PUT, actions and DELETE of the record, which stays unchanged, and that tenant's lists never show it (TEN-13). With
`{{tenancy}}` = `row`, also tests.md row 15c (a native query of the entity, and the catalogue guard run
with the new table).

## Step 7: Build and test
Run `{{buildCommand}}`, the full gate command (`verify`: every test, ArchUnit, the coverage minimum and
static analysis with security rules, house-review CFG-11), never the tests alone: green means that command,
at every commit a run makes (one per entity: each is built before it is committed). Its output, when kept, goes
under `target/` only (`{{buildCommand}} > target/crud-gen-verify.log 2>&1`), which the build owns and git ignores:
never a new file in the project tree (a `build.log` at the root lands in the commit). Fix every compile error,
test failure and gate failure in the generated code; never report done on a red build. Generated code passes
the gates with **no new entry in the exclude file but the two kinds below, and no lowered minimum**: a SpotBugs
finding in a file that follows the golden files is a reference bug, fixed in the reference (the precedence above), never excluded in
the project; a coverage drop means a test row the entity lacks (`references/tests.md`). Records copy their
collections in a compact constructor as the golden ones do (`TodoResponse`: `List.copyOf`; `TodoCreateRequest`:
`Collections.unmodifiableSet`, null-safe, so validation still sees a null). **The only entries generated code
brings:** a method of the generated entity that points a JPA relation at its argument (golden `Todo.moveTo`)
gets its `<Match>` in the exclude file, generated with the entity: its class, that method and
`EI_EXPOSE_REP2`, with the reason "a JPA relation holds the managed entity; it can't be a copy"; and each
generated controller gets its `SPRING_ENDPOINT` `<Match>` (its class and that pattern), under the file's
`SPRING_ENDPOINT` comment, whose reason covers it (golden: the example's controllers). Any other finding in
generated code is a reference bug. **The red proof of a guard is the reference's**: each golden guard (a race, a lock, an
index, a scope) was proven red when it was written. The report names, for each generated guard test, the
golden test it mirrors, so a reviewer checks the shape against a proven one; a claim of a red run that left
no artifact is never made.

## Step 8: Self-review (house-review)
Run house-review on the generated and changed files: `/house-review uncommitted` before the commit,
or `/house-review commit <sha>` for a commit the run made (a range `<a>..<b>` for several), without
`--focus` (with `--unattended` in an unattended run): it loads its rules through its gates like any diff review (house-review Step 3). Either
way the report is written to `reportDir` as `<date>-<entity>.md` (house-review Step 6), so every
entity of a run leaves one. **The goal is zero findings.** A finding in
code that follows the golden files means the reference or this skill is wrong: fix the generated
file, then offer the skills fix (the precedence above). **A run of several entities** reviews each entity as soon as it is
generated and green (its own files and the shared files it touched), not all of them at the end:
findings in the first shape the next ones. When the review is delegated to a separate reviewer, the
checklist's review item stays open until that reviewer's report is clean.

### Final checklist
- [ ] No `{{…}}` left in the generated files.
- [ ] Every file written with Write or Edit; Bash ran only the build, git and `bootstrap-copy.sh` (*Tools*).
- [ ] Each changeset completed in place lies above the review profile's `shippedRelease` (Step 2, *Shipped*); a
      shipped one got a new changeset instead.
- [ ] Unattended: every assumption marked **assumed** in *Decisions per entity* and listed in the report; no
      question asked.
- [ ] The aggregate's row in `AggregateArchTest.AGGREGATES` **and** in both profiles' `aggregates`.
- [ ] The list's consumer and kind in both profiles (`cursorLists` or `offsetLists`, Step 3).
- [ ] A changed error shape or pointer (a cross-field error moved to its end field, a new code on an
      existing call): an entry, marked *breaking*, in each changelog the review profile's
      `publishedContracts` names (a UI, another service; ARCH-13, REST-20).
- [ ] A shared root: writes carry a permission the writing roles alone hold, personal-data reads decided, both in
      the profiles (Step 1).
- [ ] Permissions asked, never guessed (Step 1): the actions, reach, roles, area and prerequisites in *Decisions per
      entity*; the constants in `Permission` and each role's lines in `Role`; every handler `@Requires`/`@SignedIn`/
      `@Public`; an owned entity's `<Entity>Policy` and "own" in one shape (the owner's id in every query and no
      list scope, or a list scope beside loads by id that ask the policy; AUTHZ-05); `RoleTest`,
      `PermissionTest` and `EndpointContractIntegrationTest` green. With `authorization: roles-piece`, the grant
      changeset for the built-in roles the developer named (`ADMIN` among them), a new built-in role's seeding
      changeset, and `BuiltInRolesIntegrationTest` green. A detail several reaches read `@Requires` each reach's READ,
      a list per reach (AUTHZ-03, AUTHZ-05); each `allowedActions` flag names its endpoint's permission (AUTHZ-07).
- [ ] Each POST's REL-11 verdict in *Decisions per entity* (refused twice by a key or version; or none of
      money, outside effects, counted things, another person, legal weight; or the open item reported).
- [ ] **The test plan** (Step 6): every applicable tests.md row, per variant, against its method, each line ticked
      against the written test after the green build, and the ticked plan in the report. The rows themselves stay in
      tests.md; this list doesn't repeat them.
- [ ] Every absence assertion (a stranger's empty `?ids=`, a row left out) follows, in the same test, the same
      call showing the record to its party (TEST-16); every 400 a service raises about a request field carries its
      `errors` pointer (`#/approverId`, ERR-07).
- [ ] A convention changed during the run (what a role holds, what a permission means): the roles'
      grants and the profiles say the new rule, and no comment generated earlier still says the old one
      (grep them).
- [ ] A text key a cursor carries: `app.limits.text.cursor` holds it and its key is in
      `LimitsIntegrationTest.CURSOR_TEXT_KEYS` (4.2).
- [ ] Asked, and recorded: each picked parent's dropdown and Step 1's *Ask, don't guess* list (Step 1),
      whether a terminal status freezes the record (Step 3), a unique code's shape (Step 2).
- [ ] A same-module parent's service changed (4.8): listed among the changed shared files.
- [ ] A party a write names to act: its `NamedParty` constant with the owner's answer to "may the owner name
      themselves?" (`Owner`, recorded), the check after `requireUser` (409 `<ENTITY>_<PARTY>_INELIGIBLE`) and, for
      `NEVER_NAMED`, the 400 `<ENTITY>_<PARTY>_IS_OWNER` before it, its picker the users dropdown (no dropdown of the
      module's own; it leaves the caller out for `NEVER_NAMED`), the stale party's reassignment, its docs by the order of
      the checks (4.9) (4.3, AUTHZ-04, REST-13).
- [ ] A state the owner releases (a draft): from which state each other reach sees the record, asked and recorded
      (unattended: from the release on, assumed), one condition in the reach's list scope and the policy's `read`
      (AUTHZ-05), and every runbook line about that reach in the recorded answer's words.
- [ ] Each flag and its write decide the caller's permission from the request alone (`@Requires`,
      `CurrentUser.holds`): no write re-reads the caller's own permission from the database (AUTHZ-07). The status
      enum carries no method the code doesn't call (SMELL-18). Each decimal stored at its column's scale (JAVA-15).
- [ ] Per offered filter, "index this filter?" asked and recorded (unattended: the rules' answer, else no index,
      assumed), a scope's fixed condition among them (4.1); a range filter offered under its own order only.
- [ ] A changeset that backfills existing rows, once the project has a release in production: row 30 on
      the upgraded database (until the first release the upgrade path is inert: record it; tests.md, *Upgrading a
      used database*).
- [ ] A review-profile Conventions row only for a departure from a house rule, with the owner's reason;
      a choice no rule forbids goes in the crud profile's *Decisions per entity*.
- [ ] With the session version: every generated write that changes access bumps it in its transaction
      and has its 401-then-refresh test (4.8, SEC-07); with the roles piece, a write of roles or grants doesn't.
- [ ] Retention asked (Step 1): accumulating rows have their purge job, index, settings and tests (4.12), or the
      review profile's `retentionExceptions` names the table with the owner's reason.
- [ ] Every setting the run added is typed by what it means (CFG-12: a `Duration`, `Period`, `DataSize`, `URI`, `Cron`
      or enum, bounded in its record; the unit in the yml value, never in the key), and
      `ConventionArchTest.settingsAreTypedByWhatTheyMean` is green.
- [ ] `{{buildCommand}}` green at each commit, gates included (no exclude entry but the relation method's and the
      controllers' `SPRING_ENDPOINT`, no lowered minimum); the build's output, if kept, under `target/`, no new
      file in the tree but the entity's own (Step 7);
      each guard test names the golden test it mirrors (Step 7).
- [ ] house-review clean (Step 8).
