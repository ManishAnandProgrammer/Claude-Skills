# Tenancy runbook: schema per tenant

The incident runbook (`runbook.md`) covers the application as a whole; this is what schema per tenant
adds. `<app>` is `spring.application.name` with dashes as underscores (`campus_desk`: role names are
plain lower-case identifiers, which the changesets use unquoted; startup refuses others), `<db>` is `DB_NAME`.

## 1. Database roles (before the first start, and before a restore)

Roles are cluster-level, so Liquibase never creates them, and a restore needs them first. As a
superuser, once per database:

```sql
CREATE ROLE <app>_owner LOGIN PASSWORD '...';   -- DB_OWNER_USER: owns every table, runs Liquibase
CREATE ROLE <app>_app LOGIN PASSWORD '...';     -- DB_USER: the pool's role; reads and writes, owns nothing
GRANT CREATE ON DATABASE "<db>" TO <app>_owner;   -- tenant schemas are created by provisioning (quoted: a DB_NAME may hold a dash)
CREATE SCHEMA platform AUTHORIZATION <app>_owner;
-- Connections start in the platform schema: a tenant's tables exist only inside its transactions.
ALTER ROLE <app>_app IN DATABASE <db> SET search_path = platform;
-- Liquibase prepends the schema it migrates, so tenant changesets still find the platform's tables.
ALTER ROLE <app>_owner IN DATABASE <db> SET search_path = platform;
```

Locally, `docker compose up -d` does exactly this on a new database
(`docker/postgres-init/10-tenancy-roles.sh`, with `DB_USER`, `DB_PASSWORD`, `DB_OWNER_USER` and
`DB_OWNER_PASSWORD` from `.env`). It runs only when the data directory is new: on an existing local
volume, `docker compose down -v` first (that deletes the local data).

Changeset `0013` grants the app role its rights (and default privileges for every later table);
`0015` takes the write rights on `platform_users` back (staff accounts are the owner's to write,
section 2). The app role must never own a table or be a superuser, itself or through a role it is a
member of: never `GRANT <app>_owner TO <app>_app` to fix a missing grant (it could then change any
schema), grant the missing table right instead. The application checks this at startup
(`PoolRoleCheck`) and stops naming what is wrong.

## 2. Platform staff

Staff are rows, not an endpoint. **After the application's first start** (Liquibase creates
`platform_users` then; before it the insert fails with "relation does not exist"), as the owner role
(`DB_OWNER_USER`), insert one with a bcrypt hash of its password (strength 12). The hash needs nothing but Docker (Apache's `htpasswd` writes `$2y$`, which
the application's encoder reads):

```bash
set -a; . ./.env; set +a
read -rs -p 'Password: ' password; echo
hash="$(docker run --rm httpd:2.4-alpine htpasswd -nbBC 12 '' "$password" | tr -d ':\n')"
docker compose exec -T postgres psql -U "$DB_OWNER_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 \
  -v email='staff@example.com' -v name='Sam Staff' -v hash="$hash" <<'SQL'
insert into platform.platform_users (id, email, full_name, password_hash, role, created_at, updated_at, version)
values (uuidv7(), lower(trim(:'email')), :'name', :'hash', 'STAFF', now(), now(), 0);
SQL
```

`INSERT 0 1` is the answer (`psql` variables work from standard input, not from `-c`). Elsewhere than
the local stack, run the same `insert` with the operator's own `psql` connection as the owner role. They
sign in at `POST /api/v1/platform/auth/login`.

**A staff member who leaves** is deactivated, never deleted (their audit history references the row), as
the owner role: `update platform_users set deactivated_at = now() where email = lower(trim('<email>'));`
(`UPDATE 1`). Their login is refused from then on, and the tokens they hold are refused at their next
request (every platform request re-reads the row); no restart, no cache. Reactivating is the same
statement with `deactivated_at = null`.

**An account** (a person, global across tenants) is suspended or reactivated by staff: find it with
`GET /api/v1/platform/users?email=<address>` (its `id`, `status` and `version`), then
`PATCH /api/v1/platform/users/{id}/status` (`{"status": "SUSPENDED", "version": n}`), never by SQL: the
endpoint also revokes every refresh token
<!-- session-version:begin -->
and ends the access tokens at once (the session version).
<!-- session-version:else -->
<!-- (the access tokens already issued expire within their 15 minutes). -->
<!-- session-version:end -->
The last active ADMIN of an open tenant is refused (409): make another member ADMIN first, or suspend the
tenant.

## 3. Tenants

- **Staff create a tenant** (`POST /api/v1/platform/tenants`) for an owner who already has an account:
  it is PENDING until provisioning has created its schema, then ACTIVE.
- **Self-registration** (when the project has the `tenant-registration` piece): a registered tenant is
  PENDING and waits for staff: `POST /api/v1/platform/tenants/{id}/approve` approves it (provisioning
  follows), `PATCH .../{id}/status` with status `REJECTED` rejects it. The owner's login answers 403
  `TENANT_NOT_ACTIVE` with `tenantStatus: PENDING` meanwhile.
- **A tenant rejected after approval** but before provisioning ran gets no schema: its
  `tenant_migrations` row becomes `CANCELLED`.

## 4. Restore order

1. The roles above (a dump doesn't carry them).
2. `pg_restore` the dump (the `platform` schema, `tenant_template`, every `t_<id>` schema).
3. Start one instance: Spring migrates the platform, the template migration runs, then
   `TenantMigrations` brings every tenant schema up to date.

One tenant alone: `pg_dump -n t_<tenant id without dashes>` and restore that schema; its platform rows
(`tenants`, `tenant_memberships`, `tenant_migrations`) come from the platform schema.

## 5. Tenant migrations at deploy

- The instance that takes the advisory lock first (`hashtextextended('tenant-schema-migrations', 0)`) migrates
  every tenant schema once it is ready; the others skip. The log says
  `Tenant migrations: ran=true, migrated=N, failed=[...]`.
- A failed schema: `SELECT tenant_id, schema_name, error FROM platform.tenant_migrations WHERE status = 'FAILED';`.
  Fix the schema (or the changeset, with a new one), then restart one instance: the run applies only
  what is missing. The other tenants were migrated meanwhile; only that tenant's new features wait.
- A schema stuck in Liquibase's lock (an instance was killed mid-run):
  `UPDATE t_<id>.databasechangeloglock SET locked = false, lockgranted = NULL, lockedby = NULL WHERE id = 1;`.
- A tenant stuck `PENDING` after approval: the counter `tenant.provisioning.failed` rises with every failed
  attempt (alert on a steady increase); `SELECT * FROM platform.tenant_migrations WHERE status = 'PROVISIONING';`
  shows the provisioning error; the job retries every run (`app.jobs.tenant-provisioning.cron`).
- **Approvals during a rolling deploy.** A tenant an old instance provisions after the last new instance
  started has the old release's schema until a migration run reaches it: run one (restart an instance)
  once the deploy is through, or approve tenants only then. `platform.tenant_migrations.last_changeset`
  behind the template's names such a schema.
- Before a release whose tenant changesets change existing rows ships, its tests run on schemas at the
  previous release, seeded (`./mvnw verify -Dapp.test.upgrade.previous-release=<pieces>,<project>`; the application's
  runbook, *Before upgrading a live database*), and the migration runs once against a restored copy.
