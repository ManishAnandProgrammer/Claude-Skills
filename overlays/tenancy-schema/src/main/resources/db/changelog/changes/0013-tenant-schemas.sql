--liquibase formatted sql

-- Schema per tenant (the tenancy-schema piece): each tenant schema's migration record, and the
-- application role's rights. ${appRole} is the pool's role (app.tenancy.schema.app-role); this
-- changelog runs as the owner role, which owns every table.

--changeset dev:0013-tenant-migrations
CREATE TABLE tenant_migrations (
    id             uuid        NOT NULL,
    tenant_id      uuid        NOT NULL,
    schema_name    text        NOT NULL,
    status         text        NOT NULL,
    -- The newest changeset of the last successful run: a schema behind the template is lagging.
    last_changeset text,
    migrated_at    timestamptz,
    error          text,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    created_by     uuid,
    updated_by     uuid,
    version        bigint      NOT NULL,
    CONSTRAINT pk_tenant_migrations PRIMARY KEY (id),
    CONSTRAINT uq_tenant_migrations_tenant_id UNIQUE (tenant_id),
    CONSTRAINT uq_tenant_migrations_schema_name UNIQUE (schema_name),
    CONSTRAINT fk_tenant_migrations_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT ck_tenant_migrations_status CHECK (status IN ('PROVISIONING', 'MIGRATED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_tenant_migrations_schema_name CHECK (schema_name ~ '^t_[0-9a-f]{32}$')
);
-- The provisioning job's queue and the migrations' list, each by status.
CREATE INDEX idx_tenant_migrations_status_updated_at_id ON tenant_migrations (status, updated_at, id);
--rollback DROP TABLE tenant_migrations;

--changeset dev:0013-app-role-grants
-- The application role reads and writes, never owns: every table the owner creates from now on, in any
-- schema (the tenant schemas included), is granted to it; the migrator revokes Liquibase's own tables
-- again after each run. Usage on each tenant schema is granted when the schema is created.
GRANT USAGE ON SCHEMA platform TO ${appRole};
GRANT SELECT, INSERT, UPDATE, DELETE ON users, refresh_tokens, tenants, tenant_memberships,
    platform_users, tenant_migrations TO ${appRole};
-- Append-only: the application may add audit and status-history rows, never change or remove them.
GRANT SELECT, INSERT ON platform_audit, tenant_status_changes TO ${appRole};
ALTER DEFAULT PRIVILEGES GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${appRole};
--rollback ALTER DEFAULT PRIVILEGES REVOKE SELECT, INSERT, UPDATE, DELETE ON TABLES FROM ${appRole};
--rollback REVOKE ALL ON ALL TABLES IN SCHEMA platform FROM ${appRole};
--rollback REVOKE USAGE ON SCHEMA platform FROM ${appRole};
