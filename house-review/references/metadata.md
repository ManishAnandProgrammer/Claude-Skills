# Configuration metadata (META-xx): optional, for projects with the `metadata` piece

Per-tenant, per-feature configuration stored as one row per key with a JSON value: the owner's
design, proven in a schema-per-tenant SaaS. Golden code:
`~/.claude/skills-reference/src/main/java/com/example/app/metadata/` (this file has the rules with their
reasons and the tenancy guide). Apply these rules
only when the project has that piece.

### META-01: Metadata is configuration, not a hidden table
- **Check:** a key holds one value, set by an admin (a todo list's
  settings, a store's opening hours, a feature switch, a template choice). Records that are added over time, listed, filtered or
  have their own lifecycle are real tables with columns, indexes and constraints.
- **Detect:** keys built from timestamps or counters (`SURVEY_FORM_<datetime>`); `key LIKE 'X%'`;
  `value ->> 'field'` in a `WHERE`; a service that loops over the rows a metadata query returns.
- **Severity:** High.

### META-02: One plain record per key
- **Check:** a value is a record whose components are the JSON contract; defaults for missing fields
  live in its compact constructor (boxed components where "absent" must be told apart); bean
  validation on the components. Other modules receive the record. No wrapper classes over a JSON
  node, no `JsonNode` getters by string name in business code.
- **Severity:** Medium.

### META-03: Keys are declared in one place; per-context values are written by their owner
- **Check:**
  - every key is a constant or a per-context factory in `MetadataKeys`
    (`todoListSettings(UUID listId)` → `TODO_LIST_SETTINGS_<uuid>`, the canonical UUID form); no key
    strings assembled elsewhere;
  - the generic admin API (`PUT /metadata/{key}`) resolves **fixed keys only**, through
    `MetadataKeys.fixedKeyNamed`;
  - per-context configuration is read and written through the owning module's typed endpoint on its
    own resource (`GET/PUT /todo-lists/{id}/settings`, `/stores/{id}/opening-hours`, a typed record
    body): the owner loads the context (404), checks its own rules (a closed store's opening hours
    can't change: 409), then calls `Metadata.update`. The metadata piece has no hook, SPI or rule
    registry for other modules: a hook calling back into the owner needs a workaround for the bean
    cycle (metadata → owner → metadata), has to parse the context id back out of the key name, and
    hides the typed contract from OpenAPI, where a typed endpoint documents it.
- **Detect:** `fixedKeyNamed` resolving a per-context prefix; key names parsed back into ids; an interface
  in the metadata piece implemented by a feature module.
- **Severity:** High (an undeclared key reachable through the API bypasses validation; a
  per-context key written through the generic API bypasses its owner's rules).

### META-04: Adding a field (the deploy rule)
- **Check:** a new field gets a default in the record; it is deployed to every instance before
  admins may set it (an old instance writing the record drops fields it doesn't know). Stored JSON
  is read tolerantly (unknown fields ignored), admin input strictly (unknown fields are 400).
- **Severity:** Medium.

### META-05: Seeding and deleting
- **Check:** the feature's lifecycle code creates its values (`create`, or `createFrom` to start a
  new context from the defaults or from another context, e.g. a new todo list); contexts that already exist are
  seeded by a changeset or a one-off job, never at application startup (DATA-28). Fixed keys and
  fixed counters are seeded by the changeset that needs them (reference: `0014-todo-list-defaults.sql`;
  in row mode its `tenancy-row` block for the existing tenants and a `TenantProvisioned` listener for
  new ones, `TodoListDefaultsSeed`). A missing value is 404 `METADATA_VALUE_MISSING`, never a silent
  default: it means seeding didn't run.
- **Tests of a fixed key** capture the stored row and put it back (`storedMetadata` /
  `restoreMetadata` in the test base, under the key's `@ResourceLock`), never delete and re-insert it
  at version 0 for good: real code reads the key, and the next class would find a test's value or none
  (reference: `MetadataIntegrationTest`).
- **Deleting a context** deletes its keys and counters in the same transaction (`Metadata.delete`,
  `Counters.delete`), so nothing is left behind for a later context to trip over.
- **Severity:** Medium.

### META-06: Versioned writes
- **Check:** `PUT {value, version}`; a stale version is 409 `OPTIMISTIC_LOCK_CONFLICT`; an unchanged
  value (compared as records, not as text) keeps its version.
- **Severity:** Medium.

### META-07: Security
- **Check:** every metadata endpoint `@Requires(Permission.METADATA_MANAGE)` (AUTHZ-03); no shared secret in a request
  body; secrets (keys, passwords, tokens) are never stored in metadata (they belong in a secret
  store or configuration).
- **Severity:** High.

### META-08: Counters
- **Check:** gapless business numbers (invoice, order) live in the `counters` table, incremented
  by one atomic `UPDATE … SET last_value = last_value + 1 … RETURNING last_value` in the caller's
  transaction (`MANDATORY`); never in JSON, never read-then-write. A rolled-back business
  transaction returns its number.
- **Names:** a per-context counter's name comes from `Counters.perContext(prefix, contextId)` (the
  canonical UUID form, as for metadata keys), never assembled by hand. The module that numbers
  declares the prefix (the billing module owns `INVOICE`), unless the dependency direction forbids
  it (ARCH-02: the store module seeds the counter, and billing depends on stores); then the
  context's owner declares it. With tenancy the tenant is not a context: `counters` is tenant data, so
  a tenant's display number is a fixed name (TEN-18).
- **Seeding:** a seed that may meet the row created another way first (row mode's `TenantProvisioned`
  seed beside a backfill; a create that numbers where the seed never ran) uses `Counters.createIfAbsent`
  (one `INSERT … ON CONFLICT DO NOTHING`), never `create` (409 on the second, which dooms the caller's
  transaction).
- **Severity:** High.

### META-09: Tenancy
- **Rules:** the tenancy rules themselves are `tenancy.md` (TEN-xx); this rule is what they mean for
  the metadata tables. Schema per tenant lists `0008` in `db/changelog/tenant.yaml`; row mode keeps one master.
- **Check:** schema-per-tenant: the table in every tenant schema, unique `key`. Row mode: both
  tables are tenant tables (TEN-30), unique `(tenant_id, key)`, both entities `TenantOwnedEntity`, each
  key the table's replica identity (TEN-38). Counters are a tenant's own numbers in both modes (TEN-18,
  `CounterTenantIntegrationTest`).
- **Severity:** Critical when a row-mode query can read or write another tenant's row.
- **Adapting the piece** (the reference is single-tenant; bootstrap picks the model, the code is
  otherwise the same):
  - *Schema-per-tenant* (the `tenancy-schema` piece): the metadata package and changeset `0008` stay
    as they are; `0008` is listed in the tenant changelog. Each tenant schema gets both tables
    (provisioning runs the tenant changelog into a new schema, `TenantMigrations` into every schema
    at deployment); the unique `key` is per schema. The tenant comes from the token's claim (TEN-01),
    and the connection hook sets `search_path` to its schema (TEN-04), so the native counter update
    needs no change either.
  - *Row mode* (the `tenancy-row` piece): `0008`'s `tenancy-row` blocks add `tenant_id` (its default
    dropped again by the row overlay's `0016`: every insert names its tenant, TEN-30), `UNIQUE
    (tenant_id, key)` under the same constraint names (`MetadataConstraintErrorCodes` keeps working)
    and the forced policy; the entities' blocks extend `TenantOwnedEntity`. The native
    `CounterRepository.increment` names its tenant (`AND tenant_id = :tenantId`, the row overlay's
    replacement; `CounterService` passes `TenantContext.id()`), an index condition on `(tenant_id, key)`
    (TEN-37).
  - In both models, lifecycle code without a request (a scheduled job, tenant registration) opens
    its session for an explicit tenant.

Not in scope by default: history (audit columns and `version` record the last change; a project
that must show earlier values adds Envers, an approved dependency, or an append-only
`configuration_metadata_history` table at bootstrap) and caching (a read is one indexed lookup; a project that caches a key, a use case, evicts it on
every write across instances).
