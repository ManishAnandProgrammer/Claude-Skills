--liquibase formatted sql

--changeset dev:0005-todos-id-owner-id
-- Target of the time entries' composite foreign key: an entry belongs only to a todo of its own owner.
-- tenancy-row:begin
-- ALTER TABLE todos ADD CONSTRAINT uq_todos_id_owner_id UNIQUE (id, owner_id, tenant_id);
-- tenancy-row:else
ALTER TABLE todos ADD CONSTRAINT uq_todos_id_owner_id UNIQUE (id, owner_id);
-- tenancy-row:end
--rollback ALTER TABLE todos DROP CONSTRAINT uq_todos_id_owner_id;

--changeset dev:0005-time-entries
CREATE TABLE time_entries (
    id         uuid        NOT NULL,
    -- tenancy-row:begin
    -- tenant_id  uuid        NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    -- tenancy-row:end
    todo_id    uuid        NOT NULL,
    owner_id   uuid        NOT NULL,
    minutes    integer     NOT NULL,
    spent_on   date        NOT NULL,
    note       text,
    billable   boolean     NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid,
    updated_by uuid,
    version    bigint      NOT NULL,
    CONSTRAINT pk_time_entries PRIMARY KEY (id),
    -- No ON DELETE CASCADE: a todo with time logged on it is refused (TODO_IN_USE), so billable
    -- time is never deleted as a side effect.
    -- tenancy-row:begin
    -- CONSTRAINT fk_time_entries_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    -- CONSTRAINT fk_time_entries_todo_id_owner_id FOREIGN KEY (todo_id, owner_id, tenant_id) REFERENCES todos (id, owner_id, tenant_id),
    -- tenancy-row:else
    CONSTRAINT fk_time_entries_todo_id_owner_id FOREIGN KEY (todo_id, owner_id) REFERENCES todos (id, owner_id),
    -- tenancy-row:end
    CONSTRAINT fk_time_entries_owner_id FOREIGN KEY (owner_id) REFERENCES users (id),
    -- The upper bound is configuration (app.limits.time-entry.max-minutes); zero or less is never time.
    CONSTRAINT ck_time_entries_minutes CHECK (minutes > 0)
);
-- The list is always one todo's entries (the path scopes it), so one index per offered order under
-- that scope, ending in the id tie-breaker: SPENT_ON (spent_on, id) and MINUTES (minutes, id), both
-- read backwards. The first also serves the composite foreign key. spentOnFrom/To are offered under
-- SPENT_ON only, a range of the first; minMinutes/maxMinutes under MINUTES only, a range of the second;
-- billable has its own index per order (0031).
-- tenancy-row:begin
-- CREATE INDEX idx_time_entries_todo_id_owner_id_spent_on_id ON time_entries (todo_id, owner_id, tenant_id, spent_on, id);
-- CREATE INDEX idx_time_entries_todo_id_owner_id_minutes_id ON time_entries (todo_id, owner_id, tenant_id, minutes, id);
-- ALTER TABLE time_entries ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE time_entries FORCE ROW LEVEL SECURITY;
-- CREATE POLICY tenant_isolation ON time_entries
--     USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
--     WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- tenancy-row:else
CREATE INDEX idx_time_entries_todo_id_owner_id_spent_on_id ON time_entries (todo_id, owner_id, spent_on, id);
CREATE INDEX idx_time_entries_todo_id_owner_id_minutes_id ON time_entries (todo_id, owner_id, minutes, id);
-- tenancy-row:end
CREATE INDEX idx_time_entries_owner_id ON time_entries (owner_id);
--rollback DROP TABLE time_entries;
