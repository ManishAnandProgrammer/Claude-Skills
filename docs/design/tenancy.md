# Row-based tenancy: design (2026-11-14, draft for the owner)

An optional bootstrap piece, `tenancy`, like `media`: a small, solid first setup that a project
extends as it grows. It comes from the tenancy discussion and two real systems:
- ERP's operator endpoints, protected by a secret key;
- DynamicForm's super admin modelled as a tenant (`docs/refactoring-plan-2026-11.md` there).

**The goal:** a project that starts with this piece never has to undo its tenancy model. Four
properties must hold from day one, because each is expensive to add later:
- the database itself refuses cross-tenant access;
- the platform and its tenants are separate worlds;
- every piece of work knows its tenant;
- the tests prove all three.

**Also in scope:** schema-per-tenant, as the second answer to the same bootstrap question (see
*Schema-per-tenant mode*). **Out of scope:** a database per tenant, and microservices. The microservices rules are written down in *Later*, but not built.

## Decisions and why

### The model

1. **One schema, and a `tenant_id uuid NOT NULL` on every tenant-owned table.** One migration run,
   one connection pool, and cross-tenant reporting stays possible. It is the model most SaaS
   products start with: PostgreSQL row-level security (RLS) is designed for it, and the AWS SaaS
   guidance calls it the "pool" model.

2. **Two layers, each with its own job.**
   - **Hibernate `@TenantId` is the convenience layer.** Hibernate ships it; no dependency. It
     fills `tenant_id` on insert and adds `tenant_id = ?` to every entity query. Repositories and
     generated code never mention the tenant, and the predicate lets the planner use indexes that
     lead with `tenant_id`.
   - **RLS is the barrier.** It catches what Hibernate doesn't filter: native SQL, a missed path, an
     SQL injection.

   Either layer alone is weaker:
   - with RLS alone, every query relies on the policy for its predicate, and non-leakproof
     operators then lose their index (in DynamicForm, a jsonb `@>` went from 0.45 ms to 140 ms);
   - with `@TenantId` alone, one native query leaks.

3. **The tenant comes only from the verified access token**, from a `tenant_id` claim. It never
   comes from a header, a path segment or a body field. Tenant-plane URLs carry no tenant
   (`/api/v1/todos`, not `/api/v1/tenants/{id}/todos`). So a client can't ask for another tenant,
   and there is no ID-guessing surface to defend. With an external identity provider (Keycloak),
   one class maps the provider's claim to the same `TenantContext`. Nothing else changes.

### Getting the tenant into the database

4. **The tenant is set when the transaction gets its connection, so borrowing stays lazy.**
   - **The hook:** Hibernate's `MultiTenantConnectionProvider.getConnection(tenantId)`, a small
     `TenantConnectionProvider` over the Hikari `DataSource`. Hibernate calls it only when the first
     statement needs a connection.
   - **What it does:** it borrows the connection, then runs
     `select set_config('app.tenant_id', ?, true)` as the physical transaction's first statement.
   - **Why this keeps lazy borrowing:**
     - with `auto-commit=false`, that statement opens the database transaction;
     - the setting is transaction-local, so the commit or rollback clears it;
     - the connection goes back to the pool clean, with no reset step that could be forgotten;
     - a transaction that never runs SQL never borrows a connection, as before.
   - **The cost:** one extra round trip per borrowed connection.

   **These alternatives are out, and why:**
   - **not an override of `JpaTransactionManager.doBegin`:** running SQL there borrows the
     connection at the start of every transaction, which loses lazy borrowing (DynamicForm does
     this);
   - **not an aspect:** aspects are ordered by hand and are easy to skip;
   - **not Hikari's `connectionInitSql`:** it runs once per physical connection, not per
     transaction, and under `auto-commit=false` the first borrower's rollback undoes it;
   - **not session-level `set_config(…, false)`:** a connection returned without a reset hands the
     tenant to the next borrower.

   **The policies** read `nullif(current_setting('app.tenant_id', true), '')::uuid`. On a reused
   session, `current_setting` returns `''`, not NULL.

   **No tenant means no rows** (fail closed). Work outside a transaction sees nothing.

   **Proven by E2** (see *Experiment results*):
   - **Spring's default borrows eagerly for read-only transactions.** `HibernateJpaDialect`
     borrows at `begin` in Hibernate's `DELAYED_ACQUISITION_AND_HOLD` mode so it can call
     `setReadOnly`. Together with the hook, that loses both lazy borrowing and the database's
     read-only flag. So the piece sets
     `hibernate.connection.handling_mode: DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION`, and
     the hook marks the transaction read-only itself in the same statement:
     `select set_config('app.tenant_id', ?, true), set_config('transaction_read_only', 'on', true)`,
     when `TransactionSynchronizationManager.isCurrentTransactionReadOnly()`.
   - **What that mode costs:** per-transaction isolation levels. Spring then refuses
     `@Transactional(isolation = …)`; the reference uses none.
   - **A session needs a tenant to open at all.** Hibernate refuses with "no tenant identifier
     specified". Control-plane work (login, platform endpoints, the job that lists tenants) runs
     under a `NO_TENANT` sentinel (`new UUID(0, 0)`): the hook leaves the setting empty, tenant
     rows read as 0, and a tenant write is refused by RLS. Never `isRoot`.
   - **The tenant is fixed when the transaction starts.** Changing it inside a transaction is
     ignored by both layers, which is consistent and safe. Learning it inside fails. So
     `TenantScope.runAs` wraps the call into the `@Transactional` method, never the reverse.

   **The rule in the skill:** set the tenant (and the read-only flag) when the connection is
   borrowed, transaction-local, in release-after-transaction mode. Never run SQL in `doBegin`.

