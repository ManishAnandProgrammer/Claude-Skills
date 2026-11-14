--liquibase formatted sql

--changeset test:tenancy-probe
-- Tenant tables shaped as every generated one is (house-review TEN-30..TEN-33): a parent mapped by the
-- tests' TenancyProbe entity, and a child whose composite foreign key keeps it in its parent's tenant.
CREATE TABLE tenancy_probe (
    id         uuid        NOT NULL,
    tenant_id  uuid        NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    label      text        NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid,
    updated_by uuid,
    version    bigint      NOT NULL,
    CONSTRAINT pk_tenancy_probe PRIMARY KEY (id),
    CONSTRAINT fk_tenancy_probe_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    -- Target of the child's composite foreign key; tenant_id last, so it serves the key and no list order.
    CONSTRAINT uq_tenancy_probe_id_tenant_id UNIQUE (id, tenant_id)
);
CREATE INDEX idx_tenancy_probe_tenant_id_label_id ON tenancy_probe (tenant_id, label, id);
ALTER TABLE tenancy_probe ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy_probe FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy_probe
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE tenancy_probe_notes (
    id        uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    probe_id  uuid NOT NULL,
    CONSTRAINT pk_tenancy_probe_notes PRIMARY KEY (id),
    CONSTRAINT fk_tenancy_probe_notes_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_tenancy_probe_notes_probe_id FOREIGN KEY (probe_id, tenant_id) REFERENCES tenancy_probe (id, tenant_id)
);
CREATE INDEX idx_tenancy_probe_notes_probe_id ON tenancy_probe_notes (probe_id, tenant_id);
ALTER TABLE tenancy_probe_notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy_probe_notes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenancy_probe_notes
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
--rollback DROP TABLE tenancy_probe_notes;
--rollback DROP TABLE tenancy_probe;
