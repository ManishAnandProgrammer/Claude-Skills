--liquibase formatted sql

-- The roles piece with schema per tenant, the platform's half before the tenant schemas' (house-review AUTHZ-11):
-- * the memberships' role column may be empty: each tenant schema's 0037 copies its tenant's memberships' roles
--   into its own tables and empties them there, one schema after another (TenantMigrations), so until every schema
--   has run it, some memberships still hold theirs (expand; 0039 drops the column once none does: contract);
-- * the accounts' role column goes at once: with tenancy it never decided anything.
-- The previous release reads and writes the memberships' role: stop its instances before this runs (no rolling
-- deploy across it). bootstrap-copy.sh copies this file with the roles and tenancy-schema pieces.

--changeset dev:0038-memberships-role-expand
ALTER TABLE tenant_memberships ALTER COLUMN role DROP NOT NULL;
ALTER TABLE users DROP COLUMN role;
--rollback ALTER TABLE users ADD COLUMN role text NOT NULL DEFAULT 'USER';
--rollback ALTER TABLE users ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'));
--rollback ALTER TABLE users ALTER COLUMN role DROP DEFAULT;
--rollback UPDATE tenant_memberships SET role = 'USER' WHERE role IS NULL;
--rollback ALTER TABLE tenant_memberships ALTER COLUMN role SET NOT NULL;
