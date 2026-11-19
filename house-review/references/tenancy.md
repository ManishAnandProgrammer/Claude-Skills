# Tenancy (TEN-xx): optional, for projects with a tenancy piece

One application serving many organisations (tenants) whose data must never meet. Apply these rules
only when the review profile says `tenancy: row` or `tenancy: schema`. Rules marked **[schema]** apply
to schema-per-tenant only, **[row]** to row-based tenancy only; the others apply to both.

Golden code: the skills repo's overlays, not the reference application (which runs without tenancy).
The shared layer `~/Documents/claude-skills/overlays/tenancy/src/main/java/com/example/app/`:
`common/tenancy/` (`TenantContext`, `TenantScope`, `TenantIdentifierResolver`, `TenantTaskDecorator`),
`config/PlatformSecurityConfig` (the platform chain), the `tenant` module (`TenantService` with
`approve`, `PlatformTenantController`, `PlatformAuditService`, `AllowAllSupportAccess`), `user/`
(`TenantMembership`, `MembershipService` with "my tenants" and the members list, `user/api/Members` +
`MemberView` (the facade other modules ask about a person), `PlatformUser`, `PlatformAuthService`,
`ImpersonationService`, `TokenService`, `TenantRegistrationService`),
`metadata/service/PlatformMetadataService` (a direct cross-tenant edit), `config/tenancy.yml`,
changeset `0012`. Schema per tenant, `overlays/tenancy-schema/`: `common/tenancy/`
(`TransactionLocalSchemaProvider`, `TenantSchemas`), `config/TenancyConfig`, `META-INF/orm.xml`, the
`tenant` module's `TenantProvisioningJob`, `TenantMigrations`, `TenantSchemaMigrator`,
`TenantTemplateMigration`, changeset `0013` and the masters `db/changelog/platform.yaml` and
`tenant.yaml`, `docs/tenancy-runbook.md`, `docker/postgres-init/`. Row mode, `overlays/tenancy-row/`:
`common/tenancy/TransactionLocalTenantProvider` (the hook), `common/persistence/TenantOwnedEntity`
(`@TenantId`), `config/TenancyConfig`, `tenant/service/TenantProvisioning` (activation after the
approval), changeset `0013-row-security` (the app role's grants), `0016-tenant-id-no-default`, with
`media` `media/api/UploadLimits` and `media/repository/MediaFileRepository` (native statements that name
their tenant), with `metadata` `metadata/repository/CounterRepository`, with `example`
`todo/service/TodoAttachmentPolicySeed` (a tenant's defaults on `TenantProvisioned`),
`docs/tenancy-runbook.md`, `docker/postgres-init/`; the tenant tables' `tenant_id`, keys and policies
are `tenancy-row` blocks in the reference's own changesets `0002`–`0011` and entities, `0017-members`,
with `example` `0018-owners-reference-members`, with a piece that brings tenant tables
`0019-replica-identity`. Both modes: changesets `0015-platform-users-read-only` and
`0020-tenant-members-list` (the members list's indexes), `config/PoolRoleCheck`,
`tenant/service/StaffTenantService`, `user/domain/Member` with `MemberRepository` (the tenant's own
members, TEN-17) and each mode's `0017-members` (schema mode's `0018-owners-reference-members` with
`example`).
Tests: `tenant/**`, `archunit/TenancyArchTest`, `todo/TodoTenantIsolationIntegrationTest`,
`support/IntegrationTest` (the test tenant, `@WithoutTestTenant`); row mode's
`tenant/row/RowIsolationIntegrationTest`, `tenant/row/RowSecurityCatalogIntegrationTest` (the guard
on the schema, the extraction guards included) and `tenant/row/NativeQueryTenantIntegrationTest` (the
guard on native SQL); schema mode's `tenant/schema/SchemaCatalogIntegrationTest` (no key leaves a tenant
schema); both modes' `config/PoolRoleCheckIntegrationTest`, `media/service/MediaJobsPerTenantTest`,
`media/mapper/MediaKeysPerTenantTest`, `metadata/CounterTenantIntegrationTest` and `user/`'s
`TenantMemberIntegrationTest`, `TenantMemberListIntegrationTest`, `TenantMemberIndexIntegrationTest`,
`MembersIntegrationTest`. In a bootstrapped project these sit at the same paths under
its own package. The token-path and seeding matrices with a test per cell: `tenancy-matrices.md`, beside
this file.

The flow: JWT (`tenant_id`) → `TenantContext` → Hibernate's resolver → the connection hook runs one
statement as the transaction's first (schema: `search_path`; row: `app.tenant_id`) → the commit or
rollback clears it. Jobs: `for tenant in tenants.activeIds(): try { TenantScope.runAs(tenant, work) }
catch (log, go on)`.
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
- **Check (staff deactivation):** a staff member leaves by `platform_users.deactivated_at` (the operator's
  SQL, as staff rows are; the audit key keeps their history, so the row is never deleted). Platform login
  refuses a deactivated member (401 `AUTH_INVALID_CREDENTIALS`, as an unknown email), the platform chain
  re-reads the row on every request (`ActiveStaffFilter`, one primary-key read: 401 `AUTHENTICATION_REQUIRED`
  from the next request on), and the impersonation issue re-reads it again. A platform token is never trusted
  for its lifetime. Tests: `StaffDeactivationIntegrationTest`.
- **Check (accounts):** with tenancy accounts are global, so only platform staff suspend or reactivate one
  (`GET /platform/users?email=` to find it, `PATCH /platform/users/{id}/status`, `AccountStatusService`, kept
  off the tenant plane by `TenancyArchTest`);
  never a tenant ADMIN. A suspension keeps every open tenant's last ACTIVE ADMIN (409
  `TENANT_MEMBER_LAST_ADMIN`, the tenant locked as a member change locks it). SEC-07 and TEN-08 for the rest.
