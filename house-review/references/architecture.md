# Architecture rules (ARCH-xx)

Sources:
- DynamicForm `playbooks/low-level-architecture-review-playbook.md`;
- the ArchUnit tests (`LayerArchTest`, `ModuleLayerArchTest`, `ModuleBoundaryArchTest`,
  `NamingConventionArchTest`, `TenantBypassArchTest`);
- DynamicForm CLAUDE.md "Architecture Rules".

**Severity principle (the playbook):** never downgrade a structural problem because the code
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
  - services are reusable outside HTTP;
  - repositories never call services;
  - entities depend on no service, repository or controller.

  Who may reach repositories comes from `{{repositoryAccess}}` (news-pulse: service + mapper for
  `getReferenceById`).
- **Severity:** Medium (High for entity → service).

### ARCH-04: Module internals stay internal
- **Check:** another module never uses this module's repositories or internal helpers, nor its
  entities in `api-facades` mode. It goes through the owner's service or `api` contract.
- **Severity:** High for repositories; Medium for entities and helpers.
- **Not a finding when:** `service-entity-methods` mode allows entity hand-out through service
  methods.

### ARCH-05: `api/` packages are pure contracts (`api-facades` mode)
- **Check:** `<module>.api` depends only on shared code, the JDK, Jakarta, Spring contracts and
  Lombok. No service, repository, entity, mapper, HTTP DTO, controller or config. (`controller/api`
  OpenAPI interfaces are excluded: they document HTTP.)
- **Severity:** High: "the facade is an illusion: renaming an internal type forces a contract
  change across every consuming module".

### ARCH-06: No HTTP-shaped objects across modules
- **Check:** request and response DTOs and mappers never cross a module boundary. Internal
  exchange uses module-owned records, query views, or ids plus a lookup contract. Response
  assembly happens in the module's own controller.
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
    `multiple`;
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
- **Check:** every ArchUnit carve-out has a dated reason and a register entry, and is as narrow
  as its need ("the exemption had been far wider than the need").
- **Severity:** Medium; High when it has grown into a hole.

### ARCH-15: Aggregates own their repositories; Manager services own cross-aggregate transactions
Owner's rule (2026-11-13), within a module as well as across modules.
- **Check:**
  - **one service per aggregate**: the aggregate root's service (`{{aggregates}}`: root, owning
    service, member entities) is the only class outside the repository package that uses the
    repositories of the root **and of its member entities** (`FormService` → `FormRepository`,
    `FormFieldRepository`). It enforces the members' business rules, so a member entity has **no
    service of its own** and is reached through the root (`/forms/{formId}/fields`);
  - **another aggregate is reached only through its root's service**, by its entity methods
    (`getOwnedTodoListForWrite`): they carry validation, ownership, locking and, later, caching and
    its invalidation. Using another aggregate's repository bypasses all of them;
  - **a Manager (application) service** (`<UseCase>Manager`, in `service`) exists only when one
    request writes **several aggregates**: it owns that transaction boundary and calls the aggregate
    services, which join it; it uses no repository. A use case that writes one aggregate needs no
    Manager (SMELL-11, SMELL-12: no layer for the pattern's sake);
  - **read models may join**: a read-only Criteria projection may join another aggregate's table to
    show its data (a list's name on a todo row); loading or writing another aggregate's entity
    goes through its service;
  - no interface for a single implementation (SMELL-11): services are concrete classes.
- **Detect:** a `*Repository` import in a service of another aggregate, a mapper or a Manager; a
  member entity with its own service or controller; a Manager that writes one aggregate; entity
  services calling each other in a cycle (the use case belongs in a Manager).
- **Enforce:** ArchUnit, from `{{aggregates}}` (reference: `AggregateArchTest`); an undeclared
  repository fails the rule, so every new aggregate is declared.
- **Severity:** High when a business rule, lock or ownership check is bypassed; otherwise Medium.

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
| ARCH-03 | `layeredArchitecture().consideringAllDependencies()` from `{{layerPackages}}` |
| ARCH-04 / ARCH-06 | one generic `ArchCondition` comparing the module segment of origin and target for `..repository..`, `..domain..`, `..dto.request..`, `..dto.response..`, `..mapper..` (no hand-listed modules) |
| ARCH-05 | `..<module>.api..` depends only on the allowed packages, excluding `..controller.api..` |
| ARCH-07 | event classes depend on no entity, repository or service |
| ARCH-08 | `<root>.common..` depends on no feature module |
| ARCH-11 | `@RestController` classes not public; no `@Transactional` methods in `..controller..`; no dependency on `..repository..` / `..domain..` |
| ARCH-12 | methods calling bypass API X carry annotation A; every `@Scheduled` method declares its posture |
| ARCH-15 | a `*Repository` is used only by its aggregate's service (`{{aggregates}}`) and its own repository package; an undeclared repository fails |
| Naming | `*Controller`, `*Repository`; `*RepositoryImpl` not public; package names lower-case; test classes end in `Test` (Surefire skips others silently) |
| Conventions in bytecode | no `@Lob` field; no `@Transactional` private method; no `Optional` field or parameter; no `@TransactionalEventListener` + `REQUIRES_NEW` without `@Async`; no `Executors.newFixedThreadPool` / `newCachedThreadPool`; `GeneralCodingRules` (no `System.out`, no java.util.logging, no field injection, no generic `RuntimeException` thrown) |

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
2. A red ArchUnit rule in the build is a finding citing the rule and its `because`.
3. Don't hand-check what a green rule guarantees: list it under "Checked and fine (ArchUnit)".
4. In Step 8, offer to generate
   `src/test/java/<root>/archunit/{Layer,ModuleBoundary,Naming,Convention}ArchTest.java` from the
   profile, with:
   - `@AnalyzeClasses(packages = "<root>", importOptions = ImportOption.DoNotIncludeTests.class)`;
   - a `.because("<why>")` on every rule;
   - a dated comment on every carve-out.

   Also offer it when an automatable guideline finding recurs.
5. Use `FreezingArchRule` only to adopt ArchUnit on a large legacy codebase, and its stored
   violations go in the deviation register.
