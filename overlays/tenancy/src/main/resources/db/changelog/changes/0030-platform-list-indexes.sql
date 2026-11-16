--liquibase formatted sql

-- The platform's lists (tenants, a tenant's members, a tenant's audit trail). NEWEST orders by the creation
-- time, then the id, never by the id alone: the primary key leads with the id and, with UUIDv7 ids in heap
-- order, the planner walks it backward through every other tenant's newer rows instead of using the scope's
-- index (house-review DATA-10; the reference's 0029 for its own tables). The members' and the audit's indexes
-- still lead with tenant_id, so the foreign keys they served stay served.
--
-- The tenants list has no scope column: its status filter's rare values (PENDING, SUSPENDED, CLOSED,
-- REJECTED) get a partial index per offered order, WHERE status = '<VALUE>', and the repository writes that
-- predicate with the value as a SQL literal (TenantRepositoryImpl), so a generic plan matches it too. A
-- composite (status, created_at, id) is no good here: the planner costs it by its leading column's heap
-- correlation and walks (created_at, id) instead, through every ACTIVE tenant between the rare ones
-- (library-desk round 4: 28,503 rows for a page). ACTIVE, most of the register, is served by walking the
-- order's own index. idx_tenants_status_id (0012) stays: the jobs' loop over a status, in id order.

--changeset dev:0030-platform-list-indexes
CREATE INDEX idx_tenants_created_at_id ON tenants (created_at, id);
CREATE INDEX idx_tenants_name_id ON tenants (name, id);
CREATE INDEX idx_tenants_pending_created_at_id ON tenants (created_at, id) WHERE status = 'PENDING';
CREATE INDEX idx_tenants_pending_name_id ON tenants (name, id) WHERE status = 'PENDING';
CREATE INDEX idx_tenants_suspended_created_at_id ON tenants (created_at, id) WHERE status = 'SUSPENDED';
CREATE INDEX idx_tenants_suspended_name_id ON tenants (name, id) WHERE status = 'SUSPENDED';
CREATE INDEX idx_tenants_closed_created_at_id ON tenants (created_at, id) WHERE status = 'CLOSED';
CREATE INDEX idx_tenants_closed_name_id ON tenants (name, id) WHERE status = 'CLOSED';
CREATE INDEX idx_tenants_rejected_created_at_id ON tenants (created_at, id) WHERE status = 'REJECTED';
CREATE INDEX idx_tenants_rejected_name_id ON tenants (name, id) WHERE status = 'REJECTED';
CREATE INDEX idx_tenant_memberships_tenant_id_created_at_id ON tenant_memberships (tenant_id, created_at, id);
CREATE INDEX idx_tenant_memberships_tenant_id_role_created_at_id ON tenant_memberships (tenant_id, role, created_at, id);
CREATE INDEX idx_platform_audit_tenant_id_created_at_id ON platform_audit (tenant_id, created_at, id);
DROP INDEX idx_tenant_memberships_tenant_id_id;
DROP INDEX idx_tenant_memberships_tenant_id_role_id;
DROP INDEX idx_platform_audit_tenant_id_id;
--rollback CREATE INDEX idx_tenant_memberships_tenant_id_id ON tenant_memberships (tenant_id, id);
--rollback CREATE INDEX idx_tenant_memberships_tenant_id_role_id ON tenant_memberships (tenant_id, role, id);
--rollback CREATE INDEX idx_platform_audit_tenant_id_id ON platform_audit (tenant_id, id);
--rollback DROP INDEX idx_tenants_created_at_id, idx_tenants_name_id, idx_tenants_pending_created_at_id, idx_tenants_pending_name_id,
--rollback     idx_tenants_suspended_created_at_id, idx_tenants_suspended_name_id, idx_tenants_closed_created_at_id,
--rollback     idx_tenants_closed_name_id, idx_tenants_rejected_created_at_id, idx_tenants_rejected_name_id,
--rollback     idx_tenant_memberships_tenant_id_created_at_id, idx_tenant_memberships_tenant_id_role_created_at_id,
--rollback     idx_platform_audit_tenant_id_created_at_id;