5. **Three database roles,** created by a runbook step (roles are cluster-level, so a restore needs
   them first), never by Liquibase:

   | Role | Used by | Rights |
   |---|---|---|
   | `<app>_owner` | Liquibase only | Owns the tables. RLS is `FORCE`d, so the owner is subject to it too, unless a data migration deliberately turns it off for its own transaction |
   | `<app>_app` | the application pool | `NOBYPASSRLS`, not an owner, DML grants only (`ALTER DEFAULT PRIVILEGES` in a changeset, so every new table is granted) |
   | `<app>_system` | later, only when a job needs it (decision 10) | `BYPASSRLS`, read-mostly grants |

   **No policy has an "or bypass" clause** based on a setting the app role can change itself. That
   would make RLS no barrier to SQL injection, and it wrecks the planner's estimates (DynamicForm
   has 27 such policies).

### The schema

6. **Shape rules for every tenant-owned table**, checked by crud-gen's pre-flight:
   - `tenant_id uuid NOT NULL REFERENCES tenants(id)`;
   - RLS `ENABLE` + `FORCE`, with one policy `USING (...) WITH CHECK (...)`, in the **same
     changeset** that creates the table;
   - unique constraints lead with `tenant_id`, e.g. `(tenant_id, lower(trim(name)))`;
   - list indexes lead with `tenant_id`, then the owner, the sort keys and `id`;
   - a reference between tenant tables is a **composite foreign key**
     `(tenant_id, parent_id) → parent(tenant_id, id)`, backed by `UNIQUE (tenant_id, id)` on the
     parent. So the database itself refuses a row that points into another tenant;
   - ids stay globally unique UUIDv7. No per-tenant id sequences.

### Two worlds: control plane and tenant plane

