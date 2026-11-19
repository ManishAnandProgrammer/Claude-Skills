--liquibase formatted sql

-- The roles piece with schema per tenant, the platform's last half (house-review AUTHZ-11): the memberships' role
-- column goes once no tenant schema has a role left to copy, so its access is the tenant tables' alone (contract,
-- after 0038's expand). Until then the precondition skips it and Liquibase tries again at the next start, which is
-- what a deploy after the tenant migrations finished is: a schema whose migration failed keeps it, and its members'
-- roles, until it is fixed and migrated (tenant_migrations.status, docs/tenancy-runbook.md). A tenant without a
-- schema (PENDING, REJECTED) has nothing to copy: its owner's ADMIN role comes at its provisioning.
-- bootstrap-copy.sh copies this file with the roles and tenancy-schema pieces.

--changeset dev:0039-memberships-role-contract
--preconditions onFail:CONTINUE
--precondition-sql-check expectedResult:0 SELECT count(*) FROM tenant_memberships m WHERE m.role IS NOT NULL AND EXISTS (SELECT 1 FROM tenant_migrations t WHERE t.tenant_id = m.tenant_id AND t.status <> 'CANCELLED')
ALTER TABLE tenant_memberships DROP COLUMN role;
--rollback ALTER TABLE tenant_memberships ADD COLUMN role text;
--rollback ALTER TABLE tenant_memberships ADD CONSTRAINT ck_tenant_memberships_role CHECK (role IN ('USER', 'ADMIN'));
--rollback CREATE INDEX idx_tenant_memberships_tenant_id_role_created_at_id ON tenant_memberships (tenant_id, role, created_at, id);
