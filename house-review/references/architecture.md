# Architecture rules (ARCH-xx)

**Severity principle:** never downgrade a structural problem because the code
still works. Structural findings are **High** (they block merge) unless stated otherwise.

**Module contracts are a profile choice** (`{{moduleContracts}}`):
- `api-facades` (modulith style, the user's preference when a project may extract services):
  entities never leave their module; other modules use `<module>.api` records and interfaces;
- `service-entity-methods`: services expose entity getters to other modules (fine for a
  monolith that won't be split).

The rules below say which mode they apply to.

---

### ARCH-01: Every module has a one-line owner statement
- **Check:** each module owns one concept you can state in one line (`{{modules}}`), and its
  code serves that concept.
- **Detect:** write the line. If you can't, or a module's classes serve another module's
  concept, that's a finding ("If you cannot write these cleanly, the module map is already
  weak").
- **Not a finding when:** `config` / `common`, whose owner statement is "infrastructure".
- **Severity:** Medium (High when it causes ARCH-09).

### ARCH-02: No cycles between modules
- **Check:** the module dependency graph is acyclic.
- **Fix:** choose one dependency direction (a stable read contract, or move the workflow). Never
  hide a cycle behind a new facade without changing who owns what.
- **Detect:** ArchUnit `slices().matching("<root>.(*)..").should().beFreeOfCycles()`; otherwise
  grep imports per module pair.
- **Severity:** High; Medium for cycles between subpackages inside one module.

### ARCH-03: Layer direction
- **Check:** controller → service → repository → entity. Nothing calls upward:
  - services are reusable outside HTTP: they never return `ResponseEntity` and never take
    `HttpServletRequest` / `HttpServletResponse` (outbound HTTP-client types such as `RestClient`
    are fine). Validation and role checks move into them when a second entry point arrives
    (REST-09);
  - repositories never call services;
  - entities depend on no service, repository or controller, and on no web or HTTP type
    (`org.springframework.http..`, `..web..`: an entity returning `HttpStatus` is HTTP-aware and
    wrong for any other adapter). Spring Data's auditing annotations are fine.

  Who may reach repositories comes from `{{repositoryAccess}}` (default `aggregate-service`: only
  the aggregate's own service, ARCH-15; mappers receive resolved entities).
- **Enforce:** ArchUnit, `LayerArchTest` in the reference (entities use no web types; services
  return no `ResponseEntity` and take no servlet request).
- **Severity:** Medium (High for entity → service).

### ARCH-04: Module internals stay internal
- **Check:** another module never uses this module's repositories or internal helpers, nor its
  entities in `api-facades` mode. It goes through the owner's service or `api` contract.
- **A parent in another module** (`api-facades` mode) is a UUID column with a database foreign key
  (no entity relation), validated through the owner's facade: `<module>.api.<Things>`, an
  interface the owner's service implements, returning a view record (`TodoView`), with:
  `get<Owned><Thing>View` (not locked: a child written while the parent is deleted is refused by the
  foreign key, DATA-29, DATA-33); `get<Owned><Thing>ViewForUpdate`, `@Transactional(propagation =
  MANDATORY)` over the owner's `findForUpdate…`, only for a write that checks the parent's state (a
  plan only for an open todo), whose lock then names that rule (DATA-29); `find<Owned><Thing>View`
  (`Optional`) for a record read just before it, where an empty one is the caller's own 404; and a batch
  lookup (`getOwnedTodoViews(ids, ownerId)`) for a list page, one statement per batch. The view carries
  the parent's state as a value the owner computes (`TodoView.open`), never a `domain` enum (or the enum
  moves to `api`, as `MediaFileStatus`). Reference: `todo.api.Todos`, used by
  `planning.service.PlanService` (`create` locks, `findById` and the list don't). The view is a module contract, never a
  response body: the consumer maps it into a record of its own (ARCH-06). A module with no facade yet (the sign-in piece's `user`) gets one
  in the same change (with tenancy it has one: the overlay's `user.api.Members`, tenant-scoped), never a repository call or an unvalidated id (an unknown id would surface as a
  foreign-key 409 with the wrong code).
- **The foreign key's mapping belongs to the module that owns the constraint** (whose changeset
  declares it), even when the code it answers is the parent module's (`fk_plans_todo_id_owner_id` →
  `TODO_IN_USE` for the todo's delete, `TODO_GONE` for a plan's insert after it, in
  `PlanningConstraintErrorCodes`; kept although the plan's create locks the todo and can't trip it,
  so a later unlocked write answers 404, not 500).
- **Severity:** High for repositories; Medium for entities and helpers.
- **Not a finding when:** `service-entity-methods` mode allows entity hand-out through service
  methods.

### ARCH-05: `api/` packages are pure contracts (`api-facades` mode)
- **Check:** `<module>.api` depends only on shared code, the JDK, Jakarta, Spring contracts and
  Lombok. No service, repository, entity, mapper, HTTP DTO, controller or config. (`controller/api`
  OpenAPI interfaces are excluded: they document HTTP.) Its types are records, interfaces, enums, and
  constants holders (a final class of `static final` fields behind a private constructor: the
  compile-time constant an annotation needs, such as a pattern, which no record or enum can hold);
  `ModuleBoundaryArchTest.apiPackagesHoldOnlyContractTypes`.
- **Severity:** High: "the facade is an illusion: renaming an internal type forces a contract
  change across every consuming module".

### ARCH-06: No HTTP-shaped objects across modules
- **Check:** request and response DTOs and mappers never cross a module boundary. Internal
  exchange uses module-owned records, query views, or ids plus a lookup contract. Response
  assembly happens in the module's own controller.
- **Why (the owner's four reasons):** a mapper that calls another module's mapper, or builds
  another aggregate's response:
  1. cascades change: a change to that response ripples into every response that embeds it;
  2. bloats responses: every field added there silently grows this response too;
  3. bypasses the owning service's cache;
  4. bypasses the owning service's security (scoping, ownership).

  Ask the owner's service for what you need instead (ARCH-15).
- **A facade's view is not a response field either.** `TodoView` or `MediaFileView` in a
  `*Response` makes the contract a wire format: a field added for another consumer reshapes this API
  (reason 1). The consuming module maps the view into a response record of its own
  (`PlanTodoResponse`, `TodoAttachmentResponse`), as DATA-27 keeps the stored, wire and contract
  types apart. Enforced by `ModuleBoundaryArchTest.responsesCarryNoOtherModulesContract`.
- **Severity:** High (boundary contamination).

### ARCH-07: Events are contracts; core workflows are not choreographed
- **Check:**
  - integration events carry only contract types;
  - a core workflow's correctness never depends on listener timing (`@Async`, AFTER_COMMIT);
  - important side effects are recorded durably (an outbox or job row, REL-06).
- **Not a finding when:** a best-effort side effect, documented as such.
- **Severity:** High.

### ARCH-08: `shared` / `common` is small, boring, and depends on no feature
- **Check:** no outgoing dependency on any feature module. It holds only universal exceptions,
  minimal security abstractions, pagination primitives and stable technical utilities. A type
  used by only one module belongs in that module.
- **Not a finding when:** a cycle-breaking contract placed there on purpose, with a comment
  saying so.
- **Severity:** High for a feature dependency; Medium for feature code parked there.

### ARCH-09: State and its rules live with their owner
- **Check:** the module that stores a concept owns its invariants and transitions. No module
  writes another module's state directly, and no lifecycle rule is spread across unrelated
  services.
- **Not a finding when:** a deliberate orchestrating workflow that calls the owner's methods.
- **Severity:** High.

### ARCH-10: Optional wiring doesn't change core behaviour
- **Check:** business outcomes don't depend on whether a bean exists
  (`@Autowired(required = false)`, `ObjectProvider#getIfAvailable`, conditional beans that
  change results).
- **Not a finding when:** switching adapters of one external boundary, profile-gated per SEC-03.
- **Severity:** High.

### ARCH-11: Controllers are package-private adapters
- **Check:**
  - `@RestController` classes are not public;
  - no `@Transactional` on controllers (it holds a connection through deserialisation);
  - no dependency on entities or repositories;
  - entities never leave the service layer (DTOs are mandatory).
- **Severity:** Medium (High for `@Transactional` on a controller).

### ARCH-12: Escape hatches are declared
- **Check:** every place that deliberately steps outside an ambient safeguard carries a marker
  annotation, and a build rule demands it. Examples:
  - unscoped or bypassing queries;
  - `@Scheduled` jobs, which must declare a concurrency posture (a lock annotation, or
    `@MultiInstanceSafe("why")`: safe when every instance runs it at once) when `{{instances}}` is
    `multiple`. The house rule (`ConventionArchTest.scheduledJobsDeclareTheirConcurrencyPosture`) knows
    `@MultiInstanceSafe`; a project with a lock library adds its annotation there
    (`.orShould().beAnnotatedWith(SchedulerLock.class)`);
  - the tenant bypass, which is profile-optional.

  The user's reason: the defect "recurred at least six times… after this rule, it fails the
  build instead".
- **Severity:** High when a bypass is unmarked.

### ARCH-13: Published contracts change with their docs
- **Check:** a change to a non-OpenAPI contract in `{{publishedContracts}}` updates its schema
  or guide and a changelog entry (marked *additive* or *breaking*) in the same commit:
  - webhook payloads, headers, signing, retries;
  - events;
  - frontend changelog;
  - k6 scripts.

  Contract tests fail on drift.
- **Severity:** Medium (High for an unannounced breaking change).

### ARCH-14: Architecture-rule exemptions are deviations
- **Check:** every ArchUnit carve-out has a dated reason and a register entry, and exempts only the
  classes its reason names.
- **Severity:** Medium; High when the carve-out exempts classes its dated reason doesn't name.

### ARCH-15: Aggregates own their repositories; Manager services own cross-aggregate transactions
Within a module as well as across modules.
- **Check:**
  - **one service per aggregate**: the aggregate root's service (`{{aggregates}}`: root, owning
    service, member entities) is the only class outside the repository package that uses the
    repositories of the root **and of its member entities** (`OrderService` → `OrderRepository`,
    `OrderLineRepository`). It enforces the members' business rules, so a member entity has **no
    service of its own** and is reached through the root (`/orders/{orderId}/lines`);
  - **another aggregate is reached only through its root's service**, by its entity methods
    (`getOwnedTodoList`): they carry validation, ownership, locking and, later, caching and
    its invalidation. Using another aggregate's repository bypasses all of them, and so does a
    mapper building another aggregate's response (the four reasons in ARCH-06);
  - **a Manager (application) service** (`<UseCase>Manager`, in `service`) exists only when one
    request writes **several aggregates**: it owns that transaction boundary and calls the aggregate
    services, which join it; it uses no repository. A use case that writes one aggregate needs no
    Manager (SMELL-11, SMELL-12: no layer for the pattern's sake);
  - **read models may read another aggregate's data**, within the module, without its entity: a
    detail's parent through an entity graph, a list page's parent names in **a second statement** for the
    batch's ids (never a join in the keyset query: with tenancy it carries the tenant predicate twice and
    its plan turns on the cursor's position; crud-gen 4.3, reference `TodoService`'s list names); loading or writing another
    aggregate's entity goes through its service. **Across modules** (ARCH-04 forbids the join), a list
    scoped by another module's data (a manager's team) takes the ids from the owner's facade
    (`Employees.reportIds(managerId)`), **with a configured bound on their number enforced where the
    relationship is written** (DATA-11: a manager gets at most `max-direct-reports`), then
    `IN (...)`; its statement count is one more per facade lookup (tests.md row 21). The other way
    round, a list whose rows show another module's data (a plan's todo title) reads its page, then
    that data for the whole page in one facade call (reference: `PlanService.findByFilter` with
    `Todos.getOwnedTodoViews`), never one call per row;
  - **a Manager's shape** (reference: `TagMergeManager`): `@Transactional` on the Manager method; each
    aggregate service exposes a `MANDATORY` half that checks, locks, changes and flushes its own aggregate and
    returns the entity (an aggregate whose change must come last is split into a locking and a finishing
    half: `TagService.lockForMerge`, `finishMerge`); locks are taken in one documented order (no path takes
    them in reverse); the owning service of the request's resource builds the response; the controller calls
    the Manager. Tests: each aggregate's refusal rolls both back (a refusal before any write changes nothing;
    a failure after a half wrote rolls it back: `TagMergeIntegrationTest.aMergeFailingAfterItsTodoHalfRollsBackBoth`);
  - no interface for a single implementation (SMELL-11): services are concrete classes.
