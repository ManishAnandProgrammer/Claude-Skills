--liquibase formatted sql

-- Tenancy (every tenancy mode): the tenant register and its audit trail, the global users' memberships,
-- platform staff, and the tenant each refresh token is for. These are the platform's tables (schema
-- per tenant: in the platform schema, Liquibase's default schema there, config/tenancy.yml).

--changeset dev:0012-tenants
CREATE TABLE tenants (
    id         uuid        NOT NULL,
    name       text        NOT NULL,
    status     text        NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid,
    updated_by uuid,
    version    bigint      NOT NULL,
    CONSTRAINT pk_tenants PRIMARY KEY (id),
    -- Text with a check, not a PostgreSQL enum: text equality is leakproof, an enum's isn't.
    CONSTRAINT ck_tenants_status CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'CLOSED', 'REJECTED'))
);
-- The jobs' loop over the ACTIVE tenants, oldest first.
CREATE INDEX idx_tenants_status_id ON tenants (status, id);
--rollback DROP TABLE tenants;

--changeset dev:0012-tenant-status-changes
-- Every change of a tenant's status, with its source; created_by names the staff member.
CREATE TABLE tenant_status_changes (
    id          uuid        NOT NULL,
    tenant_id   uuid        NOT NULL,
    from_status text,
    to_status   text        NOT NULL,
    source      text        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_by  uuid,
    version     bigint      NOT NULL,
    CONSTRAINT pk_tenant_status_changes PRIMARY KEY (id),
    CONSTRAINT fk_tenant_status_changes_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT ck_tenant_status_changes_source CHECK (source IN ('STAFF', 'REGISTRATION', 'PROVISIONING'))
);
CREATE INDEX idx_tenant_status_changes_tenant_id ON tenant_status_changes (tenant_id);
--rollback DROP TABLE tenant_status_changes;

--changeset dev:0012-tenant-memberships
-- A global user's place in a tenant, with the role there. Login reads a user's memberships.
CREATE TABLE tenant_memberships (
    id           uuid        NOT NULL,
    tenant_id    uuid        NOT NULL,
    user_id      uuid        NOT NULL,
    role         text        NOT NULL,
    last_used_at timestamptz,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    created_by   uuid,
    updated_by   uuid,
    version      bigint      NOT NULL,
    CONSTRAINT pk_tenant_memberships PRIMARY KEY (id),
    -- Once per tenant; leading with user_id, it serves the user's foreign key and login's lookup.
    CONSTRAINT uq_tenant_memberships_user_id_tenant_id UNIQUE (user_id, tenant_id),
    CONSTRAINT fk_tenant_memberships_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_tenant_memberships_user_id FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_tenant_memberships_role CHECK (role IN ('USER', 'ADMIN'))
);
CREATE INDEX idx_tenant_memberships_tenant_id ON tenant_memberships (tenant_id);
--rollback DROP TABLE tenant_memberships;

--changeset dev:0012-platform-users
-- Platform staff: separate from tenant users, no tenant. An operator inserts them (docs/runbook.md).
CREATE TABLE platform_users (
    id            uuid        NOT NULL,
    email         text        NOT NULL,
    full_name     text        NOT NULL,
    password_hash text        NOT NULL,
    role          text        NOT NULL,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    created_by    uuid,
    updated_by    uuid,
    version       bigint      NOT NULL,
    CONSTRAINT pk_platform_users PRIMARY KEY (id),
    CONSTRAINT ck_platform_users_email_normalized CHECK (email = lower(trim(email))),
    CONSTRAINT uq_platform_users_email UNIQUE (email),
    CONSTRAINT ck_platform_users_role CHECK (role IN ('STAFF'))
);
--rollback DROP TABLE platform_users;

--changeset dev:0012-refresh-tokens-tenant-id
-- A refresh issues tokens for the tenant its token was for. NOT NULL with no default: on a project that
-- already has sessions, delete refresh_tokens first in this changeset (everyone signs in again).
ALTER TABLE refresh_tokens ADD COLUMN tenant_id uuid NOT NULL;
ALTER TABLE refresh_tokens ADD CONSTRAINT fk_refresh_tokens_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id);
CREATE INDEX idx_refresh_tokens_tenant_id ON refresh_tokens (tenant_id);
--rollback ALTER TABLE refresh_tokens DROP COLUMN tenant_id;

--changeset dev:0012-platform-audit
-- Platform staff's actions inside a tenant (impersonations, direct edits): append-only, always on.
CREATE TABLE platform_audit (
    id         uuid        NOT NULL,
    actor_id   uuid        NOT NULL,
    tenant_id  uuid        NOT NULL,
    action     text        NOT NULL,
    detail     text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid,
    updated_by uuid,
    version    bigint      NOT NULL,
    CONSTRAINT pk_platform_audit PRIMARY KEY (id),
    CONSTRAINT fk_platform_audit_actor_id FOREIGN KEY (actor_id) REFERENCES platform_users (id),
    CONSTRAINT fk_platform_audit_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);
-- A tenant's support history, newest first (UUIDv7 ids).
CREATE INDEX idx_platform_audit_tenant_id_id ON platform_audit (tenant_id, id);
CREATE INDEX idx_platform_audit_actor_id ON platform_audit (actor_id);
--rollback DROP TABLE platform_audit;
