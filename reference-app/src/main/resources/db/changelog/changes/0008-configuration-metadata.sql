--liquibase formatted sql

-- Configuration metadata (one typed value per key) and counters (gapless business numbers).
-- Per tenant with tenancy (META-09): schema per tenant runs this changeset in every tenant schema as it
-- is; row mode adds tenant_id, leading each table's unique key, and row-level security.

--changeset dev:0008-configuration-metadata
CREATE TABLE configuration_metadata (
    id         uuid        NOT NULL,
    -- tenancy-row:begin
    -- tenant_id  uuid        NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    -- tenancy-row:end
    key        text        NOT NULL,
    value      jsonb       NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid,
    updated_by uuid,
    version    bigint      NOT NULL,
    CONSTRAINT pk_configuration_metadata PRIMARY KEY (id),
    -- Its index serves the only lookup, key = ?.
    -- tenancy-row:begin
    -- CONSTRAINT uq_configuration_metadata_key UNIQUE (tenant_id, key),
    -- CONSTRAINT fk_configuration_metadata_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    -- tenancy-row:else
    CONSTRAINT uq_configuration_metadata_key UNIQUE (key),
    -- tenancy-row:end
    -- The names MetadataKey accepts, so a key written by hand (a seed, a fix) can't miss its declaration.
    CONSTRAINT ck_configuration_metadata_key CHECK (key ~ '^[A-Z][A-Z0-9_]*([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})?$'),
    -- Every value is a record, stored as a JSON object.
    CONSTRAINT ck_configuration_metadata_value CHECK (jsonb_typeof(value) = 'object')
);
-- tenancy-row:begin
-- ALTER TABLE configuration_metadata ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE configuration_metadata FORCE ROW LEVEL SECURITY;
-- CREATE POLICY tenant_isolation ON configuration_metadata
--     USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
--     WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- tenancy-row:end
--rollback DROP TABLE configuration_metadata;

--changeset dev:0008-counters
CREATE TABLE counters (
    id         uuid        NOT NULL,
    -- tenancy-row:begin
    -- tenant_id  uuid        NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    -- tenancy-row:end
    key        text        NOT NULL,
    last_value bigint      NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid,
    updated_by uuid,
    version    bigint      NOT NULL,
    CONSTRAINT pk_counters PRIMARY KEY (id),
    -- tenancy-row:begin
    -- CONSTRAINT uq_counters_key UNIQUE (tenant_id, key),
    -- CONSTRAINT fk_counters_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    -- tenancy-row:else
    CONSTRAINT uq_counters_key UNIQUE (key),
    -- tenancy-row:end
    -- UPPER_SNAKE_CASE, optionally ending in a canonical context id: the names Counters.perContext writes.
    CONSTRAINT ck_counters_key CHECK (key ~ '^[A-Z][A-Z0-9_]*([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})?$'),
    CONSTRAINT ck_counters_last_value CHECK (last_value >= 0)
);
-- tenancy-row:begin
-- ALTER TABLE counters ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE counters FORCE ROW LEVEL SECURITY;
-- CREATE POLICY tenant_isolation ON counters
--     USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
--     WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- tenancy-row:end
--rollback DROP TABLE counters;
