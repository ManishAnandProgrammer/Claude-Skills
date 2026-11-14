--liquibase formatted sql

-- Plans: the example's planning module (a parent in another module, no overlapping ranges).
-- bootstrap-copy.sh copies this file with the example piece.

--changeset dev:0011-btree-gist
-- The exclusion constraint below compares owner_id (a uuid) with =, which a GiST index does only through
-- btree_gist. The extension is trusted since PostgreSQL 13, so the database's owner may create it without
-- superuser rights; a managed database must allow it (Amazon RDS and Cloud SQL do), or a DBA creates it
-- once before the first migration.
CREATE EXTENSION IF NOT EXISTS btree_gist;
-- No rollback statement: the database may have had the extension before this changeset, and other
-- objects may use it; dropping it is a DBA's decision.
--rollback SELECT 1;

--changeset dev:0011-plans
CREATE TABLE plans (
    id         uuid        NOT NULL,
    -- tenancy-row:begin
    -- tenant_id  uuid        NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    -- tenancy-row:end
    todo_id    uuid        NOT NULL,
    owner_id   uuid        NOT NULL,
    starts_on  date        NOT NULL,
    ends_on    date        NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid,
    updated_by uuid,
    version    bigint      NOT NULL,
    CONSTRAINT pk_plans PRIMARY KEY (id),
    -- The todo is another module's: an id with a real foreign key, of the same owner (uq_todos_id_owner_id,
    -- 0005). No cascade: a todo with plans is refused (TODO_IN_USE), as with time entries.
    -- tenancy-row:begin
    -- CONSTRAINT fk_plans_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    -- CONSTRAINT fk_plans_todo_id_owner_id FOREIGN KEY (todo_id, owner_id, tenant_id) REFERENCES todos (id, owner_id, tenant_id),
    -- tenancy-row:else
    CONSTRAINT fk_plans_todo_id_owner_id FOREIGN KEY (todo_id, owner_id) REFERENCES todos (id, owner_id),
    -- tenancy-row:end
    CONSTRAINT fk_plans_owner_id FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT ck_plans_dates CHECK (starts_on <= ends_on),
    -- One owner's plans never share a day: both ends inclusive ('[]'), so a plan ending on the 22nd and one
    -- starting on the 22nd collide. The database decides, so two concurrent creates can't both pass.
    -- A record with states would add
    -- WHERE (status <> 'CANCELLED') so that only live records hold their days.
    -- tenancy-row:begin
    -- CONSTRAINT ex_plans_owner_id_dates EXCLUDE USING gist (owner_id WITH =, tenant_id WITH =, daterange(starts_on, ends_on, '[]') WITH &&)
    -- tenancy-row:else
    CONSTRAINT ex_plans_owner_id_dates EXCLUDE USING gist (owner_id WITH =, daterange(starts_on, ends_on, '[]') WITH &&)
    -- tenancy-row:end
);
-- One index per offered order (STARTS_ON: starts_on, id; NEWEST: id descending) per filter that scopes
-- the list, each ending in the id tie-breaker of its keyset:
-- * all the owner's plans;
-- * one todo's plans (todoId); the first also serves the todo's foreign key (its delete looks for plans).
-- tenancy-row:begin
-- CREATE INDEX idx_plans_owner_id_starts_on_id ON plans (owner_id, tenant_id, starts_on, id);
-- CREATE INDEX idx_plans_owner_id_id ON plans (owner_id, tenant_id, id);
-- CREATE INDEX idx_plans_todo_id_owner_id_starts_on_id ON plans (todo_id, owner_id, tenant_id, starts_on, id);
-- CREATE INDEX idx_plans_todo_id_owner_id_id ON plans (todo_id, owner_id, tenant_id, id);
-- ALTER TABLE plans ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE plans FORCE ROW LEVEL SECURITY;
-- CREATE POLICY tenant_isolation ON plans
--     USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
--     WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- tenancy-row:else
CREATE INDEX idx_plans_owner_id_starts_on_id ON plans (owner_id, starts_on, id);
CREATE INDEX idx_plans_owner_id_id ON plans (owner_id, id);
CREATE INDEX idx_plans_todo_id_owner_id_starts_on_id ON plans (todo_id, owner_id, starts_on, id);
CREATE INDEX idx_plans_todo_id_owner_id_id ON plans (todo_id, owner_id, id);
-- tenancy-row:end
--rollback DROP TABLE plans;