- **Detect:** a `*Repository` import in a service of another aggregate, a mapper or a Manager; a
  member entity with its own service or controller; a Manager that writes one aggregate; entity
  services calling each other in a cycle (the use case belongs in a Manager).
- **Enforce:** ArchUnit, from `{{aggregates}}` (reference: `AggregateArchTest`); an undeclared
  repository fails the rule, so every new aggregate is declared.
- **Severity:** High when a business rule, lock or ownership check is bypassed; otherwise Medium.

### ARCH-16: Bean cycles are fixed, never hidden
- **Check:**
  - `spring.main.allow-circular-references` is never enabled;
  - `@Lazy` on an injection point and `ObjectProvider<OwnBean>` are never used to get round a
    cycle between the application's own beans: the cycle stays, and the code behind a missing
    `ObjectProvider` bean is silently skipped.

  Fix the cycle, in this order: extract the shared part into a third bean both use (A → C ← B);
  publish an event from one side (ARCH-07); merge the two when neither exists without the other.
- **Detect:** `allow-circular-references`; `@Lazy` on a constructor parameter or field;
  `ObjectProvider<` of an application bean.
- **Not a finding when:** `ObjectProvider` for an optional adapter of an external boundary
  (ARCH-10's exception), or `@Lazy` on a bean that is expensive to create and has no cycle (say so
  in a comment).