- **Detect:** one decoder for both audiences; a `ROLE_SUPER_ADMIN` that bypasses tenant checks;
  operator endpoints behind a shared secret header; `isPlatform` branches in tenant-plane code; a platform
  chain without the staff re-read, or a staff "disable" checked only at login; an account suspension
  endpoint on the tenant plane.
- **Severity:** Critical.

### TEN-06: Tenant status is checked whenever tokens are issued
- **Check:** login, refresh and switch-tenant call `Tenants.requireActive` (403 `TENANT_NOT_ACTIVE`
  with `tenantStatus`; an unknown tenant is refused the same way). A suspension takes effect within
  one access-token lifetime (REST-15's bound, 15 minutes); with the `session-version` piece at once:
  the suspension raises every member's session version there in one statement, in its own transaction
  (`TenantDeactivated`, SEC-07), and the session version is per membership, so the members' tokens for
  other tenants stay valid. The check's refusal never dooms the caller's transaction
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
- **Check:** a change to memberships or roles (add, promote, demote, remove) authorises on the caller's
  membership **read under the lock that serialises those changes** (the tenant's row), never on the
  token's role alone. The token's role is up to one lifetime old, and a stale ADMIN token that adds its
  own account back, or promotes one of its accounts, makes the change permanent: the documented
  15-minute bound becomes forever. Reference: `MembershipService.lockAsCurrentAdmin` (403
  `ACCESS_DENIED`), tested by `TenantMemberIntegrationTest.aDemotedAdminsTokenManagesNoMore` and
  `aRemovedAdminCantComeBack`. A last-ADMIN count is no substitute: an add or a promotion never consults
  it.
- **Check:** adding a member by email is a lookup that answers whether an address has an account (and the
  person's name): it is rate limited per ADMIN account (`MemberRateLimits`, `app.rate-limit.members.add-per-account`,
  429 `RATE_LIMITED`) whenever the limiter is on, and the API text says it is unlimited when the limiter is
  off; any other endpoint that turns an email into a person takes the same limit. Reference
  `TenantMemberIntegrationTest.addsAreLimitedPerAdmin`. Invitations (later) replace the direct add.
- **Check:** **every path that issues a token** (login, refresh, switch-tenant, impersonation, and any
  later one: an invitation, a tenant-picker sign-in) re-reads at the issue what the token stands for:
  the account (share-locked and ACTIVE, first: `UserService.activeForTokenIssue`), the membership (still
  there), its role and session version, the tenant ACTIVE; for an impersonation the staff row too; nothing
  comes from the presented token beyond its own validity. A member removed and added again starts with new sessions: a
  refresh token issued before the current membership is refused by every path that issues from it,
  refresh **and** switch-tenant (the token's own membership checked before the target's; reference
  `TokenService.requireIssuedForThisMembership`, tested by `TenantMemberIntegrationTest
  .aReAddedMembersOldSessionStaysEnded` and `aRemovedMembersOldSessionDoesNotSwitch`). The paths, their
  checks and tests are one table in `tenancy-matrices.md` (*Every path that issues a token*), with the
  matrix *Every state × every token path* (removed member, demoted member, suspended account, deactivated
  staff, suspended tenant × login, refresh, switch-tenant, impersonation, each request): a new path or
  state adds its row or column and a test per cell.
- **Check:** `tenant_memberships` is a platform table (TEN-35), so no policy narrows it: every query for
  one tenant's members names the tenant itself (`findByUserIdAndTenantId(userId, tenantId)`,
  `countByTenantIdAndRole`, the members list's `TenantMembershipRepositoryImpl.findByFilter(filter,
  tenantId, …)`, the `Members` facade), and only login and "my tenants" read a user's memberships across
  tenants.
  The members test crosses tenants: another tenant's member is 404 `TENANT_MEMBER_NOT_FOUND`, and a
  person who is a member of two tenants is changed and removed in the caller's tenant only
  (`TenantMemberIntegrationTest.anotherTenantsMemberIs404`, `changesStayInTheCallersTenant`), and the
  list and a member's GET show the caller's tenant only (`TenantMemberListIntegrationTest
  .listShowsOnlyThisTenantsMembers`, `getOfANonMemberIs404`; `MembersIntegrationTest` for the facade).
- **Detect:** grep every caller of the token service's issue methods and of the refresh-token lookup
  (`usableToken`): each one reads the membership and the tenant status itself, and each path that takes a
  refresh token checks it was issued after the token's own membership; a path that takes the role or the
  tenant from the presented token; `users.tenant_id`; the role read from `users.role` into a tenant token or
  any response (`/users/me` carries no role with tenancy: the membership's is in `GET /users/me/tenants`);
  an endpoint that grants or keeps roles checked by its `@Requires` alone (the token's permissions, AUTHZ-10); a members
  service that takes no caller id; a membership lookup by user or by id alone on a tenant-plane path
  (it reaches the person's membership in another tenant).
- **Severity:** Medium; High for the role check of member changes (a removed ADMIN keeps control).

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
- **Check:** staff's create and approve answer the tenant as it is once their transaction committed
  (`StaffTenantService`, `NOT_SUPPORTED`, reads it after the call): in row mode it is ACTIVE by then,
  with the activation's version. A body mapped inside the approving transaction shows PENDING and the
  version before, and a client sending that version back gets 409.
- **Detect:** a second activation path; staff activating a tenant whose tables don't exist;
  provisioning that creates storage without re-checking the status; plans or payment state on
  `tenants`; a create or approve answer mapped inside the transaction that publishes `TenantApproved`.
- **Severity:** Medium.

## Work off the request path

### TEN-10: Every unit of work carries its tenant
- **Check:**
  - a job loops `Tenants.activeIds()` and runs each tenant's batch under `runAs`, each in its own
    transactions (one big tenant can't starve the rest). Each tenant's run is caught, logged with the
    tenant id and left to the next run: one tenant that fails (a query timeout in a big tenant) must not
    skip every tenant after it, run after run (golden `MediaVerificationJob`, `MediaCleanupJob`, tested
    by `MediaJobsPerTenantTest`). Housekeeping whose rows a suspended or closed tenant still holds (expired
    uploads) loops `Tenants.idsHoldingData()` (ACTIVE, SUSPENDED, CLOSED) instead;
  - `@Async` work inherits the tenant through the `TaskDecorator` (`TenantTaskDecorator`);
  - outbox rows and messages carry `tenant_id`, and the consumer runs under `runAs`;
  - caches have the tenant in their key; the MDC carries `tenantId`;
  - object-store keys start with the tenant (`{tenantId}/{purpose}/{uuid}`).
- **Detect:** a job that queries tenant tables without a scope (it sees nothing, or, in row mode with
  a bypass role, everything); a tenant loop without a catch per tenant; storage housekeeping limited to
  ACTIVE tenants; `@Async` without the decorator; a cache key without the tenant.
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
- **Check:** the app role only reads `platform_users` (changeset `0015`): staff accounts are the owner's
  to write (runbook section 2), and with the write right one SQL injection creates a staff account that
  impersonates every tenant. Tests seed staff as the owner (the Liquibase data source).
- **Check:** `PoolRoleCheck` stops startup unless the pool's role is plain **through its memberships
  too**: no superuser or `BYPASSRLS` role, and no table owner, among the roles it is a member of
  (`pg_has_role(current_user, …, 'MEMBER')`), and no write on `platform_users`. `GRANT <owner> TO <app>`,
  a common quick fix for a missing grant, leaves every catalogue column of the app role plain and hands
  it the owner's power to lift `FORCE` or drop a policy (`PoolRoleCheckIntegrationTest` proves each
  case is caught).
- **Detect:** the pool connecting as the owner or a superuser; `GRANT ALL`; `GRANT <owner> TO <app>`
  (or any role grant to the app role); a role check reading only `rolsuper`/`rolbypassrls`/`relowner` of
  the role itself; DML on `platform_users` for the app role; a policy with
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

## A tenant can leave: its data is a closed graph

A tenant that outgrows the shared database moves to one of its own: row mode by logical replication
filtered by its `tenant_id`, schema per tenant by `pg_dump -n <schema>`. Both work only when the tenant's
data references nothing outside itself but the register. These rules keep it so from the first table, so
an extraction is an operation, not a migration project (the tooling itself is not built yet, below).

### TEN-16: No foreign key from tenant data into the platform, but the register
- **Check:** a tenant table references, outside its own tenant's tables, only `tenants (id)` (row mode's
  `tenant_id`). A person is a row of the tenant's own `members` (TEN-17): `(owner_id, tenant_id) →
  members (user_id, tenant_id)` in row mode, `owner_id → members (user_id)` inside the schema in schema
  mode; never `users (id)` or `tenant_memberships`. The changesets `0002`–`0011` were applied with keys to
  `users`; the overlays' `0018-owners-reference-members` replaces them under the same names (a new table
  never has one). Without tenancy the reference keeps `owner_id → users`. Guards:
  `RowSecurityCatalogIntegrationTest.tenantDataReferencesNoPlatformTable` and
  `SchemaCatalogIntegrationTest.tenantSchemasReferenceNoPlatformTable` (each proven to catch a key to
  `users`).
- **Detect:** `REFERENCES users`, `REFERENCES tenant_memberships`, `REFERENCES platform_users` or
  `REFERENCES platform.` in a tenant changeset; a `@ManyToOne User` on a tenant entity; a new table
  missing from the catalogue guards' reach (a schema-mode table outside `tenant.yaml`).
- **Severity:** High (the tenant can no longer be extracted without rewriting its data).

### TEN-17: Members: the tenant's own copy of its memberships
- **Check:** the tenancy pieces' `members` table (row mode a tenant table with `tenant_id`, RLS and the
  key `(user_id, tenant_id)`; schema mode in every tenant schema, key `(user_id)`) holds one row per
  person who is or was a member, and no personal data (no email, no name: those stay on the platform).
  `MembershipService` writes it in the transaction that creates the platform membership (`addMember`),
  marks it left (`left_at`) when the membership is removed, never deletes it (the person's records still
  reference it), and clears the mark on the same row when the person is added again. The owner's row
  comes from the `TenantProvisioned` listener (`addOwnerToTenantData`): the owner's membership is created
  with the tenant, before schema mode has a schema and while row mode has no tenant to write under.
  `tenant_memberships` stays the authority on who belongs and with which role. Test fixtures that add a
  membership add the row too (`IntegrationTest.addMember`). Tests:
  `TenantMemberIntegrationTest.theTenantsMembersFollowTheMemberships`, `provisioningAddsTheOwnersRow`.
  A record naming a member (golden `Todo.requesterId`) checks them through `Members.isMember` whenever a write
  names someone new (an update only when the id changes: a PUT re-sends a former member, who stays) and
  shows a former member by id alone (`TodoRequesterTenancyIntegrationTest`).
- **Detect:** an update that re-checks the person it already names (`requireMember(request.assigneeId())`
  unconditionally): every record of a person who left then refuses every edit with 404.
- **Extension point (a members picker every member reads):** `GET /tenant-members` is ADMIN-only and
  searches emails. A project whose screens let every member pick a person generates a members dropdown in
  the user module with crud-gen (label the full name, `query` on it, read through `Members`, the current
  tenant only, crud-gen dropdown.md's tests) and reviews it with house-review like any other code.
- **Detect:** a membership created or removed without its `members` row in the same transaction; a
  `DELETE FROM members`; a role, email or name copied into `members`; a tenant record that references
  `tenant_memberships` (the platform's row is deleted on removal).
- **Severity:** High (a membership without its row makes the member's first write fail; a deleted row
  orphans their records).

### TEN-18: Display numbers count per tenant
- **Check:** a number a tenant's users see (an invoice or order number) comes from the metadata piece's
  `Counters`, a row of the tenant's own data (`counters`, unique `(tenant_id, key)` in row mode, per
  schema in schema mode), never from a PostgreSQL sequence or identity column of a shared table: one
  sequence serves every tenant in row mode, so the numbers interleave, reveal another tenant's volume and
  don't move with the tenant. Tests: `CounterTenantIntegrationTest` (one name, two tenants, each from 1);
  row mode's catalogue test fails on any sequence not named in `PLATFORM_SEQUENCES`.
- **Check:** `counters` is tenant data, so a tenant's display number is a **fixed** counter name
  (`TICKET_NUMBER`), never `Counters.perContext` with the tenant id; schema mode seeds it in the tenant
  changeset, row mode needs TEN-34's seed pair (and `createIfAbsent` on the create). The create takes the
  number **last**, after every check: `next` holds the counter's row until commit, so one tenant's creates
  queue behind each other (worth a race test: consecutive numbers, no duplicate). A display number whose
  records may be deleted has gaps; the API text says a deleted record's number is not handed out again.
- **Detect:** `GENERATED … AS IDENTITY`, `serial`, `CREATE SEQUENCE` or `nextval` for tenant data in row
  mode; a display number derived from a global id; `perContext(…, tenantId)`; `next` called before the
  create's own checks.
- **Severity:** Medium (High when the developer says the number is a legal sequence, such as an invoice
  number).

### TEN-19: Stored files sit under their tenant's prefix
- **Check:** every object key starts with the tenant's id: `<tenantId>/<PURPOSE>/<uuid>` (the tenancy
  overlay's `MediaFileMapper.newKey`), so one tenant's files are one prefix in the bucket, to copy to
  another store or delete with the tenant, and the keys stored in its rows stay valid after a move.
  Test: `MediaKeysPerTenantTest`. See media.md MEDIA-08.
- **Detect:** a key without the tenant prefix, or with it in another position; files of several tenants
  under one prefix; a key built from anything but the current tenant (a body field, a path segment).
- **Severity:** Medium.

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
  a review records the tenant count the developer gives, never an estimate.
- **Severity:** Medium (an architecture decision to revisit, not a code defect).

## Row mode

One schema for every tenant; `tenant_id` on each tenant table. Two layers, each with its job:
Hibernate's `@TenantId` fills `tenant_id` and adds `tenant_id = ?` to every entity query (the index
predicate, and no repository names the tenant); PostgreSQL row-level security (RLS) is the barrier
for everything Hibernate doesn't filter (native SQL, a fixture, an injection). Evidence: the design's
E1–E3 and the row tests named below, which run as the real application role.

### TEN-30: [row] Every tenant table is forced under one tenant policy, in the changeset that creates it
- **Check:** the changeset that creates a tenant table also has
  - `tenant_id uuid NOT NULL` with **no default**, and `CONSTRAINT fk_<table>_tenant_id FOREIGN KEY
    (tenant_id) REFERENCES tenants (id)`. Hibernate sets the column on entity inserts; every other insert
    (native SQL, a changeset's backfill, a test fixture) names its tenant. An insert that forgets it
    fails at once, whatever the session was told (its NULL is refused by the policy's check before NOT
    NULL: `RowIsolationIntegrationTest.anInsertNamesItsTenant`). A `current_setting` default would fill
    it from the transaction's tenant and hide the omission until a job or a platform path inserts with
    another tenant set. The golden changesets `0002`–`0011` were applied with such a default; the row
    overlay's `0016-tenant-id-no-default` drops it (an applied changeset is never edited), and a new
    table never has one;
  - `ALTER TABLE <t> ENABLE ROW LEVEL SECURITY; ALTER TABLE <t> FORCE ROW LEVEL SECURITY;` (forced: the
    owner, which runs Liquibase, is bound too);
  - exactly one policy for every command, `CREATE POLICY tenant_isolation ON <t> USING (tenant_id =
    nullif(current_setting('app.tenant_id', true), '')::uuid) WITH CHECK (<the same>)`. A second
    permissive policy is OR-ed with the first and widens it.

  Every table is a tenant table unless it is in `RowSecurityCatalogIntegrationTest.PLATFORM_TABLES`
  with its reason; that test fails on a tenant table missing any part above (it is proven to catch a
  table without a policy, one enabled but not forced, and a `tenant_id` with a default), on a table
  that is neither, on a `tenant_id` that is nullable, has a default or doesn't reference `tenants`, and
  on an app role that is a superuser, owns a table or has `BYPASSRLS`.
- **Detect:** a `tenant_id` column without `FORCE`; `tenant_id … DEFAULT …` (any default); a native or
  fixture `INSERT` into a tenant table whose column list lacks `tenant_id`; the policy in a later
  changeset (a window without it); `USING` without `WITH CHECK` (a write into another tenant); `FOR SELECT` only; a policy with
  `OR current_setting('app.bypass', true) = 'on'` or any switch the app role can set itself (TEN-12);
  `current_setting('app.tenant_id')::uuid` without `nullif` (a reused session returns `''`, and the cast
  fails instead of matching nothing); a new table missing from the catalogue test's lists.
- **Severity:** Critical.

### TEN-31: [row] Only the hook names `app.tenant_id`
- **Check:** `TransactionLocalTenantProvider` is the only Java that sets or reads `app.tenant_id`
  (`TenancyArchTest`), with one parameterised transaction-local statement (TEN-04); the policies read
  it in SQL, and nothing else does (no column default). `NO_TENANT` sets it empty: platform tables work, tenant rows read
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
  - entity queries, filters and requests never take a tenant parameter (TEN-01): Hibernate adds
    `tenant_id = ?` to every entity query, and the policy's predicate then reduces to a one-time filter.
    The one exception is native SQL (TEN-37).
- **Detect:** a tenant entity extending `AuditableEntity` (its inserts fail without a tenant column
  value, and entity queries lose their predicate and the insert check); `tenantId` in a request, a
  filter, a JPQL `@Query` or a derived query method; a native statement without it (TEN-37).
- **Severity:** High (RLS still holds; Critical when combined with a policy gap).

### TEN-33: [row] Keys carry the tenant; references between tenant tables are composite
- **Check:**
  - every unique constraint and list index has `tenant_id` among its **leading equality columns**
    (DATA-10). The order between `tenant_id` and the owner is plain: **the owner or a parent column first
    when the table has one** (`(owner_id, tenant_id, name, id)`, `(todo_list_id, owner_id, tenant_id, …)`:
    it still serves that column's foreign key, and an owner or a parent lives in one tenant, so the pair is
    as selective either way); **`tenant_id` first when the table has neither, or when a list filters by
    the tenant alone** (a tenant-shared customer list
    `(tenant_id, name, id)`, the media jobs' `(tenant_id, upload_expires_at, id)`, `(tenant_id, key)`).
    The sort keys and `id` follow. The single-tenant names stay, so the modules' `ConstraintErrorCodes`
    serve every mode;
  - a reference between two tenant tables is a **composite foreign key** carrying `tenant_id` on both
    sides, to a parent key with `tenant_id` last: `(todo_list_id, owner_id, tenant_id) → todo_lists (id,
    owner_id, tenant_id)`, `(media_file_id, tenant_id) → media_files (id, tenant_id)`. Key checks bypass
    RLS, so a plain `parent_id` key lets a row point at another tenant's parent; the composite one
    refuses it (`RowIsolationIntegrationTest`; the catalogue test fails on a key between tenant tables
    without the pair). With `tenant_id` first, the parent key `(tenant_id, id)` walked "newest first"
    ahead of the list's own index on the test data (`TodoIndexIntegrationTest`): last, it serves the key
    and no order;
  - the one reference to a platform table is `tenant_id → tenants`, a plain key; a person is the
    tenant's own member, `(owner_id, tenant_id) → members (user_id, tenant_id)` (TEN-16);
  - **a scope column that determines `tenant_id`** (an owner or a member: a person lives in one tenant)
    has dependency statistics with it, `CREATE STATISTICS st_<table>_<column>_tenant_id (dependencies)
    ON <column>, tenant_id FROM <table>`, in the table's changeset (golden row overlay
    `0023-owner-tenant-statistics.sql`). Without them the planner multiplies the person's share of the
    table by the tenant's, estimates a few rows (one, for a member of a tenant among many) and picks
    another index with the same leading columns plus a sort: the exclusion constraint's GiST index
    or a join probing by id; `OwnerTenantStatisticsIntegrationTest` checks they exist. The constraint's own
    `tenant_id WITH =` is right (a person in two tenants has two rotas); its `&&` stays the constraint's
    (it isn't leakproof, so a list's overlap filter can't use the GiST index under the policy, TEN-36);
- **an exclusion constraint** is a key like the others: `tenant_id WITH =` among its equality columns.
- **Not a finding (DATA-10):** the `tenant_id → tenants` key of an owner-scoped table has no index
  leading with `tenant_id`: tenants are closed, never deleted, so the key is never checked from the
  parent side. Tenant deletion (later) adds the indexes it needs.
- **Detect:** an owner- or member-scoped tenant table without the dependency statistics; `UNIQUE (key)` or `UNIQUE (owner_id, name)` without `tenant_id`; a tenant table's
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
  (`docs/tenancy-runbook.md`, section 4; proven in the catalogue test): that is how a default added
  later (a new upload purpose's policy) reaches the tenants that already exist, beside the listener
  for new ones (golden `0010`'s `…-existing-tenants` changeset and its test). **Its rollback lifts `FORCE`
  the same way** around its `DELETE` (it runs as the owner with no tenant set, where the forced policy hides
  every row: a plain `--rollback DELETE` deletes nothing; guard `BackfillRollbacksIntegrationTest`, which
  also runs each backfill's rollback as the owner). A listener alone leaves
  every existing tenant without the row. A failed provisioning leaves the
  tenant PENDING and logs it; approving again retries.
- **Check:** the backfill gives the row to **every** tenant, a PENDING registration included, so the
  listener creates its row **only when missing** (`Uploads.definePolicy`, `Metadata.createIfAbsent`,
  `Counters.createIfAbsent`: one `INSERT … ON CONFLICT DO NOTHING` naming its tenant, or a check first).
  A plain insert meets the backfilled row on approval, the unique key refuses it, the activation rolls
  back, and every retry fails the same way: the tenant can never be activated. Tested by approving a
  PENDING tenant after the backfill ran (golden `TodoSeedsBackfillIntegrationTest
  .aPendingTenantThroughTheBackfillsIsActivated`, red with a plain insert). During a rolling deploy an
  old instance (without the listener) may approve a tenant after the new release's backfill ran: the
  tenant then lacks the row, so a row the code can't work without is created on use too (a display
  number's create calls `Counters.createIfAbsent` before `next`), or the runbook says to approve only
  once the deploy is through.
- **Check (the convention, every mode):** a per-tenant seed is create-if-absent and reaches **every
  tenant row, whatever its status**: the backfill reads `FROM tenants`, never filtered by status, and
  continues from the tenant's own data (a counter starts after the tenant's highest number, never at 0
  over rows that are already numbered); schema per tenant puts the seed in a tenant changeset, which every
  schema runs. Housekeeping jobs visit every tenant that holds data (`idsHoldingData`), work done for
  users only the ACTIVE ones. The matrix of seeds × tenant statuses with a test per cell is
  `tenancy-matrices.md`, *Tenant lifecycle × seeding*; a new seed adds its row there, and its backfill
  is tested on the upgraded database (crud-gen tests.md row 30, `UpgradeFromPreviousReleaseIntegrationTest`:
  on an empty database a backfill limited to ACTIVE tenants, or a counter started at 0, passes).
- **Detect:** `INSERT` into a tenant table in a changeset outside a `NO FORCE` … `FORCE` backfill; a
  backfill with a `WHERE status = …` on `tenants`, or a counter seeded at a constant beside rows that
  carry numbers; a backfill changeset with no test on the upgraded database; a
  new seed listener without the backfill for existing tenants; a seed listener that inserts
  unconditionally (`metadata.create`, `counters.create`, `repository.save`) beside a backfill; a seed listener on `TenantCreated` or
  `TenantApproved` (no tenant scope yet, or no committed tenant); seeding inside the approving
  transaction; `NO FORCE` without `FORCE` again in the same changeset.
- **Severity:** High.

### TEN-35: [row] The platform's tables are named, each with its reason
- **Check:** `RowSecurityCatalogIntegrationTest.PLATFORM_TABLES` lists the control plane: `users`,
  `refresh_tokens`, `tenants`, `tenant_status_changes`, `tenant_memberships`, `platform_users`,
  `platform_audit` and Liquibase's tables. `tenant_memberships` and `refresh_tokens` carry a
  `tenant_id` but stay platform tables: login and "my tenants" read a user's memberships across
  tenants before one is chosen, and a refresh reads its token before it knows the tenant. Code reads
  them by user or by token, never as "all rows"; a query for one tenant's members names the tenant
  (TEN-08). A project's own platform table joins the list with its reason, in the same change.
- **Detect:** a table added to the list without a reason, or to make the guard pass; a tenant-plane
  endpoint reading `tenant_memberships` without the caller's tenant in its predicate.
- **Severity:** High.

### TEN-36: [row] Operators that aren't leakproof lose their index under a policy
- **Check:** PostgreSQL evaluates the policy before any user condition that isn't leakproof, so such
  a condition can't be an index condition and filters the tenant's rows instead (a jsonb `@>` leaves its
  GIN index, `ILIKE` its trigram index, the range `&&` the exclusion constraint's GiST index; DESIGN.md,
  *Measurements behind the rules*):
  - `pg_proc.proleakproof` (asserted by `RowIsolationIntegrationTest`): leakproof `uuid_eq`, `texteq`,
    `int8eq`, `timestamptz_lt`; not leakproof `jsonb_contains`, `textlike`, `texticlike`, `enum_eq`,
    `range_overlaps`. Statuses as `text` with a check constraint (DATA-07) stay leakproof; PostgreSQL
    enums don't.

  A search on such an operator is bounded by the leakproof predicates in front of it (the tenant and the
  owner, the list's order index). A faster shape (a leakproof equality on a derived column) or a reader on
  a bypass role is a use case: the project measures it with `EXPLAIN` as the app role with the tenant set
  (runbook, section 5).
- **Detect:** a GIN or GiST index whose only user is a non-leakproof operator on a tenant table;
  `EXPLAIN` evidence taken as a superuser; a PostgreSQL enum column filtered on.
- **Severity:** Medium (performance).

### TEN-37: [row] Native SQL on a tenant table names its tenant
- **Check:** Hibernate's tenant filter never reaches native SQL, so every native statement on a tenant
  table carries `tenant_id = :tenantId`, once per tenant table it reads or writes (a subquery's table
  included), and an insert names `tenant_id` in its column list. The service passes the current
  tenant (`TenantContext.id()`, which throws outside a tenant); a job's statements get the tenant the
  job runs for, from the `TenantScope.runAs` around each tenant's batch. The policy is the fallback,
  not the filter: the code always sends the right tenant, so a platform statement and a tenant
  statement never look alike, and the planner sees the tenant as a parameter. Golden: the row
  overlay's `MediaFileRepository` (claims, releases, the jobs' leases) and `CounterRepository`
  (`MediaFileService`, `CounterService` pass it). `NativeQueryTenantIntegrationTest` reads every
  repository's `@Query(nativeQuery = true)` and fails on a tenant table named without its predicate
  (proven to catch one removed); native SQL lives in repositories, so it sees all of it.
- **Detect:** a native statement on a tenant table without `tenant_id = :tenantId`; a subquery's tenant
  table without its own; a `JdbcTemplate` or `createNativeQuery` on a tenant table outside a repository
  (the guard can't see it); the tenant read from the `app.tenant_id` setting in SQL
  (`current_setting`) instead of passed.
- **Severity:** High (the policy still holds; Critical when combined with a policy gap or a bypass
  role).

### TEN-38: [row] Every tenant table's replica identity holds `tenant_id`
- **Check:** each tenant table has a unique index that includes `tenant_id` (on NOT NULL columns, not
  partial, no expression) set as its replica identity: `ALTER TABLE <t> REPLICA IDENTITY USING INDEX
  <uq_…>`, in the changeset that creates the table. A tenant leaves by logical replication through a
  publication filtered by its id (`CREATE PUBLICATION … FOR TABLE <t> WHERE (tenant_id = '<id>')`), and
  PostgreSQL refuses every UPDATE and DELETE on a published table whose row filter names a column outside
  the replica identity; the default identity, the primary key `(id)`, lacks `tenant_id`, so the
  application's writes would fail the moment the publication exists. Use a unique key the table already
  has with `tenant_id` (the parent key of its composite references, `(tenant_id, key)`); a table without
  one gets `uq_<table>_id_tenant_id (id, tenant_id)`. The reference's tables got theirs in the row
  overlay's `0019-replica-identity` (applied changesets are never edited). Guards:
  `RowSecurityCatalogIntegrationTest.everyReplicaIdentityCarriesTheTenant` (proven to catch a table on
  its primary key) and `aTenantFilteredPublicationAcceptsUpdatesAndDeletes` (UPDATE and DELETE pass under
  a tenant-filtered publication, and fail on a table without the identity).
- **Detect:** a tenant table's changeset without `REPLICA IDENTITY USING INDEX`; `REPLICA IDENTITY
  FULL` (it works, but logs every column of every old row); an identity index without `tenant_id`.
- **Severity:** Medium (the tenant can't be extracted while the application keeps writing).

## Not built yet (phase 2)

Invitations (a membership for an email without an account; today staff and registration only add
existing or new owners); email verification of registrations and signups (no mail sending in the
pieces); deleting registrations left PENDING past `app.tenancy.pending-lifetime`; parallel tenant
migrations (one JVM runs Liquibase one schema at a time); impersonating a suspended tenant (refused
today, as every token issue for it); break-glass access and a support-access log the tenant reads;
per-tenant rate limits and quotas; a role per tenant schema; moving a tenant out (a tenant directory
with its cell, a `MIGRATING` status, the extraction runbook) and a JWT key pair for a second deployable
that checks tokens (the rules above only keep a tenant extractable).
