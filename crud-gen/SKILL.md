---
name: crud-gen
description: Generate full layered CRUD for a JPA entity in any Spring Boot project, following the house pattern so the result passes house-review with no findings. Generates the API interface with OpenAPI docs, controller, service, mapper, repository with a Criteria impl, record DTOs, filter, ErrorCodes and messages, and the integration test class, with optional dropdown and media endpoints. Pre-flights the entity and its migration. `/crud-gen bootstrap` sets up the shared house infrastructure once per project (error contract, wrappers, paging, logging, config baseline, ArchUnit). Project specifics come from .claude/crud-profile.md, discovered from the codebase on first use.
argument-hint: "<path-to-Entity.java> | bootstrap"
allowed-tools: [Read, Write, Edit, Bash, Glob, Grep, AskUserQuestion]
---

# CRUD Generator

Target: **$ARGUMENTS**

- `bootstrap`: set up the shared house infrastructure once. Read
  `~/.claude/skills/crud-gen/references/bootstrap.md` and follow it; skip the rest of this file.
- a path to an entity: generate its CRUD (Steps 0–8 below).

## The goal: generated code passes house-review

crud-gen and house-review share one rule set. **The house-review references are the source of
truth**, and this skill follows them:

| Reference | Used for |
|---|---|
| `~/.claude/skills/house-review/references/rest-api.md` | Paths, verbs, status codes, validation, OpenAPI on API interfaces |
| `…/error-contract.md` | ErrorCode, mapper, AppException, messages, ProblemDetail |
| `…/persistence.md` | Transactions, ids, versions, deletes, indexes, column sizes |
| `…/java.md`, `…/code-smells.md` | Java conventions (no `var`, text blocks, streams, Optional, records, visibility) |
| `…/lenses.md` (TEST-xx) | The tests every endpoint ships with |
| `…/architecture.md` | Module boundaries (`moduleContracts`) |

Read the parts each step cites. When this file and a reference disagree, **the reference wins**;
report the disagreement so this file gets fixed. Step 8 runs house-review on the generated files,
and it must come back clean.

The **pattern** is fixed across projects. Everything that differs is a **profile key**, written
`{{key}}`.

---

## Step 0: Load the project profile

Look for `.claude/crud-profile.md` at the project root. If it exists, read it: profile values and
its *Deviations* take precedence over the defaults here. Also read `.claude/review-profile.md` if
it exists; its conventions apply to generated code too.

If it doesn't exist, build it before generating anything:
1. Copy `~/.claude/skills/crud-gen/profile-template.md`.
2. Fill each key from the codebase; don't guess:
   - the build file: metamodel processor, commons-collections4, Lombok, ArchUnit;
   - existing controllers, services and repositories;
   - the error contract types (`ErrorCode`, `AppException`, the mapper, the messages file);
   - wrappers and paging types: `ObjectWrapper`, `ListWrapper` (bounded lists),
     `PaginationWrapper` + `PageInfo` (paginated lists), `PageFilter`, `PageRequests`;
   - the audit base class;
   - custom validators;
   - the package layout and modules;
   - the integration test base class and its helpers;
   - the migrations directory.
3. **If the house infrastructure is missing** (no ErrorCode/AppException contract, no
   `PaginationWrapper` / paging filter, no test base), stop and offer `/crud-gen bootstrap` first. Generated code depends on
   it.
4. Keys with no equivalent: `none` (see *Fallbacks* in the template).
5. Write the profile, show a short summary, and get confirmation **once**.

---

## Step 1: Read and analyse the entity

Extract:
- the simple class name;
- all fields with their JPA annotations (`nullable`, `updatable`, `unique`, `length`,
  `columnDefinition`);
- the FK relations (`@ManyToOne` / `@OneToOne`: field, target, `updatable`, `unique`);
- the module (Step 1.5);
- the dropdown annotation (if the profile has one);
- the media collection (if the profile has media).

Derive:
- `EntityName` / `entityName`;
- `url-path`: kebab-case plural;
- `ERROR_PREFIX`: UPPER_SNAKE entity name, e.g. `COURSE_QUICK_LINK`.

`ID` means `{{idType}}`.

### Step 1.5: Module
If `{{modules}}` is set, every generated class goes under `{{appPackage}}` with `{module}`
substituted. Cross-module rules come from `{{moduleRules}}` and `{{moduleContracts}}`
(architecture.md ARCH-04/05/06):
- import a parent from **its own** module;
- never use another module's repository (mappers may only use `getReferenceById` when
  `{{repositoryAccess}}` allows it);
