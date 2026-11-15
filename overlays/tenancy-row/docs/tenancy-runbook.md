# Tenancy runbook: row mode

The incident runbook (`runbook.md`) covers the application as a whole; this is what row mode adds:
one schema, a `tenant_id` on every tenant table, and PostgreSQL row-level security (RLS) as the barrier.
`<app>` is `spring.application.name` with dashes as underscores (`campus_desk`: role names are plain
lower-case identifiers, which the changesets use unquoted; startup refuses others), `<db>` is `DB_NAME`.

## 1. Database roles (before the first start, and before a restore)

Roles are cluster-level, so Liquibase never creates them, and a restore needs them first. As a
superuser, once per database:

```sql
CREATE ROLE <app>_owner LOGIN PASSWORD '...';                          -- DB_OWNER_USER: owns every table, runs Liquibase
CREATE ROLE <app>_app LOGIN PASSWORD '...' NOSUPERUSER NOBYPASSRLS;    -- DB_USER: the pool's role; reads and writes, owns nothing
GRANT CREATE ON DATABASE <db> TO <app>_owner;   -- the btree_gist extension, when a changeset needs it
GRANT CREATE ON SCHEMA public TO <app>_owner;
```

The app role must never be a superuser, own a table or have `BYPASSRLS`: any of the three switches
every policy off for it. `RowSecurityCatalogIntegrationTest` checks this in the tests; check a real
database with:

```sql
SELECT rolname, rolsuper, rolbypassrls FROM pg_roles WHERE rolname IN ('<app>_owner', '<app>_app');
```

Locally, `docker compose up -d` does exactly this on a new database
(`docker/postgres-init/10-tenancy-roles.sh`, with `DB_USER`, `DB_PASSWORD`, `DB_OWNER_USER` and
`DB_OWNER_PASSWORD` from `.env`). It runs only when the data directory is new: on an existing local
volume, `docker compose down -v` first (that deletes the local data).

Changeset `0013` grants the app role its rights (and default privileges for every later table). Each
tenant table's changeset enables and forces RLS with its policy.

## 2. Platform staff

Staff are rows, not an endpoint. As the owner role (`DB_OWNER_USER`), insert one with a bcrypt hash of
its password (strength 12). The hash needs nothing but Docker (Apache's `htpasswd` writes `$2y$`, which
the application's encoder reads):

```bash
set -a; . ./.env; set +a
read -rs -p 'Password: ' password; echo
hash="$(docker run --rm httpd:2.4-alpine htpasswd -nbBC 12 '' "$password" | tr -d ':\n')"
docker compose exec -T postgres psql -U "$DB_OWNER_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 \
  -v email='staff@example.com' -v name='Sam Staff' -v hash="$hash" <<'SQL'
insert into platform_users (id, email, full_name, password_hash, role, created_at, updated_at, version)
values (uuidv7(), lower(trim(:'email')), :'name', :'hash', 'STAFF', now(), now(), 0);
SQL
```

`INSERT 0 1` is the answer (`psql` variables work from standard input, not from `-c`). Elsewhere than
the local stack, run the same `insert` with the operator's own `psql` connection as the owner role. They
sign in at `POST /api/v1/platform/auth/login`.

## 3. Tenants

- **Staff create a tenant** (`POST /api/v1/platform/tenants`) for an owner who already has an account.
  The response says PENDING; right after it commits, provisioning seeds the tenant's defaults and makes
  it ACTIVE (there is no schema to create).
- **Self-registration** (when the project has the `tenant-registration` piece): a registered tenant is
  PENDING and waits for staff: `POST /api/v1/platform/tenants/{id}/approve` approves it (activated at
  once), `PATCH .../{id}/status` with status `REJECTED` rejects it. The owner's login answers 403
  `TENANT_NOT_ACTIVE` with `tenantStatus: PENDING` meanwhile.
- **A tenant still PENDING after its approval**: the log has `Tenant provisioning failed` with the
  cause (a module's seeding). Fix it, then approve the tenant again: provisioning runs again.

## 4. Data fixes and seeds as the owner

RLS is forced, so the owner sees and writes no tenant rows unless it names a tenant, like the app:

```sql
BEGIN;
SELECT set_config('app.tenant_id', '<tenant id>', true);
-- the fix, for that tenant only
COMMIT;
```

A changeset that must touch every tenant's rows (a new purpose's default for the existing tenants)
lifts the policy for its own transaction only, and says why:

```sql
ALTER TABLE <table> NO FORCE ROW LEVEL SECURITY;
INSERT INTO <table> (..., tenant_id) SELECT ..., id FROM tenants;
ALTER TABLE <table> FORCE ROW LEVEL SECURITY;
```

Liquibase runs a formatted-SQL changeset in one transaction, so the table is never left unforced.
New tenants get their defaults from their modules' `TenantProvisioned` listeners, never a changeset.

## 5. Query plans (a slow tenant)

`EXPLAIN` as the app role with the tenant set, never as a superuser (it bypasses the policies and
shows plans the application never gets):

```sql
SET ROLE <app>_app;
BEGIN;
SELECT set_config('app.tenant_id', '<tenant id>', true);
EXPLAIN (ANALYZE, BUFFERS) <the statement from the log>;
ROLLBACK;
RESET ROLE;
```

An operator that isn't leakproof (`jsonb @>`, `LIKE`, `ILIKE`, range `&&`, a PostgreSQL enum's `=`)
can't use its own index under a policy: it becomes a filter over the tenant's rows (house-review
TEN-36). Fine for a bounded tenant; for a large one, a measured fix (a leakproof shape, or a reader on a
bypass role behind a marker) is a design decision.

## 6. Backup and restore order

`pg_dump` as the owner fails on every table whose policy is forced (it runs with `row_security = off`,
which is an error where a policy applies): back up as the cluster's superuser, or as a backup role created for it (`CREATE ROLE <app>_backup
LOGIN BYPASSRLS PASSWORD '...'`, granted `SELECT` on every table), never by lifting `FORCE`.

1. The roles above (a dump doesn't carry them).
2. `pg_restore` the dump.
3. Start one instance: Liquibase finds every changeset applied.

One tenant's data: there is no per-tenant dump in row mode. Export with one query per tenant table
(`WHERE tenant_id = '<tenant id>'`, as the owner with the tenant set, section 4), or restore the whole
dump into a scratch database and copy that tenant's rows across.
