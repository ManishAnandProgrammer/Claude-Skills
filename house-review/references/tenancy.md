# Tenancy (TEN-xx): optional, for projects with a tenancy piece

One application serving many organisations (tenants) whose data must never meet. Apply these rules
only when the review profile says `tenancy: row` or `tenancy: schema`. Rules marked **[schema]** apply
to schema-per-tenant only, **[row]** to row-based tenancy only; the others apply to both.

Golden code: the skills repo's overlays, not the reference application (which runs without tenancy).
The shared layer `~/Documents/claude-skills/overlays/tenancy/src/main/java/com/example/app/`:
`common/tenancy/` (`TenantContext`, `TenantScope`, `TenantIdentifierResolver`, `TenantTaskDecorator`),
`config/PlatformSecurityConfig` (the platform chain), the `tenant` module (`TenantService` with
`approve`, `PlatformTenantController`, `PlatformAuditService`, `AllowAllSupportAccess`), `user/`
(`TenantMembership`, `MembershipService` with "my tenants", `PlatformUser`, `PlatformAuthService`,
`ImpersonationService`, `TokenService`, `TenantRegistrationService`),
`metadata/service/PlatformMetadataService` (a direct cross-tenant edit), `config/tenancy.yml`,
changeset `0012`. Schema per tenant, `overlays/tenancy-schema/`: `common/tenancy/`
(`TransactionLocalSchemaProvider`, `TenantSchemas`), `config/TenancyConfig`, `META-INF/orm.xml`, the
`tenant` module's `TenantProvisioningJob`, `TenantMigrations`, `TenantSchemaMigrator`,
`TenantTemplateMigration`, changeset `0013` and the masters `db/changelog/platform.yaml` and
`tenant.yaml`, `docs/tenancy-runbook.md`, `docker/postgres-init/`. Row mode, `overlays/tenancy-row/`:
`common/tenancy/TransactionLocalTenantProvider` (the hook), `common/persistence/TenantOwnedEntity`
(`@TenantId`), `config/TenancyConfig`, `tenant/service/TenantProvisioning` (activation after the
approval), changeset `0013-row-security` (the app role's grants), with `media` `media/api/UploadLimits`
and with `example` `todo/service/TodoAttachmentPolicySeed` (a tenant's defaults on
`TenantProvisioned`), `docs/tenancy-runbook.md`, `docker/postgres-init/`; the tenant tables' `tenant_id`,
keys and policies are `tenancy-row` blocks in the reference's own changesets `0002`–`0011` and entities.
Tests: `tenant/**`, `archunit/TenancyArchTest`, `todo/TodoTenantIsolationIntegrationTest`,
`support/IntegrationTest` (the test tenant, `@WithoutTestTenant`); row mode's
`tenant/row/RowIsolationIntegrationTest` and `tenant/row/RowSecurityCatalogIntegrationTest` (the guard
on the schema). In a bootstrapped project these sit at the same paths under
its own package. The design and its experiments: the skills repo's `docs/design/tenancy.md`.

The flow: JWT (`tenant_id`) → `TenantContext` → Hibernate's resolver → the connection hook runs one
statement as the transaction's first (schema: `search_path`; row: `app.tenant_id`) → the commit or
rollback clears it. Jobs: `for tenant in tenants.activeIds(): TenantScope.runAs(tenant, work)`.
Platform staff: their own tokens (`aud=platform`) under `/api/v1/platform/**` only.

**Severity rule for the whole file:** any path that can read or write another tenant's data is
**Critical**, whatever the rule's own severity says.

## Where the tenant comes from

### TEN-01: The tenant comes only from the verified token
- **Check:** the tenant is the access token's `tenant_id` claim, read in one place
  (`TenantContext`). Never a header, a path segment, a query parameter or a body field: tenant-plane
  URLs carry no tenant (`/api/v1/todos`, not `/tenants/{id}/todos`), so a client can't ask for another
  tenant and there is no id to guess. With an external identity provider, `TenantContext.fromToken`
  is the one class that maps its claim. The one request that names a tenant is switch-tenant, and it
  is checked against the caller's own memberships.
- **Detect:** `tenantId` in a request record, a filter, `@PathVariable`, `@RequestParam` or
  `@RequestHeader` of a tenant-plane controller (ArchUnit: `TenancyArchTest`); `X-Tenant-ID`;
  a host-name or subdomain lookup without a verified mapping.
- **Severity:** Critical.

### TEN-02: Only `TenantScope.runAs` switches the tenant, around the transaction
- **Check:** work off the request path (a job, a listener, provisioning) runs under
  `TenantScope.runAs(tenantId, work)`, which restores the previous tenant in `finally`. It wraps the
  call **into** the `@Transactional` method: the tenant is fixed when the transaction's session opens,
  and a switch inside an open transaction is ignored by Hibernate and the database alike (E2, E4). No
  controller calls `TenantScope`. No `ThreadLocal` of a tenant anywhere else.
- **Detect:** `runAs` inside a `@Transactional` method's body around repository calls; a static
  `setTenant`/`clear` API; `TenantScope` in a controller (ArchUnit).
- **Severity:** Critical (a switch inside a transaction silently reads the outer tenant's data).

### TEN-03: No tenant means NO_TENANT, never a default tenant
- **Check:** Hibernate's resolver returns `TenantContext.current()`: the scope, else the token, else
  `NO_TENANT` (`new UUID(0, 0)`), which reaches platform tables only (schema: the role's default
  `search_path = platform`, tenant tables don't exist; row: policies see `''`, 0 rows). An unknown
  tenant never falls back to a real one or to `public`; `isRoot` is never true. One resolver, in
  `common.tenancy` (ArchUnit).
- **Detect:** a resolver returning a configured default tenant, `public`, or null; a second
  `CurrentTenantIdentifierResolver`.
- **Severity:** Critical.

### TEN-04: The hook sets the tenant transaction-locally, when the connection is borrowed
- **Check:**
  - Hibernate's `MultiTenantConnectionProvider.getConnection(tenant)` runs **one parameterised
    statement** as the physical transaction's first: schema `set_config('search_path',
    quote_ident(?), true)`, row `set_config('app.tenant_id', ?, true)`, plus
    `set_config('transaction_read_only', 'on', true)` for a read-only transaction;
  - `hibernate.connection.handling_mode: DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION`, so a
    transaction borrows at its first statement (Spring's default mode borrows a read-only one at
    begin and the flag is lost); the core's `LazyConnectionDataSourceProxy` is not needed then;
  - nothing to reset: the commit or rollback clears the setting, so the pool hands out clean
    connections.
- **Detect:** `SET search_path` / `set_config(..., false)` / `setSchema` (session-level: the next
  borrower gets the previous tenant, E4); Hikari `connectionInitSql`; SQL in an override of
  `JpaTransactionManager.doBegin` (borrows every transaction eagerly); an aspect that sets the tenant;
  a concatenated schema name instead of a parameter (78 prepared statements per connection in E5).
- **Not a finding when:** `@Transactional(isolation = ...)` is absent: the release mode refuses it,
  and the house uses none.
- **Severity:** Critical for a session-level setting; High for eager borrowing.

## Two worlds: the platform and the tenants

### TEN-05: Platform staff are separate users with their own tokens and chain
- **Check:** `platform_users` (no tenant), their own login, tokens with `aud=platform` and no
  `tenant_id`; `/api/v1/platform/**` has its own `SecurityFilterChain` that accepts only platform
  tokens, and the main chain accepts only tenant tokens (`aud=tenant`). A platform token on a tenant
  endpoint is 401, and the reverse. Staff are never modelled as a tenant or a "super admin" role in a
  tenant.
- **Detect:** one decoder for both audiences; a `ROLE_SUPER_ADMIN` that bypasses tenant checks;
  operator endpoints behind a shared secret header; `isPlatform` branches in tenant-plane code.
- **Severity:** Critical.

### TEN-06: Tenant status is checked whenever tokens are issued
- **Check:** login, refresh and switch-tenant call `Tenants.requireActive` (403 `TENANT_NOT_ACTIVE`
  with `tenantStatus`; an unknown tenant is refused the same way). A suspension takes effect within
  one access-token lifetime (REST-15's bound, 15 minutes); a per-request check is a later option for
  projects that need it at once. The check's refusal never dooms the caller's transaction
  (`noRollbackFor`), since a refresh commits its token's rotation.
- **Detect:** token issue without the status check; a status mirrored from billing (billing decides
  when to call `suspend`); an unknown status treated as active (SEC-06).
- **Severity:** High.

### TEN-07: The planes don't call each other's services
- **Check:** tenant-plane controllers never depend on the control plane's services (the tenant
  module's, `Platform*`, `ImpersonationService`); platform controllers never on tenant-plane
  services directly. Other modules reach the register only through `tenant.api.Tenants`.
- **Detect:** ArchUnit `TenancyArchTest`.
- **Severity:** High.

### TEN-08: Memberships, with the role on the membership
- **Check:** users are global (email unique once); `tenant_memberships(tenant_id, user_id, role)`
  holds the role, so one person is ADMIN here and USER there. An access token is for one membership;
  login picks the most recently used, switch-tenant issues tokens for another. A refresh token
  remembers its tenant.
- **Detect:** `users.tenant_id`; the role read from `users.role` into a tenant token.
- **Severity:** Medium.

### TEN-09: One way in, one lifecycle
- **Check:** `PENDING → ACTIVE ⇄ SUSPENDED → CLOSED`, `PENDING → REJECTED`, as text with a check
  constraint; every change is a `tenant_status_changes` row with its source (`STAFF`, `REGISTRATION`,
  `PROVISIONING`). `Tenants.approve` is the one way in (staff now, a payment webhook later): it
  publishes `TenantApproved`, the mode's provisioning prepares the tables and only then activates
  (`activateProvisioned`). Staff-created tenants are approved at once; registered ones wait. Creating
  a tenant publishes `TenantCreated` in its transaction (the owner's membership); tenant defaults are
  seeded on `TenantProvisioned`, under the new tenant's scope. A tenant no longer PENDING when
  provisioning reaches it (rejected after approval) gets no schema (`tenant_migrations` CANCELLED).
  **[row]** provisioning is the activation alone, right after the approval commits (TEN-34).
- **Detect:** a second activation path; staff activating a tenant whose tables don't exist;
  provisioning that creates storage without re-checking the status; plans or payment state on
  `tenants`.
- **Severity:** Medium.

## Work off the request path

### TEN-10: Every unit of work carries its tenant
- **Check:**
  - a job loops `Tenants.activeIds()` and runs each tenant's batch under `runAs`, each in its own
    transactions (one big tenant can't starve the rest);
  - `@Async` work inherits the tenant through the `TaskDecorator` (`TenantTaskDecorator`);
  - outbox rows and messages carry `tenant_id`, and the consumer runs under `runAs`;
  - caches have the tenant in their key; the MDC carries `tenantId`;
  - object-store keys start with the tenant (`{tenantId}/{purpose}/{uuid}`).
- **Detect:** a job that queries tenant tables without a scope (it sees nothing, or, in row mode with
  a bypass role, everything); `@Async` without the decorator; a cache key without the tenant.
- **Severity:** Critical for a cache or message without the tenant; High for a job without a scope.

### TEN-11: Metrics are never tagged by tenant
- **Check:** no tenant id as a metric tag (unbounded cardinality); per-tenant usage is a metering
  table.
- **Detect:** `Tag.of("tenant", ...)`, `.tag("tenantId", ...)`.
- **Severity:** Medium.

## The database

### TEN-12: Two roles: the owner migrates, the application only reads and writes
- **Check:** created by the runbook (cluster-level, so a restore needs them first), never by
  Liquibase: `<app>_owner` owns every table and runs Liquibase (`app.liquibase.username`,
  `DB_OWNER_USER`); `<app>_app` is the pool's role, not an owner, DML grants only through
  `ALTER DEFAULT PRIVILEGES` in a changeset. **[row]** the app role is `NOBYPASSRLS` and RLS is
  `FORCE`d. No policy or code path has an "or bypass" switch the app role can flip itself.
- **Detect:** the pool connecting as the owner or a superuser; `GRANT ALL`; a policy with
  `OR current_setting('app.bypass') = 'on'`.
- **Severity:** Critical.

### TEN-13: Tests run with the real roles; tenancy tests cross tenants
- **Check:** the Testcontainers init script creates the roles; Liquibase migrates as the owner, the
  pool connects as the app role. The test base gives the run one test tenant, and every test that
  isn't about tenancy runs inside it (its methods under `TenantScope.runAs`, fixture JDBC on its
  tables), so a project's tests read as without tenancy. Tests about tenancy are `@WithoutTestTenant`
  and create the tenants they cross (`newTenant()`, `newUserIn(tenant)`): per entity (crud-gen
  generates it) another tenant's GET, PUT and DELETE are 404 like a missing id, and its lists never
  show the caller's rows. Plane tests: platform token on a tenant endpoint 401 and the reverse; a
  suspended tenant's login and refresh 403. Hook tests (E4): nothing leaks to a plain JDBC borrower
  after commit and rollback, `REQUIRES_NEW` under another tenant, `NO_TENANT`, lazy borrowing.
- **Detect:** tests connecting as the owner or a superuser (they prove nothing); an entity without its
  cross-tenant test; a cross-tenant test running inside the test tenant's scope (the scope, not the
  token, would name the tenant).
- **Severity:** High (TEST lens).

### TEN-14: Staff act in a tenant only through impersonation or an audited, scoped edit
- **Check:** platform staff may act as any tenant and edit across tenants by default (owner decision
  O6), but only two ways:
  - **impersonation:** `POST /platform/tenants/{id}/impersonations` issues a short-lived tenant token
    for a member (default the tenant's oldest ADMIN) with `act: {sub: <platformUserId>}` (RFC 8693),
    no refresh token; the tenant plane takes it like any tenant token; `CurrentUser.actorId()` and the
    MDC's `actorId` name the real person, while `id()` (ownership, audit columns) stays the member;
  - **a direct edit:** a platform endpoint names the tenant in its path
    (`/platform/tenants/{tenantId}/...`) and a `Platform*` service runs the tenant module's own
    service under `TenantScope.runAs(tenantId, …)`, outside any transaction of its own (golden
    `PlatformMetadataService`). One tenant per call, never a cross-tenant read in one transaction.
  - **always:** one append-only `platform_audit` row per impersonation and per direct edit (who, which
    tenant, what, when), written before the action so a refused attempt is recorded too; the app
    role has `SELECT, INSERT` on it only;
  - **the gate:** `SupportAccessPolicy.allows` is asked before an impersonation token is issued (403
    `SUPPORT_ACCESS_DENIED`). The default allows every request; a strict application replaces it
    (consent the tenant's admin gave for a time box, a ticket reference), never the endpoints.
- **Detect:** a platform endpoint reading tenant tables without `runAs`; a staff action without an
  audit row; an impersonation token without `act`, with a refresh token, or issued without the
  policy; a direct edit that loops over tenants in one transaction.
- **Severity:** Critical for an unaudited path into tenant data; High otherwise.

### TEN-15: Self-registration never reveals an account
- **Check:** when the project lets organisations register (`POST /auth/tenant-registrations`, the
  `tenant-registration` piece): public and rate limited like signup; the answer is the same (202, no
  body) whether the email is new, has an account with that password, or has one with another password
  (then nothing is registered: no one attaches a tenant to someone else's account); both paths cost
  the same work (one hash, the breached-password check). The tenant is PENDING until staff approve it.
  Without mail sending the owner's email is unverified: the review records it as an accepted risk
  until the project sends verification mails.
- **Detect:** a 409 or a different body for a known email; a registration path that skips the
  password check for an existing account; an approval that bypasses `approve`.
- **Severity:** High (account enumeration, a tenant attached to a foreign account).

## Schema per tenant

### TEN-20: [schema] Only the hook writes `search_path`; schema names are derived
- **Check:** a tenant's schema is `t_<tenant id without dashes>` (`TenantSchemas.of`), never taken
  from input, and the hook still passes it through `quote_ident(?)`. Only
  `TransactionLocalSchemaProvider` (and the migrator's DDL as the owner) names `search_path`. The app
  role's default `search_path` is `platform` (runbook).
- **Detect:** `search_path` or a schema name built from a request value; `search_path` anywhere else
  (ArchUnit source scan).
- **Severity:** Critical.

### TEN-21: [schema] Platform entities name their schema; native SQL names none
- **Check:** every platform entity is mapped to schema `platform` in `META-INF/orm.xml` (the entity
  classes stay the same in every mode), so it resolves under any tenant's `search_path`. Tenant entities name no schema. Native SQL never names a schema
  (`platform.`, `t_….`, `tenant_template.`): a tenant query names its tables unqualified and the hook
  decides the schema; platform data is read through its entities. Tenant tables have no `tenant_id`.
- **Detect:** ArchUnit `TenancyArchTest` (`@Query(nativeQuery = true)` values, source scan); a platform
  entity missing from `orm.xml` (its table isn't found under a tenant's `search_path`).
- **Severity:** Critical when tenant code names another schema.

### TEN-22: [schema] Two changelogs; changesets never move
- **Check:** `db/changelog/platform.yaml` (platform tables, Spring's Liquibase at startup,
  `default-schema: platform`) and `db/changelog/tenant.yaml` (every tenant table, run per schema).
  Generated tables go into the tenant changelog. A changeset is identified by id, author and file
  path: moving one to another file re-runs it in every schema (E6).
- **Detect:** a tenant table in the platform master or the reverse; a changeset file renamed or moved.
- **Severity:** High.

### TEN-23: [schema] The first migration runs only in provisioning; deploys migrate on one instance
- **Check:**
  - provisioning (a job row: `tenant_migrations` PROVISIONING) creates the schema, grants usage to
    the app role, runs the tenant changelog, seeds, activates; idempotent and resumable. Two migrators
    on a brand-new schema fail (E6e);
  - at deploy, `TenantMigrations` runs on one instance under a PostgreSQL advisory lock (others
    skip), schema after schema (Liquibase's run state is JVM-wide: no parallel runs in one JVM),
    recording each schema's last changeset; a failing schema is recorded FAILED and skipped, a rerun
    applies only what's missing (E6c/E6d). Never at every instance's startup (Liquibase's lock polls
    every 10 s per schema, E6f);
  - changesets are expand-then-contract: old code meets new schemas during the rollout.
- **Detect:** `SpringLiquibase` per tenant in a startup bean; migrations without the advisory lock;
  a failure that stops the whole run; a destructive changeset in the same release as the code that
  stops using the column.
- **Severity:** High.

### TEN-24: [schema] Hibernate validates the template schema
- **Check:** `getAnyConnection` points at `tenant_template`, migrated at every start before the
  entity manager factory (after the platform changelog). Hibernate validates that one schema;
  `tenant_migrations` (every schema at the expected changeset) covers the rest. A schema behind the
  template is "migrations lagging", readable on the platform API, never on `/health`.
- **Detect:** `ddl-auto: validate` against `platform` (fails: missing tables, E7); a health
  indicator that goes DOWN for one lagging tenant.
- **Severity:** Medium.

### TEN-25: [schema] Thousands of tenants, not tens of thousands
- **Check:** schema per tenant suits thousands of tenants that want their data apart (export, delete,
  restore one with `pg_dump -n`, `DROP SCHEMA … CASCADE` after retention). Tens of thousands strain the
  catalog, `pg_dump` and autovacuum: row mode is the answer there. The bootstrap question says so;
  a review records the expected tenant count.
- **Severity:** Medium (an architecture decision to revisit, not a code defect).

## Row mode

One schema for every tenant; `tenant_id` on each tenant table. Two layers, each with its job:
Hibernate's `@TenantId` fills `tenant_id` and adds `tenant_id = ?` to every entity query (the index
predicate, and no repository names the tenant); PostgreSQL row-level security (RLS) is the barrier
for everything Hibernate doesn't filter (native SQL, a fixture, an injection). Evidence: the design's
E1–E3 and the row tests named below, which run as the real application role.

### TEN-30: [row] Every tenant table is forced under one tenant policy, in the changeset that creates it
- **Check:** the changeset that creates a tenant table also has
  - `tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid` and
    `CONSTRAINT fk_<table>_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id)`. Hibernate sets the
    column; the default fills a native or fixture insert from the transaction's tenant, and without a
    tenant it is NULL, refused (the policy's check fires first: `new row violates row-level security
    policy`, `RowIsolationIntegrationTest`);
  - `ALTER TABLE <t> ENABLE ROW LEVEL SECURITY; ALTER TABLE <t> FORCE ROW LEVEL SECURITY;` (forced: the
    owner, which runs Liquibase, is bound too);
  - exactly one policy for every command, `CREATE POLICY tenant_isolation ON <t> USING (tenant_id =
    nullif(current_setting('app.tenant_id', true), '')::uuid) WITH CHECK (<the same>)`. A second
    permissive policy is OR-ed with the first and widens it.

  Every table is a tenant table unless it is in `RowSecurityCatalogIntegrationTest.PLATFORM_TABLES`
  with its reason; that test fails on a tenant table missing any part above (it is proven to catch a
  table without a policy and one enabled but not forced), on a table that is neither, on a `tenant_id`
  that is nullable or doesn't reference `tenants`, and on an app role that is a superuser, owns a table
  or has `BYPASSRLS`.
- **Detect:** a `tenant_id` column without `FORCE`; the policy in a later changeset (a window without
  it); `USING` without `WITH CHECK` (a write into another tenant); `FOR SELECT` only; a policy with
  `OR current_setting('app.bypass', true) = 'on'` or any switch the app role can set itself (TEN-12);
  `current_setting('app.tenant_id')::uuid` without `nullif` (a reused session returns `''`, and the cast
  fails instead of matching nothing); a new table missing from the catalogue test's lists.
- **Severity:** Critical.

### TEN-31: [row] Only the hook names `app.tenant_id`
- **Check:** `TransactionLocalTenantProvider` is the only Java that sets or reads `app.tenant_id`
  (`TenancyArchTest`), with one parameterised transaction-local statement (TEN-04); the policies and
  the column default read it in SQL. `NO_TENANT` sets it empty: platform tables work, tenant rows read
  as none, a tenant write is refused. Nothing of it survives the transaction: after a commit and after a
  rollback every pooled connection reads `''` and a plain JDBC borrower sees no tenant rows
  (`RowIsolationIntegrationTest`).
- **Detect:** `set_config('app.tenant_id', ..., false)`, `SET app.tenant_id`, Hikari
  `connectionInitSql`; a service or repository reading the setting to build its own predicate.
- **Severity:** Critical.

### TEN-32: [row] Tenant entities extend `TenantOwnedEntity`; code never names the tenant
- **Check:**
  - an entity of a tenant table extends `TenantOwnedEntity` (`@TenantId`), a platform entity extends
    `AuditableEntity` (`RowSecurityCatalogIntegrationTest` matches entities to the platform list).
    Hibernate then refuses an entity carrying another tenant's id before any SQL, finds no other
    tenant's row by id or reference, and filters JPQL, Criteria, joins and bulk updates (E1,
    `RowIsolationIntegrationTest`);
  - repositories, services, filters and requests never take a tenant parameter (TEN-01);
  - **native SQL** is not filtered by Hibernate; the policy filters it (proven in the row test), and
    its predicate is an index condition on an index with `tenant_id` among its equality columns (the
    row test's `EXPLAIN`). So a native query needs no tenant parameter. With Hibernate's own
    `tenant_id = ?` in an entity query, the policy's predicate reduces to a one-time filter (measured on
    the todo index shapes).
- **Detect:** a tenant entity extending `AuditableEntity` (the column default would fill it, but
  entity queries lose their predicate and the insert check); `tenantId` in a repository method, a
  `@Query` or a service signature; a native query that adds the tenant from a parameter it was given.
- **Severity:** High (RLS still holds; Critical when combined with a policy gap).

### TEN-33: [row] Keys carry the tenant; references between tenant tables are composite
- **Check:**
  - every unique constraint and list index has `tenant_id` among its equality columns, in DATA-10's
    scope order: after the owner in an owner-scoped table (`(owner_id, tenant_id, name, id)`, which
    still serves the owner's foreign key), leading where the tenant alone scopes the list (the media
    jobs' `(tenant_id, upload_expires_at, id)`, `(tenant_id, key)`). The single-tenant names stay, so the
    modules' `ConstraintErrorCodes` serve every mode;
  - a reference between two tenant tables is a **composite foreign key** carrying `tenant_id` on both
    sides, to a parent key with `tenant_id` last: `(todo_list_id, owner_id, tenant_id) → todo_lists (id,
    owner_id, tenant_id)`, `(media_file_id, tenant_id) → media_files (id, tenant_id)`. Key checks bypass
    RLS, so a plain `parent_id` key lets a row point at another tenant's parent; the composite one
    refuses it (`RowIsolationIntegrationTest`; the catalogue test fails on a key between tenant tables
    without the pair). With `tenant_id` first, the parent key `(tenant_id, id)` walked "newest first"
    ahead of the list's own index on the test data (`TodoIndexIntegrationTest`): last, it serves the key
    and no order;
  - a reference to a platform table (`users`, `tenants`) stays a plain key.
- **Not a finding (DATA-10):** the `tenant_id → tenants` key of an owner-scoped table has no index
  leading with `tenant_id`: tenants are closed, never deleted, so the key is never checked from the
  parent side. Tenant deletion (later) adds the indexes it needs.
- **Detect:** `UNIQUE (key)` or `UNIQUE (owner_id, name)` without `tenant_id`; a tenant table's
  `FOREIGN KEY (parent_id) REFERENCES parent (id)`; an extra `(owner_id)` index beside `(owner_id,
  tenant_id, …)` (it took the list plans, with a sort, in the todo index tests).
- **Severity:** Critical for a foreign key that can cross tenants; Medium for a key or index without
  the tenant.

### TEN-34: [row] Tenant data comes from listeners, never from changesets
- **Check:** a changeset can't seed a tenant's rows: none exists when it runs, and the forced policy
  binds the owner too (`RowSecurityCatalogIntegrationTest`). A module seeds a new tenant's defaults in
  a `TenantProvisioned` listener (golden `TodoAttachmentPolicySeed` through `Uploads.definePolicy`),
  which runs under the tenant's scope in its activating transaction: `TenantProvisioning` activates an
  approved tenant right after the approval commits, in a new transaction opened inside
  `TenantScope.runAs` (the approving one has no tenant, and the tenant is fixed when a transaction
  begins). A migration across existing tenants' rows lifts `FORCE` for its own transaction
  (`docs/tenancy-runbook.md`, section 4; proven in the catalogue test). A failed provisioning leaves the
  tenant PENDING and logs it; approving again retries.
- **Detect:** `INSERT` into a tenant table in a changeset; a seed listener on `TenantCreated` or
  `TenantApproved` (no tenant scope yet, or no committed tenant); seeding inside the approving
  transaction; `NO FORCE` without `FORCE` again in the same changeset.
- **Severity:** High.

### TEN-35: [row] The platform's tables are named, each with its reason
- **Check:** `RowSecurityCatalogIntegrationTest.PLATFORM_TABLES` lists the control plane: `users`,
  `refresh_tokens`, `tenants`, `tenant_status_changes`, `tenant_memberships`, `platform_users`,
  `platform_audit` and Liquibase's tables. `tenant_memberships` and `refresh_tokens` carry a
  `tenant_id` but stay platform tables: login and "my tenants" read a user's memberships across
  tenants before one is chosen, and a refresh reads its token before it knows the tenant. Code reads
  them by user or by token, never as "all rows". A project's own platform table joins the list with
  its reason, in the same change.
- **Detect:** a table added to the list without a reason, or to make the guard pass; a tenant-plane
  endpoint reading `tenant_memberships` without the caller's tenant in its predicate.
- **Severity:** High.

### TEN-36: [row] Operators that aren't leakproof lose their index under a policy
- **Check:** PostgreSQL evaluates the policy before any user condition that isn't leakproof, so such
  a condition can't be an index condition and filters the tenant's rows instead:
  - **measured** (E3, 200 tenants x 1,000 rows): a jsonb `@>` with a GIN index read 1,016 buffers as the
    app role (a filter over the tenant's rows), 26 as a superuser (`BitmapAnd` with the GIN index);
    `ILIKE` became a filter after the tenant index; the reference's plan overlap check (`&&` on a
    range) leaves the exclusion constraint's GiST index for a btree walk of the owner's plans
    (`PlanIndexIntegrationTest`'s row variant; the constraint itself still decides);
  - `pg_proc.proleakproof` (asserted by `RowIsolationIntegrationTest`): leakproof `uuid_eq`, `texteq`,
    `int8eq`, `timestamptz_lt`; not leakproof `jsonb_contains`, `textlike`, `texticlike`, `enum_eq`,
    `range_overlaps`. Statuses as `text` with a check constraint (DATA-07) stay leakproof; PostgreSQL
    enums don't.

  A search on such an operator scans the tenant's rows: fine for a bounded tenant. For a large one,
  `EXPLAIN` as the app role with the tenant set (runbook, section 5), and change the shape (a leakproof
  equality on a derived column) or add a measured, marked reader on a bypass role (decision 10).
- **Detect:** a GIN or GiST index whose only user is a non-leakproof operator on a tenant table;
  `EXPLAIN` evidence taken as a superuser; a PostgreSQL enum column filtered on.
- **Severity:** Medium (performance), High when a large tenant's list runs it on every request.

## Not built yet (phase 2)

Invitations (a membership for an email without an account; today staff and registration only add
existing or new owners); email verification of registrations and signups (no mail sending in the
pieces); deleting registrations left PENDING past `app.tenancy.pending-lifetime`; parallel tenant
migrations (one JVM runs Liquibase one schema at a time); impersonating a suspended tenant (refused
today, as every token issue for it); break-glass access and a support-access log the tenant reads;
per-tenant rate limits and quotas; a role per tenant schema.
