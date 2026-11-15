--liquibase formatted sql

-- Schema per tenant: each tenant schema's own members (house-review TEN-16, TEN-17), a tenant changeset
-- (tenant.yaml). The tenant's tables reference a member in their own schema, owner_id → members
-- (user_id), never platform.users, so `pg_dump -n <schema>` holds a closed graph that restores into a
-- database of its own. The platform's tenant_memberships stays the authority on who belongs and with
-- which role; MembershipService writes both in one transaction. No personal data: the email and the
-- name stay on the platform. A removed member's row stays (their records reference it), marked left.

--changeset dev:0017-members
CREATE TABLE members (
    id         uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    left_at    timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid,
    updated_by uuid,
    version    bigint      NOT NULL,
    CONSTRAINT pk_members PRIMARY KEY (id),
    -- The target of the owners' keys. user_id is a copy of users.id, not a key to it: the tenant's data
    -- names no platform table (TEN-16).
    CONSTRAINT uq_members_user_id UNIQUE (user_id)
);
-- The members of a tenant that exists when this ships, found through the platform's migration record of
-- this schema. None in the template, and none in a schema being provisioned: a new tenant's data comes
-- from its TenantProvisioned listeners (MembershipService adds the owner), never from a changeset.
INSERT INTO members (id, user_id, created_at, updated_at, version)
SELECT uuidv7(), m.user_id, now(), now(), 0
  FROM tenant_memberships m
  JOIN tenant_migrations t ON t.tenant_id = m.tenant_id
 WHERE t.schema_name = current_schema()
   AND t.status <> 'PROVISIONING';
--rollback DROP TABLE members;