7. **The control plane manages tenants; the tenant plane is the product.**
   - **`tenants`** (control plane, not under RLS):
     - columns `id`, `name`, `status`;
     - statuses `PENDING → ACTIVE ⇄ SUSPENDED → CLOSED` (and `PENDING → REJECTED`), as an enum with a check constraint;
     - a tenant reads its own row only through `TenantService`.
   - **Platform staff are separate users with no tenant:**
     - a `platform_users` table, their own login, and tokens with `aud=platform` and no
       `tenant_id`;
     - their endpoints live under `/api/v1/platform/**`;
     - a second `SecurityFilterChain` accepts only platform tokens there, and only tenant tokens
       everywhere else.

     So a platform token can't call a tenant endpoint by mistake, and the reverse is just as
     impossible. This removes DynamicForm's root cause: there, the super admin is a tenant, and
     special cases spread through the code.
   - **Operator work is endpoints for authenticated platform staff,** with roles and an audit row.
     It is never a shared secret key (ERP's approach):
     - create, suspend, reactivate and close a tenant;
     - slow work, such as importing a tenant's data or rebuilding a cache, is a job row that the
       endpoint creates and a scheduler runs.

8. **Identity: global users with memberships** (owner decision O1).
   - `users` holds the identity: email unique globally, password.
   - `tenant_memberships(tenant_id, user_id, role)` is a tenant table under RLS. The role lives on
     the membership: the same person can be ADMIN in one tenant and MEMBER in another.
   - An access token is for **one** tenant. Login picks the user's last-used membership, and
     `POST /api/v1/auth/switch-tenant` issues tokens for another one.

   It's more than "a user belongs to one tenant", but converting users-per-tenant to memberships
   later is the migration nobody finishes. With memberships from day one, invitations, several
   workspaces and per-tenant roles are additions, not rewrites.

9. **Tenant status is checked when tokens are issued** (login, refresh, switch). So a suspension
   takes effect within one access-token lifetime (15 min). The rule file states that bound
   (REST-15). An unknown status is a refusal (SEC-06). A per-request cached check is for later,
   when a project needs immediate suspension (owner decision O4).

### Work off the request path

10. **Every unit of work carries its tenant.**
    - **Jobs:** a job runs per tenant through `TenantScope.runAs(tenantId, work)`. Each tenant gets
      its own transaction, so the tenant is set exactly as on a request. For example, media
      verification loops over the active tenants and handles a batch for each, which also keeps
      one big tenant from starving the rest. The `BYPASSRLS` pool comes only when a job needs a
      single cross-tenant statement for performance, measured, behind a marker annotation and an
      ArchUnit rule (ARCH-12) (owner decision O3).
    - **Outbox rows and messages** carry `tenant_id`; the consumer runs them under `runAs`.
    - **Caches** have the tenant in their key.
    - **Logs:** the MDC carries `tenantId`.
    - **Metrics are never tagged by tenant** (unbounded cardinality). Per-tenant usage is a
      metering table, later.
    - **Media:** keys become `{tenantId}/{purpose}/{uuid}`, so one tenant's files can be exported
      or deleted, and `media_files` is a tenant table. Upload policies stay platform-wide for now.

### Staff access to tenant data

11. **Platform staff can act as any tenant and edit across tenants (owner decision O6, 2026-11-14).**
    This is the default. An application that needs more adds a gate in front of it; it doesn't
    redesign the access.
    - **Impersonation:**
      - `POST /api/v1/platform/tenants/{id}/impersonations` issues a short-lived tenant token
        (`tenant_id` = that tenant), with `act: {sub: <platformUserId>}` (RFC 8693) and a role
        chosen by the platform user (by default the tenant's ADMIN);
      - the tenant plane accepts it like any tenant token;
      - `CurrentUser` exposes the actor, so audit columns and logs record both the impersonated
        role and the real person.
    - **Direct cross-tenant edits:** a platform endpoint names its tenant in the path
      (`/api/v1/platform/tenants/{id}/...`) and runs the tenant module's service under
      `TenantScope.runAs(id, …)`. It never reads another tenant's rows in one transaction.
    - **Always on, even by default:** an append-only `platform_audit` row for every
      impersonation and every direct edit (who, which tenant, what, when). It's cheap, and it is
      what a later consent or ticket step builds on.
    - **The gate:** a `SupportAccessPolicy` interface called before an impersonation is issued.
      The default allows it. A strict application plugs in a consent check (the tenant's admin
      approved, time-boxed) or a ticket reference, without changing the endpoints.
    - **Break-glass and a support-access log the tenant can read** come later. They are what a
      strict policy adds.

### Provisioning

12. **Creating a tenant is one transaction:**
    - the tenant row (ACTIVE);
    - the owner's membership, or an invitation when the owner has no account yet;
    - each module's defaults, seeded under `TenantScope.runAs(newTenantId, …)` by a synchronous
      `TenantCreated` event listener in the same transaction. The tenant module doesn't know the
      other modules (ARCH-02).

    **Registration is a bootstrap choice (O5, decided):**
    - **Staff only:** platform staff create the tenant, already ACTIVE. No public endpoint.
    - **Self-registration with approval:** `POST /api/v1/tenant-registrations` (public, rate
      limited, email-verified like signup) creates the tenant as PENDING with its owner. Staff
      activate or reject it. The owner's login answers 403 `TENANT_NOT_ACTIVE` with the status, so
      the client can show "awaiting approval". A job deletes registrations still unverified or
      PENDING after `app.tenancy.pending-lifetime`, which keeps abandoned and junk registrations
      out of staff's queue.

    **One way in:** `TenantService.activate(tenantId, ActivationSource)`. Staff call it now. A
    billing module's payment webhook calls it later, and the source is recorded in an audit row.
    Adding payments then adds a caller, not a second activation path.

    **No plans or subscriptions in the piece.** `tenants` holds only the lifecycle. A future billing
    module owns plans, payment state and grace periods, and decides when to call `suspend`. The
    tenant status never mirrors the payment status. A failed card isn't a suspension until billing
    says so.

    The registration endpoint is copied only when chosen (markers, like the other pieces), so a
    staff-only project has no public tenant endpoint to attack.

## The flow

```
Request:   JWT (tenant_id) ─▶ TenantContext ─▶ TenantIdentifierResolver ─▶ @TenantId (insert + filter)
                                                  └─▶ first SQL statement: TenantConnectionProvider
                                                       borrows lazily, set_config('app.tenant_id', …, true)
           PostgreSQL policy: tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid

Job:       for tenant in tenants.activeIds():  TenantScope.runAs(tenant, () -> service.doBatch())
Platform:  JWT (aud=platform) ─▶ /api/v1/platform/** ─▶ TenantService (tenants table, no RLS)
```

## Names (golden code, reference-app)

| Where | Classes |
|---|---|
| `common/tenancy` | `TenantContext` (reads the claim; `id()`, `findId()`), `TenantScope` (`runAs`), `TenantConnectionProvider` (Hibernate's connection hook: lazy borrow + transaction-local tenant), `TenantIdentifierResolver` (Hibernate's resolver over `TenantContext`), `TenancyConfig` |
| `tenant` (module) | `Tenant`, `TenantStatus`, `TenantService` (`create`, `suspend`, `reactivate`, `close`, `activeIds`), `TenantCreated` event, `PlatformTenantController` + `PlatformTenantApi`; api package `Tenants` for other modules |
| `user` (sign-in piece, tenancy markers) | `TenantMembership`, `MembershipRole`, the token's `tenant_id` claim, `switch-tenant`; `PlatformUser` + its login |
| `todo` (tenancy markers) | `tenantId` with `@TenantId` on each entity; composite foreign keys in the changesets |
| changesets | `0011-tenants.sql` (tenants, memberships, platform users, grants), and `tenant_id`, RLS and composite foreign keys inside the todo changesets under SQL markers (needs `bootstrap-copy.sh` to filter `*.sql` too) |
| config | `config/tenancy.yml`: claim name, the `switch-tenant` limits, `app.jobs.*.per-tenant-batch-size` |
| errors | `TENANT_NOT_ACTIVE` 403 at token issue, `TENANT_NOT_FOUND` 404 (platform), `MEMBERSHIP_NOT_FOUND` 404 (switch) |

## Tests (part of the piece)

- **The test database runs with the real roles:** a Testcontainers init script creates the three
  roles, Liquibase migrates as the owner, and the app pool connects as `<app>_app`. Tests that run
  with RLS bypassed prove nothing; that is DynamicForm's 1,624-method gap.
- **The test base:** `newTenant()` and `newMember(tenant, role)`; every test works in its own fresh
  tenant, which also isolates tests that share one database.
- **Catalogue test:** every table with a `tenant_id` column has RLS enabled and forced, a policy on
  `app.tenant_id`, a unique `(tenant_id, id)` when it is referenced, and composite foreign keys.
  The allow-list of control-plane tables is written out with a reason for each.
- **Raw JDBC test, as the app role:**
  - with no tenant set, it reads 0 rows;
  - with tenant A set, it reads only A's rows;
  - inserting with B's `tenant_id` is refused;
  - a child row pointing at another tenant's parent is refused.
- **Per entity (crud-gen generates it):** tenant B's GET, PUT and DELETE of A's record answer 404,
  and B's list never shows A's rows.
- **Plane tests:**
  - a platform token on a tenant endpoint → 401;
  - a tenant token on `/platform/**` → 401;
  - a suspended tenant's login or refresh → 403 `TENANT_NOT_ACTIVE`.
- **ArchUnit:**
  - only `TenantConnectionProvider` writes `app.tenant_id`, and only `TenantScope` switches the tenant;
  - request records and controller parameters have no `tenantId`;
  - tenant-plane controllers don't depend on platform services, and the reverse.

## Skill changes that come with it

- **house-review:** a new `references/tenancy.md` with TEN-01..n (the rules above, each with
  Detect hints and severities: Critical for any cross-tenant read or write path), loaded only when
  the profile says `tenancy: row`. META-09 points to it.
- **crud-gen:**
  - a profile key `tenancy: none | row`;
  - the pre-flight checks of decision 6;
  - `@TenantId` on generated entities;
  - the generated cross-tenant tests;
  - a native query in a repository gets `AND tenant_id = :tenantId` (RLS backs it up, but the
    planner needs the predicate).
- **bootstrap.md:**
  - question 6's tenancy line becomes a real question with the piece;
  - the piece row needs `sign-in` (or the external identity provider's claim mapping);
  - runbook steps: create the roles, restore order, and `EXPLAIN` as the app role with the tenant
    set.
- **verify.sh:** new cases for core + sign-in + tenancy, and with media.

## Experiment results (2026-11-14)

**Setup:** Spring Boot 4.1.2, Hibernate 7.4.13, Spring 7.0.10, HikariCP. The reference app's
pool and JPA settings (`auto-commit: false`, `provider_disables_autocommit: true`).
PostgreSQL 18 with an owner role and a `NOBYPASSRLS` app role; 200 tenants × 1,000 rows.
Borrows were counted under the pool, and Hikari's active count was read inside each transaction.
The runs used TRACE logging for Spring transactions, Hibernate JDBC and Hikari.

**The code:**
`/tmp/claude-1001/-home-manu-IdeaProjects-news-pulse/af09d146-98d3-4b37-a88e-ebacceb12a2d/scratchpad/tenexp`
(scratch, not kept).

**E2: connection borrowing**

| Mode | Read-only tx: borrowed at | Read-only tx without SQL | Database read-only | Write tx borrowed at |
|---|---|---|---|---|
| Spring default (`…_AND_HOLD`), no tenancy | **begin** | **borrows** | on | first statement |
| default + Spring's `LazyConnectionDataSourceProxy` | first statement | no borrow | on | first statement |
| `…_RELEASE_AFTER_TRANSACTION`, no tenancy | first statement | no borrow | **off** | first statement |
| default + tenant hook | **begin** | **borrows** | **off** (a write was accepted) | first statement |
| default + lazy proxy + tenant hook | **begin** | **borrows** | **off** | first statement |
| **release mode + tenant hook setting read-only** | **first statement** | **no borrow** | **on** (a write is refused) | first statement |

With the hook in release mode, all of these were proven:
- **The tenant is cleared after the transaction:** the setting is set inside, and after commit
  and after rollback every pooled session reads `''`.
- **Two transactions on one thread** switch tenants correctly.
- **`REQUIRES_NEW` under another tenant** gets its own connection and tenant, and the outer
  transaction is unchanged afterwards.
- **`NOT_SUPPORTED`:** the repository's own transaction is tenant-scoped, and a statement with no
  transaction leaves the connection clean.
- **`@Retryable`:** each attempt borrows again and gets the tenant.
- **A foreign `tenant_id` insert** is refused by RLS `WITH CHECK`.
- **With no tenant,** the session refuses to open. With the `NO_TENANT` sentinel, control-plane
  reads work, tenant rows read as 0, and a tenant write is refused.
- **A tenant change inside a transaction** is ignored by both layers.

**Cost** (local Docker, 20,000 read-only transactions with one query each, three runs):

| Mode | µs per transaction |
|---|---|
| default | 266–290 |
| lazy proxy | 279–309 |
| release mode | 273–289 |
| hook | 324–348 |

The hook adds one round trip per transaction that touches the database, about 55 µs locally. In
production that is about one network round trip.

**Finding for every project, tenancy or not:**
- **The reference's `db.yml` comment is wrong for read-only transactions.** It says "a connection
  is borrowed at the first statement". Under Spring's default, every read-only transaction (class
  level on every service) borrows at begin.
- **Without tenancy, `LazyConnectionDataSourceProxy` fixes it and keeps the read-only flag.** It
  ships with Spring, so it needs no dependency.
- **Verified in a copy of the reference app**, with the proxy as a `BeanPostProcessor` around
  Boot's `HikariDataSource`:
  - **Borrowing:** a read-only transaction borrows at the first statement; one without SQL borrows
    nothing (Hikari's acquire count was 2 with the proxy, 3 without).
  - **Read-only flag:** still on in the database.
  - **Health:** `ConnectionPoolHealthIndicator` still unwraps to Hikari and reports UP, and
    readiness is UP.
  - **Metrics:** the Prometheus scrape has the same 20 `hikaricp_*` and `jdbc_connections_*`
    series, with the pool tag.
  - **Tests:** 701 pass. One test had to change: `PoolExhaustionIntegrationTest` held connections
    through `dataSource.getConnection()`, which now returns lazy handles that never borrow, so it
    takes them from the unwrapped Hikari pool instead.
  - **The rule this gives:** code that needs a real connection without running a statement asks
    the pool itself.

**E1: what `@TenantId` filters on its own** (a table without RLS, under tenant A):
- **Filtered, with `tenant_id = ?` in the SQL:**
  - `find` and `findById` of B's row → null or empty;
  - JPQL count and select;
  - Criteria `construct` projections;
  - joins, with the predicate on the joined table too;
  - JPQL bulk `update` and `delete`.
- **Cross-tenant references throw:** `getReferenceById(B)` and a lazy parent that belongs to B both
  throw `ObjectNotFoundException`. The composite foreign key (decision 6) keeps that data from
  existing.
- **Inserts:** the tenant is filled automatically. An entity carrying another tenant's id is
  refused ("assigned tenant id differs from current tenant id").
- **Not filtered:** native SQL. RLS covers it, and native queries add the predicate themselves.

**E3: plans under the policy** (as the app role with the tenant set):
- **List pages use the `(tenant_id, title, id)` index:**
  - with the policy alone, as an Index Cond on `current_setting`, stable;
  - with Hibernate's predicate;
  - in the generic plan of a server-prepared statement.
- **Non-leakproof operators lose their own index.** For jsonb `@>`, the GIN index is not used as
  the app role, even with the tenant predicate: 1,016 buffers, a filter over every row of the
  tenant. As superuser it uses `BitmapAnd` with the GIN index: 26 buffers. `ILIKE` likewise
  becomes a filter after the tenant index.
- **`pg_proc.proleakproof`:**
  - **leakproof:** `uuid_eq`, `texteq`, `text_lt`, `int8eq`, `date_lt`, `timestamptz_lt`;
  - **not leakproof:** `jsonb_contains`, `textlike`, `texticlike`, `enum_eq`.

  Statuses as `text` with a check constraint (DATA-07) stay leakproof; native PostgreSQL enums
  don't.
- **The rule:** a search on a non-leakproof operator scans the tenant's rows. Fine for a bounded
  tenant. For a large one, `EXPLAIN` as the app role, and add a marked bypass reader with an
  explicit tenant predicate only when measured.

## Schema-per-tenant mode (2026-11-14)

Bootstrap asks one question: **tenancy `none | row | schema`**. Schema mode is written fresh on
the shared design. It takes the parts of ERP's schema-per-tenant code that held up, and avoids
ERP's three traps:
- `SET SESSION search_path` leaks to the next borrower;
- an unknown tenant becomes `public`;
- a tenant set inside an open transaction is ignored.

The ERP study and E4–E7 below are the evidence.

### What is shared with row mode (unchanged)

- The tenant comes from the verified token.
- `TenantContext` and `TenantScope.runAs`, the `NO_TENANT` sentinel, and the platform (decisions
  7–12):
  - `tenants`, `platform_users`, global users with memberships;
  - staff endpoints instead of secret keys;
  - one way in, `activate`;
  - registration as a bootstrap choice.
- Release-after-transaction mode with a connection hook (decision 4).
- Tests in a fresh tenant, jobs per tenant, the tenant in cache keys and the MDC.

### What differs

| Topic | Row mode | Schema mode |
|---|---|---|
| Isolation | `@TenantId` + RLS on `tenant_id` | One schema per tenant: `t_<tenant id without dashes>`, derived and quoted (`quote_ident`), never from input. Tenant tables have no `tenant_id` |
| The hook's statement | `set_config('app.tenant_id', ?, true)` | `set_config('search_path', quote_ident(?), true)` with the read-only flag; one parameterised statement |
| No tenant | Policies see `''`: 0 rows | The role's default `search_path = platform`: tenant tables don't exist (`relation "notes" does not exist`), platform tables work |
| Platform entities | Unqualified | `@Table(schema = "platform")`, so they resolve under any `search_path` |
| Login | Users and memberships in one schema | Users and memberships in `platform`; login finds the tenants in one query (ERP needed a Redis username directory and an all-schema scan) |
| Migrations | One changelog | Two masters: `db/changelog/platform.yaml` (Spring's Liquibase at startup) and `db/changelog/tenant.yaml`, run per schema by a `TenantMigrations` job, with `liquibaseSchema` = the tenant schema |
| Hibernate validation | `ddl-auto: validate` as is | `getAnyConnection` points at a **`tenant_template` schema** that the migration job keeps current. Hibernate validates that schema only |
| Export, delete, restore one tenant | A query per table | `pg_dump -n`, `DROP SCHEMA … CASCADE` after retention, restore one schema |
| Scale | Millions of tenants | Thousands. Tens of thousands strain the catalog, `pg_dump` and autovacuum; row mode is the answer there (the bootstrap question says so) |
| Defence in depth | RLS | One app role reaches every schema. ArchUnit plus a test: no native SQL names a schema (`t_` or `platform.` outside platform entities). A role per tenant is a later option |

### Provisioning and migrations (from E6)

- **Provisioning a tenant** is a job row, run once, resumable:
  1. `CREATE SCHEMA` and grant usage to the app role;
  2. Liquibase runs the tenant master into the new schema; this first run creates Liquibase's own
     lock table;
  3. seed the tenant's defaults;
  4. ACTIVE.

  The first migration of a schema runs **only** in provisioning. Two migrators racing on a
  brand-new schema fail while creating Liquibase's own tables (E6e, 3 of 3 runs).
- **At deploy:**
  - `TenantMigrations` runs once, on one instance, under a PostgreSQL advisory lock. It
    migrates the template and then every tenant schema, with bounded parallelism.
  - It records each schema's last changeset in `platform.tenant_migrations`.
  - A failing schema is recorded and skipped, and the others continue. A rerun applies only
    what's missing (E6c/E6d).
- **Not at every instance's startup.** Liquibase's own lock does serialise two migrators on one
  schema, but the waiter polls every 10 s (E6f). N instances times M schemas would stall deploys.
- **Changesets are expand-then-contract.** Schemas are migrated one after another, so old code
  runs against new schemas and new code against old ones during the rollout.
- **Health:** `tenant_migrations` behind the expected changeset counts as "migrations lagging".
  It is readable on the platform API, never through `/health`.

### Experiment results E4–E7 (2026-11-14)

**Setup:** same as E1–E3. The role's default `search_path` is `platform`; three tenant schemas
hold 10, 20 and 30 rows. The code lives with the E1–E3 code.

**E4: the transaction-local `search_path` hook**, compared with ERP's approach (`setAutoCommit(true)`,
`setSchema`, `setAutoCommit(false)` on borrow, no reset):

| Check | Transaction-local hook | ERP's approach |
|---|---|---|
| Read-only transaction borrows at | first statement | **begin** (Spring's default mode) |
| Read-only transaction without SQL | no borrow | **borrows** |
| `search_path` inside, rows seen | tenant schema, its rows | tenant schema, its rows |
| PostgreSQL read-only; a write in it | on; refused | on; refused |
| **Plain JDBC borrower after commit** | `platform`; tenant tables invisible | **previous tenant's schema; reads its rows** |
| Plain JDBC borrower after rollback | `platform` | **previous tenant's schema** |
| `REQUIRES_NEW` under another tenant, outer afterwards | own connection and schema; outer unchanged | same |
| Two transactions on one thread, retry, `NOT_SUPPORTED` | correct | correct |
| Tenant changed inside a transaction | ignored | ignored |
| `NO_TENANT` | platform reads work; `relation "notes" does not exist` | n/a (ERP uses `public`) |

**E5: plan caching across schemas.**
- **Setup:** pool size 1, `prepareThreshold=1`; `pg_prepared_statements` confirmed the
  statements were prepared on the server.
- **Through Hibernate:** 60 transactions alternating three tenants, a native and a JPQL query
  each: **0 wrong results**.
- **Raw JDBC:** one `PreparedStatement` object reused across `search_path` changes: correct
  every time. PostgreSQL replans a cached plan when `search_path` changes.
- **Generic plans:** the same with `plan_cache_mode=force_generic_plan` set through the driver
  options.
- **ERP's approach** left **78** server-prepared statements on one connection, one per distinct
  `SET SESSION search_path TO '<schema>'` text. The hook's one parameterised statement left 9.

**E6: Liquibase per schema** (as the owner role, master YAML including numbered SQL files):

| Run | Result |
|---|---|
| v1 into 50 new schemas | 4.5 s (about 90 ms each); `databasechangelog` and the lock table inside each schema |
| v1 again, nothing to do | 1.3–1.4 s (about 28 ms per schema, at every deploy) |
| v2 with schema 25 broken on purpose | only schema 25 fails (`column "priority" already exists`), its lock is released, the 49 others are migrated |
| v2 after fixing schema 25 | only the missing changeset runs |
| two migrators, **new** schema | one fails (`duplicate key … pg_type_typname_nsp_index`), 3 of 3 runs; changesets applied once |
| two migrators, schema with its lock table | both succeed; the second waits about 10 s (Liquibase polls its lock) and finds nothing to do; applied once |

- **Changesets are identified by id, author and file path.** Moving a changeset to another file
  re-runs it. My first attempt with two separate files proved it: every schema failed with
  "relation already exists". Tenant changesets live in numbered files included by one master.

**E7: `ddl-auto: validate`** goes through `getAnyConnection`:
- in `platform` it fails with "missing table [notes]";
- in `tenant_template` it passes;
- a drifted template (`title` renamed) fails with "missing column [title] in table [notes]".

Hibernate validates only that one schema. The other tenant schemas are covered by
`tenant_migrations` (every schema reached the expected changeset), not by Hibernate.

## Later (rules and notes, not in the first setup)

- **Support impersonation and break-glass access** (decision 11).
- **The `BYPASSRLS` system pool** (decision 10).
- **Tenant export and deletion:** a catalogue-driven job per table, S3 prefix deletion, and a
  retention period after CLOSED.
- **Per-tenant rate limits and quotas** against noisy neighbours.
- **Per-tenant upload policies** and configuration (the `metadata` piece's row-based variant).
- **Microservices:**
  - the tenant comes from the verified token, or from the message envelope for async work;
  - one small library propagates it;
  - a tenant service owns the lifecycle and publishes events that other services use to set
    themselves up;
  - cross-tenant reporting reads a separate reporting store;
  - each service applies the same RLS rules to its own database.
- **A Keycloak guide** (`guides/keycloak.md`), when the owner gives the version: an organization
  or attribute claim mapped to `TenantContext`. The rules stay independent of the identity
  provider.

## Decided (2026-11-14)

O1 global users with memberships · O2 separate `platform_users` with their own token audience ·
O3 jobs per tenant through `TenantScope` (no `BYPASSRLS` pool in the first setup) · O4 tenant
status checked at token issue (suspension effective within one access-token lifetime).

O5 registration: staff only, or self-registration with staff approval (decision 12); billing later
only adds a caller of `activate`.

O6 staff access (decision 11): impersonation and direct cross-tenant edits by default, always
audited; a `SupportAccessPolicy` gate (default allow) for applications that need consent or
tickets.