- in `api-facades` mode, reach other modules only through their `api` contracts.

---

## Step 2: Pre-flight the entity and its migration

Check the entity and the migrations against the house rules **before** generating:

| Check | Rule |
|---|---|
| Id is time-ordered per `{{idStrategy}}` (UUIDv7 via Hibernate's `UuidVersion7Strategy`, or TSID), assigned in the application | DATA-09 |
| Extends `{{AuditBase}}` (audit columns + `@Version`) | DATA-06 |
| Lombok: class `@Getter` + `@SuperBuilder`; `@Setter` only on freely editable fields; no `@Data`, no `@EqualsAndHashCode` / `@ToString` over relations | JAVA-16 |
| Relations are `@ManyToOne(fetch = LAZY)`; collections exposed read-only with `add…` / `remove…` | JAVA-16 |
| Long text is `columnDefinition = "text"`, never `@Lob`; string columns have a `length` matching the planned `@Size` | DATA-12 |
| No soft-delete flag (`deleted`, `deleted_at`); statuses are enums | DATA-07 |
| A changeset exists for the table, with FK constraints `fk_<table>_<column>` | DATA-13 |
| **An index on every FK column and every filter / sort column** (Step 4.1), plus unique constraints for unique fields | DATA-10, DATA-05 |
| The sequence exists if the entity has a display number | DATA-14 |

For each failure:
1. Report it.
2. Offer the fix with **AskUserQuestion**:
   - the entity change;
   - a **new** changeset (next free number, `--rollback`, appended to the master file; never
     edit an applied one).
3. Apply it on approval, then generate.

If the user declines, generate anyway and list the open items in the final report: they will
be house-review findings.

---

## Step 3: Decide the variant

- **Variant A: list CRUD** (no FK with `unique = true`): a paged list endpoint plus
  get/create/update/delete.
- **Variant B: one per parent** (an FK with `unique = true`): a nested singleton resource under
  its parent, `{{urlPrefix}}/<parents>/{parentId}/<child>` (REST-02). GET returns the wrapper with
  a null object when it doesn't exist yet; PUT creates or replaces it.

Child entities in Variant A that only make sense under a parent are also nested
(`/<parents>/{parentId}/<children>`), not filtered by `?parentId=`.

---

## Step 4: Generate files

Paths are relative to `{{sourceRoot}}`; `<app>` = `{{appPackage}}`.

### Coding conventions (all generated code)
- `Objects.isNull` / `Objects.nonNull`; `CollectionUtils.isEmpty` / `isNotEmpty`;
  enums compared with `==`.
- **No `var`**: explicit types everywhere, tests included.
- **Text blocks** (`"""`) for every multi-line string (OpenAPI descriptions, JSON fixtures).
- **Streams for pure pipelines** (`.map(mapper::toResponse).toList()`). Use a `for` loop when the
  loop body has side effects. Complex steps are named methods passed as method references, never
  multi-line lambdas.
- `Optional`: return types only. Use `orElseThrow(AppException.supply(...))`, `map` and
  `ifPresentOrElse`.
- Lombok only where it is used: `@RequiredArgsConstructor` only with final fields, and `@Slf4j`
  only when the class logs.
- Visibility: controllers and `*RepositoryImpl` package-private; fields `private final`.
- Comments only for *why*. No "what" comments.
- Variables declared next to their first use.

### 4.1 Filter (Variant A)
**Path:** `<app>/dto/request/EntityNameFilter.java`. A class extending `{{PageFilter}}` (page +
size, capped by `{{PageRequests}}`); records can't extend it.
- One field per filterable attribute: FK ids, enums, booleans, and a `query` String (free-text
  search) with `@Size(max = 100)`. The same parameter name as in dropdowns, so the frontend has
  one search convention.
- Enums are typed as the enum, never String (REST-08).
- A `sort` field typed as a generated enum `EntityNameSort` (e.g. `NEWEST`, `NAME`), defaulting
  to the first constant. A bad value is a 400, never a silent default.
- `requireXPredicate()` per optional field.
- Lists in `{{cursorLists}}` use cursor paging (`{{CursorFilter}}`) instead of page/size.

### 4.2 Payload (record)
**Path:** `<app>/dto/request/EntityNamePayload.java`. A **record**, with validation per this
table (REST-09/10):

| Field | Annotations |
|---|---|
| short String, required | `@NotBlank @Size(max = <column length>)` + `{{NotXss}}` |
| short String, optional | `@Size(max = <column length>)` + `{{NotXss}}` |
| code / identifier field in `{{noEmojiFields}}` | add `{{NoEmoji}}` (**only** these fields) |
| URL | `@NotBlank @Size(max = 2048) @URL` (or a pattern) + `{{NotXss}}` |
| long text (`text` column) | `@Size(max = <reasonable cap, e.g. 20000>)` + `{{NotXss}}`. **Every String has a `@Size`** |
| FK id | `@NotNull` (+ `@Positive` for numeric ids) |
| number | `@NotNull` + `@Min` / `@Max` or `@Positive` with real bounds |
| Boolean | `@NotNull` |
| enum | `@NotNull` if the column is non-null |
| collection | `@NotEmpty @Size(max = N)` with element constraints |
| `version` (**update payload only**) | `@NotNull Long version`: the version the client saw (DATA-06) |

Create and update share one record only if they have identical fields. Otherwise generate
`EntityNameCreatePayload` and `EntityNameUpdatePayload` (the update one carries `version`).
`@Schema` on every component (description, example), using text blocks for long descriptions.

### 4.3 Response (record)
**Path:** `<app>/dto/response/EntityNameResponse.java`. A flat **record**:
- `id`;
- all fields, with FKs as `parentId` (never the entity);
- ending with `{{auditFields}}` (including `version`).

`@Schema` on every component.

### 4.4 ErrorCodes and messages (error-contract.md)
For this entity, add:
- **`{{ErrorCode}}`**: `ERROR_PREFIX_NOT_FOUND`, plus `ERROR_PREFIX_<FIELD>_TAKEN` for each
  unique field, plus `ERROR_PREFIX_IN_USE` if other tables reference it (delete refused by an
  FK). Grouped under a `// ── EntityName ──` comment, with Javadoc if it lives in an `api/`
  package.
- **`{{ErrorCodeMapper}}`**: `NOT_FOUND` → 404, `*_TAKEN` → 409, `IN_USE` → 409.
- **`{{messagesFile}}`** (every locale), for example:
  `ERROR_PREFIX_NOT_FOUND=No entity name was found with id {0}.`
- **The handler's constraint-name map** (`{{constraintCodeMap}}`): the unique constraint names to
  `*_TAKEN`, and the FK constraint names pointing **to** this table to `ERROR_PREFIX_IN_USE`. The
  database enforces uniqueness; never check-then-insert (DATA-05).

### 4.5 Mapper
**Path:** `<app>/mapper/EntityNameMapper.java`. A `public` `@Component` (it lives in another
package from the service), hand-written, mapping only: no business rules, no flag arguments
(JAVA-17).
- `toEntity(payload)`: all fields. FKs via `parentRepository.getReferenceById(id)` only if
  `{{repositoryAccess}}` allows mappers; otherwise the service passes the resolved parent in.
- `toResponse(entity)`: every field plus every audit field. FK → `entity.getParent().getId()`
  (doesn't initialise the proxy).
- `update(entity, payload)`: only mutable fields. Skip `updatable = false` FKs, audit fields and
  `version`.
- `toResponses(list)`: `entities.stream().map(this::toResponse).toList()`, **only if** a list
  endpoint uses it.

### 4.6 Repository
- `EntityNameRepository extends {{BaseRepository}}<EntityName, ID>, CustomEntityNameRepository`
  (Variant A).
- Variant B: `Optional<EntityName> findByParent_Id(ID parentId)`.

### 4.7 Custom repository and Criteria impl (Variant A)
- `CustomEntityNameRepository.findByFilter(EntityNameFilter filter, Pageable pageable)`
  returns `Page<EntityName>`.
- `EntityNameRepositoryImpl` is **package-private**, uses `EntityManager`, the Criteria API and
  the metamodel (`EntityName_`), through `{{CriteriaPages}}` when the project has it.
- Predicates come from the `requireXPredicate()` methods; `query` uses
  `like(lower(...), lowerCaseContainsQuery())`.
- **Order by the sort enum, then `id`** as the tie-breaker (DATA-10).
- The count runs only when the page is full (`PageableExecutionUtils`) (DATA-11).
- One query per page. Lazy relations rely on `default_batch_fetch_size`; never fetch-join a
  collection with paging (DATA-26).

### 4.8 Service
**Path:** `<app>/service/EntityNameService.java`.
- `@Service @RequiredArgsConstructor @Transactional(readOnly = true)`.
- Every write method has its own `@Transactional`.
- Services return wrappers: `{{ObjectWrapper}}` for one object, `{{PaginationWrapper}}` for a
  paginated list (explicit `pageInfo`), `{{ListWrapper}}` only for a list bounded by nature. A
  paged list never returns `{{ListWrapper}}`. Build-first when extra details may be attached.
- Expose `getEntityName(ID id)` (the entity, or 404) for other modules, or an `api` contract
  method in `api-facades` mode.

```java
@Transactional
public {{ObjectWrapper}}<EntityNameResponse> create(EntityNamePayload payload) {
    EntityName saved = repository.saveAndFlush(mapper.toEntity(payload));
    return {{objectFactory}}(mapper.toResponse(saved));
}
```
- The id is application-assigned and `@Version` is null, so `saveAndFlush` **persists** (no
  merge). The flush makes the response carry `version` and the audit fields, and surfaces a
  unique violation here (the handler maps it to `*_TAKEN`).

```java
@Transactional
public {{ObjectWrapper}}<EntityNameResponse> update(ID id, EntityNameUpdatePayload payload) {
    EntityName entity = getEditableEntityName(id, payload.version());
    mapper.update(entity, payload);
    repository.flush();
    return {{objectFactory}}(mapper.toResponse(entity));
}

private EntityName getEditableEntityName(ID id, Long expectedVersion) {
    EntityName entity = getEntityName(id);
    if (!Objects.equals(entity.getVersion(), expectedVersion)) {
        throw AppException.of({{staleErrorCode}}, id);
    }
    return entity;
}
```
- Update changes the **managed** entity and flushes. No `save` / `merge` on a managed entity
  (DATA-25).

```java
public EntityName getEntityName(ID id) {
    return repository.findById(id).orElseThrow(AppException.supply(ErrorCode.ERROR_PREFIX_NOT_FOUND, id));
}

public {{ObjectWrapper}}<EntityNameResponse> findById(ID id) {
    return {{objectFactory}}(mapper.toResponse(getEntityName(id)));
}

public {{PaginationWrapper}}<EntityNameResponse> findByFilter(EntityNameFilter filter) {
    Page<EntityName> page = repository.findByFilter(filter, filter.toPageRequest());
    return {{pagedListFactory}}(page.map(mapper::toResponse));
}

@Transactional
public void delete(ID id) {
    repository.delete(getEntityName(id));
    repository.flush();
}
```
- Delete: 404 when the record is missing; the flush surfaces an FK violation here (answered 409
  `ERROR_PREFIX_IN_USE`).
- **Ownership** (when the entity is user-owned: `{{ownerField}}` is set): every read and write
  loads through `getOwnedEntityName(id, currentUserId)`, which answers **404** for another user's
  record. If the caller may *see* it but not change it, answer **403** with a specific code
  (REST-16).
- No remote call inside any transaction. If the entity needs one (media storage checks, external
  validation), orchestrate with `NOT_SUPPORTED` / `TransactionTemplate` (DATA-01; media.md).

### 4.9 API interface (OpenAPI)
**Path:** `<app>/controller/api/EntityNameApi.java`. A public interface carrying all
documentation (REST-20):
- `@Tag(name = "EntityName")`.
- Per method:
  - `@Operation(summary, description)`, with text blocks for long descriptions;
  - an `@ApiResponse` for **every** status it can return: 200 / 201 / 204, plus 400
    `VALIDATION_FAILED`, 401, 403 (role), 404 `ERROR_PREFIX_NOT_FOUND`, 409
    (`*_TAKEN`, `OPTIMISTIC_LOCK_CONFLICT`, `ERROR_PREFIX_IN_USE`) and 503 (transient database
    failure). Each error lists its `errorCode` values and refers to `ProblemDetail`.
- The method signatures carry the same Spring MVC parameter annotations as the controller.
- The list method returns `{{PaginationWrapper}}<EntityNameResponse>`, so the OpenAPI schema shows
  `response` and `pageInfo` (number, size, totalElements, totalPages, hasNext, hasPrevious). Its
  description names the page parameters (`page`, `size` capped at 100, `sort`) and that
  `pageInfo.size` is the size actually used.

### 4.10 Controller
**Path:** `<app>/controller/EntityNameController.java`. `@RestController
@RequiredArgsConstructor`; **package-private**; `implements EntityNameApi`; the full path on every
method; `{{controllerSecurity}}` roles; thin (REST-21); no `@Transactional`.

```java
@RestController
@RequiredArgsConstructor
{{controllerSecurity}}
class EntityNameController implements EntityNameApi {
    private final EntityNameService service;

    @GetMapping("{{urlPrefix}}/entity-names")
    @Override
    public {{PaginationWrapper}}<EntityNameResponse> all(@Valid EntityNameFilter filter) {
        return service.findByFilter(filter);
    }

    @GetMapping("{{urlPrefix}}/entity-names/{id}")
    @Override
    public {{ObjectWrapper}}<EntityNameResponse> get(@PathVariable ID id) {
        return service.findById(id);
    }

    @PostMapping("{{urlPrefix}}/entity-names")
    @ResponseStatus(HttpStatus.CREATED)
    @Override
    public {{ObjectWrapper}}<EntityNameResponse> create(@RequestBody @Valid EntityNamePayload payload) {
        return service.create(payload);
    }

    @PutMapping("{{urlPrefix}}/entity-names/{id}")
    @Override
    public {{ObjectWrapper}}<EntityNameResponse> update(@PathVariable ID id,
                                                       @RequestBody @Valid EntityNameUpdatePayload payload) {
        return service.update(id, payload);
    }

    @DeleteMapping("{{urlPrefix}}/entity-names/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Override
    public void delete(@PathVariable ID id) {
        service.delete(id);
    }
}
```
Interface methods are public, so the implementing methods are `public` too. The *class* stays
package-private.

### 4.11 Dropdown (only if the entity carries `{{dropdownAnnotation}}`)
Read `references/dropdown.md`: the house dropdown standard.
- `GET <resource>/dropdown` with `DropdownFilter` (`status`, `sortDirection`, `limit`, `query`),
  subclassed only for the annotation's mandatory and optional filters;
- defaults from the entity's `@DropdownCandidate`;
- `ListWrapper<DropdownItem>` built by the service;
- a projection query.

When the pre-flight finds a resource that other screens pick from (another entity references it
by FK) but no `@DropdownCandidate`, ask whether to add one.

---

## Step 5: Media (only with a media collection and a media profile)
Read `references/media.md`.

---

## Step 6: Integration tests (always)
Read `references/tests.md` and generate `EntityNameIntegrationTest` (plus a small unit test for
non-trivial mapper or service logic). Every endpoint ships with its tests.

---

## Step 7: Build and test
Run the profile's `buildCommand`. Fix any compile error or test failure in the generated code
before going on. Never report done on a red build.

---

## Step 8: Self-review (house-review)
Run house-review on the generated and changed files (mode: path or uncommitted, scoped to them).
- **The goal is zero findings.** Fix every finding in the generated code.
- A finding that comes from this generator's own template means **this skill is wrong**:
  1. fix the generated file;
  2. report the template defect to the user, offering to fix `~/.claude/skills/crud-gen` so the
     next run is clean.
- Findings in pre-existing code are listed, not fixed, unless the user asks.

### Final checklist
- [ ] Profile loaded; no `{{…}}` left in any file.
- [ ] Pre-flight items fixed, or listed as declined.
- [ ] Payload and Response are records, with `@Schema`; every String has `@Size`; `@NoEmoji` only
      on `{{noEmojiFields}}`; the update payload carries `version`.
- [ ] Filter has typed enums and a sort enum; the list is paged and capped, sorted with an id
      tie-breaker, with the count skipped on a partial page, and returned as
      `{{PaginationWrapper}}`.
- [ ] ErrorCodes, mapper entries, messages and constraint mappings added; no hand-written
      "not found" strings.
- [ ] Service: class `readOnly`, `@Transactional` writes, update flushes a managed entity (no
      merge), delete loads (404) then flushes; ownership → 404; no remote call in a transaction.
- [ ] API interface documents every status and errorCode; the controller is package-private,
      `implements` it, uses 201 / 204, holds no logic and no `@Transactional`.
- [ ] RepositoryImpl is package-private and uses the metamodel.
- [ ] No `var`; text blocks; Lombok only where used; no unused methods.
- [ ] Tests generated per `references/tests.md`; build green.
- [ ] house-review on the generated files: clean.
