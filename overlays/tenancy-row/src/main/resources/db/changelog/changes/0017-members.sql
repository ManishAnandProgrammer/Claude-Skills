--liquibase formatted sql

-- Row mode: each tenant's own members (house-review TEN-16, TEN-17). The tenant's tables reference a
-- member of their own tenant, (owner_id, tenant_id) → members (user_id, tenant_id), never the platform's
-- users, so one tenant's rows are a closed graph that row-filtered logical replication can move to a
-- database of its own. The platform's tenant_memberships stays the authority on who belongs and with
-- which role; MembershipService writes both in one transaction. No personal data: the email and the
-- name stay on the platform. A removed member's row stays (their records reference it), marked left.

--changeset dev:0017-members
CREATE TABLE members (
    id         uuid        NOT NULL,
    -- No default: every insert names its tenant (TEN-30).
    tenant_id  uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    left_at    timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid,
    updated_by uuid,
    version    bigint      NOT NULL,
    CONSTRAINT pk_members PRIMARY KEY (id),
    CONSTRAINT fk_members_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    -- Once per tenant; the target of the owners' composite keys, tenant_id last (TEN-33). user_id is a
    -- copy of users.id, not a key to it: the tenant's data names no platform table but tenants (TEN-16).
    CONSTRAINT uq_members_user_id_tenant_id UNIQUE (user_id, tenant_id)
);
-- Row-filtered replication of UPDATE and DELETE needs the filter column in the replica identity (TEN-38).
ALTER TABLE members REPLICA IDENTITY USING INDEX uq_members_user_id_tenant_id;
ALTER TABLE members ENABLE ROW LEVEL SECURITY;
ALTER TABLE members FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON members
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- The members of the tenants that exist when this ships (none on a new database). RLS is lifted for the
-- transaction of this changeset only, so the owner writes every tenant's rows (docs/tenancy-runbook.md,
-- section 4). New memberships write their row themselves.
ALTER TABLE members NO FORCE ROW LEVEL SECURITY;
INSERT INTO members (id, tenant_id, user_id, created_at, updated_at, version)
SELECT uuidv7(), m.tenant_id, m.user_id, now(), now(), 0
  FROM tenant_memberships m;
ALTER TABLE members FORCE ROW LEVEL SECURITY;
--rollback DROP TABLE members;
