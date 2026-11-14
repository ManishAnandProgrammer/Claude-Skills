# Configuration metadata (META-xx): optional, for projects with the `metadata` piece

Per-tenant, per-feature configuration stored as one row per key with a JSON value: the owner's
design, proven in a schema-per-tenant SaaS (2026-11-14). Golden code:
`~/.claude/skills-reference/src/main/java/com/example/app/metadata/` (its `package-info.java` has
the rules and the tenancy guide). Apply these rules only when the project has that piece.

### META-01: Metadata is configuration, not a hidden table
- **Check:** a key holds one value, read often and changed rarely, by an admin (attendance rules
  of a session, a feature switch, a template choice). Records that grow, are listed, filtered or
  have their own lifecycle are real tables with columns, indexes and constraints.
- **Detect:** keys built from timestamps or counters (`SURVEY_FORM_<datetime>`); `key LIKE 'X%'`;
  `value ->> 'field'` in a `WHERE`; a service that loops over many metadata rows.
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
    (`attendance(UUID sessionId)` → `ATTENDANCE_CONFIG_<uuid>`, the canonical UUID form); no key
    strings assembled elsewhere;
  - the generic admin API (`PUT /metadata/{key}`) resolves **fixed keys only**, through
    `MetadataKeys.byName`;
  - per-context configuration is read and written through the owning module's typed endpoint on its
    own resource (`GET/PUT /school-sessions/{id}/fee-rules`, a typed record body): the owner checks
    its own rules (an ended session's rules can't change), then calls `Metadata.update`. The metadata
    piece has no hook, SPI or rule registry for other modules (run 5: a `MetadataUpdateRule` hook
    needed `ObjectProvider` around a bean cycle, parsed the session id back out of the key name, and
    kept the contract out of OpenAPI).
- **Detect:** `byName` resolving a per-context prefix; key names parsed back into ids; an interface
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
  new context from the previous one, e.g. a new school session); contexts that already exist are
  seeded by a changeset or a one-off job, never at application startup (DATA-28). Fixed keys and
  fixed counters are seeded by the changeset that needs them. A missing value is 404
  `METADATA_NOT_FOUND`, never a silent default: it means seeding didn't run.
- **Deleting a context** deletes its keys and counters in the same transaction (`Metadata.delete`,
  `Counters.delete`), so nothing is left behind for a later context to trip over.
- **Severity:** Medium.

### META-06: Versioned writes
- **Check:** `PUT {value, version}`; a stale version is 409 `OPTIMISTIC_LOCK_CONFLICT`; an unchanged
  value (compared as records, not as text) keeps its version.
- **Severity:** Medium.

### META-07: Security
- **Check:** every metadata endpoint carries the admin role check; no shared secret in a request
  body; secrets (keys, passwords, tokens) are never stored in metadata (they belong in a secret
  store or configuration).
- **Severity:** High.

### META-08: Counters
- **Check:** gapless business numbers (admission, receipt) live in the `counters` table, incremented
  by one atomic `UPDATE … SET last_value = last_value + 1 … RETURNING last_value` in the caller's
  transaction (`MANDATORY`); never in JSON, never read-then-write. A rolled-back business
  transaction returns its number.
- **Names:** a per-context counter's name comes from `Counters.perContext(prefix, contextId)` (the
  canonical UUID form, as for metadata keys), never assembled by hand. The module that numbers
  declares the prefix (the fee module owns `RECEIPT`), unless the dependency direction forbids it
  (ARCH-02); then the context's owner declares it.
- **Severity:** High.

### META-09: Tenancy
- **Check:** schema-per-tenant: the table in every tenant schema, unique `key`. Row-based: a
  `tenant_id` column, unique `(tenant_id, key)`, Hibernate `@TenantId` on both entities, and the
  counter's native `UPDATE` filtered by tenant (native SQL is not filtered by `@TenantId`).
- **Severity:** Critical when a row-based query can read or write another tenant's row.

Not in scope by default: history (audit columns and `version` record the last change; Envers is a
bootstrap option) and caching (a read is one indexed lookup; cache only a key read repeatedly on a
hot path, with eviction on every write across instances).
