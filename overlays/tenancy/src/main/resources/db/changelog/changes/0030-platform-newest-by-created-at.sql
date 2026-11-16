--liquibase formatted sql

-- The platform's NEWEST lists (tenants, a tenant's members, a tenant's audit trail) order by the creation
-- time, then the id, never by the id alone: the primary key leads with the id and, with UUIDv7 ids in heap
-- order, the planner walks it backward through every other tenant's newer rows instead of using the
-- tenant's index (house-review DATA-10; the reference's 0029 for its own tables). Each index still leads
-- with the same columns, so the foreign keys it served stay served.

--changeset dev:0030-platform-newest-by-created-at
CREATE INDEX idx_tenants_created_at_id ON tenants (created_at, id);
CREATE INDEX idx_tenants_status_created_at_id ON tenants (status, created_at, id);
CREATE INDEX idx_tenant_memberships_tenant_id_created_at_id ON tenant_memberships (tenant_id, created_at, id);
CREATE INDEX idx_tenant_memberships_tenant_id_role_created_at_id ON tenant_memberships (tenant_id, role, created_at, id);
CREATE INDEX idx_platform_audit_tenant_id_created_at_id ON platform_audit (tenant_id, created_at, id);
DROP INDEX idx_tenants_status_id;
DROP INDEX idx_tenant_memberships_tenant_id_id;
DROP INDEX idx_tenant_memberships_tenant_id_role_id;
DROP INDEX idx_platform_audit_tenant_id_id;
--rollback CREATE INDEX idx_tenants_status_id ON tenants (status, id);
--rollback CREATE INDEX idx_tenant_memberships_tenant_id_id ON tenant_memberships (tenant_id, id);
--rollback CREATE INDEX idx_tenant_memberships_tenant_id_role_id ON tenant_memberships (tenant_id, role, id);
--rollback CREATE INDEX idx_platform_audit_tenant_id_id ON platform_audit (tenant_id, id);
--rollback DROP INDEX idx_tenants_created_at_id, idx_tenants_status_created_at_id, idx_tenant_memberships_tenant_id_created_at_id,
--rollback     idx_tenant_memberships_tenant_id_role_created_at_id, idx_platform_audit_tenant_id_created_at_id;