- **Severity:** High.

### ARCH-17: State a later request depends on is shared
- **Check:** when `{{instances}}` is `multiple`, state that one request writes and a later request
  reads (a plan to confirm, a pending challenge, an allowance, a draft) lives in the database or
  Redis. Otherwise the flow is declared single-instance in the profile.
- **Why:** the next request lands on another instance and finds nothing, or a different value.
- **Detect:** a `Map` or local cache field written in one endpoint and read in another (PERF-05).
- **Severity:** High (Medium while the deployment is single-instance by decision).

---

## Enforcing with ArchUnit (house recommendation)

The house enforces structure at build time with **ArchUnit**
(`com.tngtech.archunit:archunit-junit5`, test scope). Spring Modulith is the alternative to
mention, but by default it treats every subpackage as internal, which clashes with the crud-gen
layout. A new dependency is still proposed to the user first (libraries-first rule).

Automatable, from the profile:

| Rule | ArchUnit |
|---|---|
| ARCH-02 | `slices().matching("<root>.(*)..").should().beFreeOfCycles()` |
| ARCH-03 | `layeredArchitecture().consideringAllDependencies()` from `{{layerPackages}}`; entities depend on no `org.springframework.http..` / `..web..`; services use no `ResponseEntity` or `HttpServletRequest` (reference: `LayerArchTest`) |
| ARCH-04 / ARCH-06 | one generic `ArchCondition` comparing the module segment of origin and target for `..repository..`, `..domain..`, `..dto.request..`, `..dto.response..`, `..mapper..` (no hand-listed modules) |
| ARCH-05 | `..<module>.api..` depends only on the allowed packages, excluding `..controller.api..` |
| ARCH-07 | event classes depend on no entity, repository or service |
| ARCH-08 | `<root>.common..` depends on no feature module |
| ARCH-11 | `@RestController` classes not public; no `@Transactional` methods in `..controller..`; no dependency on `..repository..` / `..domain..` |
| ARCH-12 | methods calling bypass API X carry annotation A; every `@Scheduled` method declares its posture |
| ARCH-15 | a `*Repository` is used only by its aggregate's service (`{{aggregates}}`) and its own repository package; an undeclared repository fails |
| ARCH-16 | no constructor parameter or field annotated `@Lazy` (the `ObjectProvider<OwnBean>` type argument and `allow-circular-references` stay review checks) |
| REST-25 | no `@PreAuthorize` whose value contains `hasRole(` outside `<root>.common.security..` (annotation values are in bytecode) |
| Naming (the reference checks the controller and repository names in `ConventionArchTest`) | `*Controller`, `*Repository`; `*RepositoryImpl` not public; package names lower-case; test classes end in `Test` (Surefire skips others silently) |
| Conventions in bytecode | no `@Lob` field; no `@Transactional` private method; no `Optional` field or parameter; no `@TransactionalEventListener` + `REQUIRES_NEW` without `@Async`; no `Executors.newFixedThreadPool` / `newCachedThreadPool`; no public member naming a package-private type (JAVA-27); no settings record component that is a number with its unit in its name or text with a grammar (CFG-12, the records a `@ConfigurationProperties` one holds included); no dependency on `java.util.logging` (ArchUnit's `NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING` sees a `Logger` field only, never a `Logger.getLogger(...)` call); `GeneralCodingRules` (no `System.out`, no field injection, no generic `RuntimeException` thrown) |

Not automatable (they live in source, not bytecode), so they stay review rules:
- `var`;
- text blocks;
- `== null` vs `Objects.isNull`;
- Lombok annotations;
- method and class length;
- stream side effects;
- comment quality;
- SMELL-27 and SMELL-28.

How the skill uses it:
1. The profile lists the existing ArchUnit classes (`{{archTests}}`) and their carve-outs, and
   the carve-outs go into the deviation register (ARCH-14).
2. Red and green rules in the build are handled as SKILL.md Step 2 says.
3. In Step 8, offer to generate
   `src/test/java/<root>/archunit/{Layer,ModuleBoundary,Aggregate,Convention}ArchTest.java` from the
   profile (the reference application's classes of those names), with:
   - `@AnalyzeClasses(packages = "<root>", importOptions = ImportOption.DoNotIncludeTests.class)`;
   - a `.because("<why>")` on every rule;
   - a dated comment on every carve-out.

   Also offer it when an automatable guideline finding recurs.
4. Use `FreezingArchRule` only when the developer chooses to freeze existing violations instead of
   fixing them first, and its stored violations go in the deviation register. The store is in the
   repository: `src/test/resources/archunit.properties` with `freeze.store.default.path` under
   `src/test/resources` and, once the first run has written it, `freeze.store.default.allowStoreCreation=false`,
   so a missing store fails the build instead of silently freezing every violation again.
5. A rule's scope change (narrowed to a package, a new accepted shape) is proven on fixture classes:
   what it must still refuse and what it now lets pass (reference `ArchRuleScopeTest`). A planted
   violation for a red run must be one the rule sees.
