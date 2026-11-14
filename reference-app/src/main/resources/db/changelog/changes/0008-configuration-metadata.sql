--liquibase formatted sql

-- Configuration metadata (one typed value per key) and counters (gapless business numbers).
-- This is the single-tenant variant, also the one for schema-per-tenant: Liquibase runs it in every
-- tenant schema, so no tenant column. Row-based tenancy replaces each table's unique (key) with
-- (tenant_id, key); see the metadata package-info for the full adaptation:
--     tenant_id uuid NOT NULL,
--     CONSTRAINT uq_configuration_metadata_tenant_id_key UNIQUE (tenant_id, key),
--     CONSTRAINT uq_counters_tenant_id_key UNIQUE (tenant_id, key),

--changeset dev:0008-configuration-metadata
CREATE TABLE configuration_metadata (
    id         uuid        NOT NULL,
    key        text        NOT NULL,
    value      jsonb       NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid,
    updated_by uuid,
    version    bigint      NOT NULL,
    CONSTRAINT pk_configuration_metadata PRIMARY KEY (id),
    -- Its index serves the only lookup, key = ?.
    CONSTRAINT uq_configuration_metadata_key UNIQUE (key),
    -- The names MetadataKey accepts, so a key written by hand (a seed, a fix) can't miss its declaration.
    CONSTRAINT ck_configuration_metadata_key CHECK (key ~ '^[A-Z][A-Z0-9_]*([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})?$'),
    -- Every value is a record, stored as a JSON object.
    CONSTRAINT ck_configuration_metadata_value CHECK (jsonb_typeof(value) = 'object')
);
--rollback DROP TABLE configuration_metadata;

--changeset dev:0008-counters
CREATE TABLE counters (
    id         uuid        NOT NULL,
    key        text        NOT NULL,
    last_value bigint      NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid,
    updated_by uuid,
    version    bigint      NOT NULL,
    CONSTRAINT pk_counters PRIMARY KEY (id),
    CONSTRAINT uq_counters_key UNIQUE (key),
    CONSTRAINT ck_counters_key CHECK (key ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_counters_last_value CHECK (last_value >= 0)
);
--rollback DROP TABLE counters;
