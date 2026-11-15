--liquibase formatted sql

-- The members list (GET /tenant-members), a platform changeset: tenant_memberships has no policy, so
-- every query names its tenant, and each index leads with tenant_id (house-review DATA-10, TEN-33).
-- NEWEST and OLDEST walk (tenant_id, id), one direction each. The role filter walks (tenant_id, role, id):
-- ADMIN is the rare value, and the last-ADMIN count and the owner lookup filter by it too.
-- (tenant_id, id) serves the tenant's foreign key as well, so it replaces idx_tenant_memberships_tenant_id.

--changeset dev:0020-tenant-members-list
CREATE INDEX idx_tenant_memberships_tenant_id_id ON tenant_memberships (tenant_id, id);
CREATE INDEX idx_tenant_memberships_tenant_id_role_id ON tenant_memberships (tenant_id, role, id);
DROP INDEX idx_tenant_memberships_tenant_id;
--rollback CREATE INDEX idx_tenant_memberships_tenant_id ON tenant_memberships (tenant_id);
--rollback DROP INDEX idx_tenant_memberships_tenant_id_role_id;
--rollback DROP INDEX idx_tenant_memberships_tenant_id_id;
