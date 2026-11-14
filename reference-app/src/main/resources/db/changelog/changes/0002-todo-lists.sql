--liquibase formatted sql

--changeset dev:0002-todo-lists
CREATE TABLE todo_lists (
    id         uuid        NOT NULL,
    -- tenancy-row:begin
    -- -- Row mode: Hibernate fills it (@TenantId); the default fills it for native and fixture inserts, and
    -- -- without a tenant it is NULL, so the row is refused. tenant_id joins every key and index below,
    -- -- after the owner (DATA-10's scope order, and the owner's foreign key still leads); their names stay
    -- -- the single-tenant ones, so the constraint-to-error mappings serve every mode.
    -- tenant_id  uuid        NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    -- tenancy-row:end
    owner_id   uuid        NOT NULL,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid,
    updated_by uuid,
    version    bigint      NOT NULL,
    CONSTRAINT pk_todo_lists PRIMARY KEY (id),
    -- tenancy-row:begin
    -- CONSTRAINT fk_todo_lists_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    -- tenancy-row:end
    CONSTRAINT fk_todo_lists_owner_id FOREIGN KEY (owner_id) REFERENCES users (id),
    -- Target of the todos' composite foreign key: a todo lives only in a list of its own owner.
    -- tenancy-row:begin
    -- CONSTRAINT uq_todo_lists_id_owner_id UNIQUE (id, owner_id, tenant_id)
    -- tenancy-row:else
    CONSTRAINT uq_todo_lists_id_owner_id UNIQUE (id, owner_id)
    -- tenancy-row:end
);
-- One name per owner as people read it ("Groceries" and " groceries" are the same list).
-- One index per offered order, ending in the id tie-breaker: by name (the page's NAME and the
-- dropdown in both directions), and newest first (UUIDv7 ids are time-ordered). query is a contains
-- search no btree serves; it is filtered while walking them.
-- tenancy-row:begin
-- CREATE UNIQUE INDEX uq_todo_lists_owner_id_name ON todo_lists (owner_id, tenant_id, lower(trim(name)));
-- CREATE INDEX idx_todo_lists_owner_id_name_id ON todo_lists (owner_id, tenant_id, name, id);
-- CREATE INDEX idx_todo_lists_owner_id_id ON todo_lists (owner_id, tenant_id, id);
-- -- The database refuses another tenant's rows whatever the SQL: enabled and forced (the owner too) in
-- -- the changeset that creates the table, one policy for reads and writes (house-review TEN-30).
-- ALTER TABLE todo_lists ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE todo_lists FORCE ROW LEVEL SECURITY;
-- CREATE POLICY tenant_isolation ON todo_lists
--     USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
--     WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- tenancy-row:else
CREATE UNIQUE INDEX uq_todo_lists_owner_id_name ON todo_lists (owner_id, lower(trim(name)));
CREATE INDEX idx_todo_lists_owner_id_name_id ON todo_lists (owner_id, name, id);
CREATE INDEX idx_todo_lists_owner_id_id ON todo_lists (owner_id, id);
-- tenancy-row:end
--rollback DROP TABLE todo_lists;
