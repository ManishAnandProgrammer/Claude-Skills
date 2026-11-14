--liquibase formatted sql

--changeset dev:0003-todos
CREATE TABLE todos (
    id           uuid        NOT NULL,
    -- tenancy-row:begin
    -- tenant_id    uuid        NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    -- tenancy-row:end
    todo_list_id uuid        NOT NULL,
    owner_id     uuid        NOT NULL,
    title        text        NOT NULL,
    notes        text,
    status       text        NOT NULL,
    priority     text        NOT NULL,
    due_date     date,
    -- Keyset paging needs a non-null sort key: undated todos sort last, after any real date.
    due_sort     date        GENERATED ALWAYS AS (coalesce(due_date, date '9999-12-31')) STORED,
    completed_at timestamptz,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    created_by   uuid,
    updated_by   uuid,
    version      bigint      NOT NULL,
    CONSTRAINT pk_todos PRIMARY KEY (id),
    -- tenancy-row:begin
    -- CONSTRAINT fk_todos_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    -- -- The composite key also keeps the list in the todo's tenant: the database refuses a todo in
    -- -- another tenant's list.
    -- CONSTRAINT fk_todos_todo_list_id_owner_id FOREIGN KEY (todo_list_id, owner_id, tenant_id) REFERENCES todo_lists (id, owner_id, tenant_id),
    -- tenancy-row:else
    CONSTRAINT fk_todos_todo_list_id_owner_id FOREIGN KEY (todo_list_id, owner_id) REFERENCES todo_lists (id, owner_id),
    -- tenancy-row:end
    CONSTRAINT fk_todos_owner_id FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT ck_todos_status CHECK (status IN ('OPEN', 'DONE')),
    CONSTRAINT ck_todos_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_todos_completed_at CHECK ((status = 'DONE') = (completed_at IS NOT NULL)),
    -- A real date on the sentinel would sort among the undated todos (the API stops it earlier).
    CONSTRAINT ck_todos_due_date CHECK (due_date < date '9999-12-31')
);
-- One index per offered order (DUE_DATE: due_sort, id; NEWEST: id descending) per filter that
-- scopes the list, each ending in the id tie-breaker of its keyset:
-- * one list's todos (todoListId, the main screen); the first also serves the composite foreign key;
-- * all the owner's todos;
-- * the owner's todos in one status (open todos are the usual view; done ones pile up).
-- Left to the order index on purpose: priority (three values, none rare), status within one list
-- (a list is small), dueOnOrBefore (under DUE_DATE a range of the due_sort index, since the
-- repository also bounds due_sort; under NEWEST a filter), and query (a contains search no btree
-- serves; add pg_trgm if it gets slow).
-- tenancy-row:begin
-- -- Row mode: ILIKE isn't leakproof, so under the policy it filters the owner's rows even with a pg_trgm
-- -- index (TEN-36): the owner-scoped order index is what bounds the search.
-- CREATE INDEX idx_todos_todo_list_id_owner_id_due_sort_id ON todos (todo_list_id, owner_id, tenant_id, due_sort, id);
-- CREATE INDEX idx_todos_todo_list_id_owner_id_id ON todos (todo_list_id, owner_id, tenant_id, id);
-- CREATE INDEX idx_todos_owner_id_due_sort_id ON todos (owner_id, tenant_id, due_sort, id);
-- CREATE INDEX idx_todos_owner_id_id ON todos (owner_id, tenant_id, id);
-- CREATE INDEX idx_todos_owner_id_status_due_sort_id ON todos (owner_id, tenant_id, status, due_sort, id);
-- CREATE INDEX idx_todos_owner_id_status_id ON todos (owner_id, tenant_id, status, id);
-- ALTER TABLE todos ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE todos FORCE ROW LEVEL SECURITY;
-- CREATE POLICY tenant_isolation ON todos
--     USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
--     WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- tenancy-row:else
CREATE INDEX idx_todos_todo_list_id_owner_id_due_sort_id ON todos (todo_list_id, owner_id, due_sort, id);
CREATE INDEX idx_todos_todo_list_id_owner_id_id ON todos (todo_list_id, owner_id, id);
CREATE INDEX idx_todos_owner_id_due_sort_id ON todos (owner_id, due_sort, id);
CREATE INDEX idx_todos_owner_id_id ON todos (owner_id, id);
CREATE INDEX idx_todos_owner_id_status_due_sort_id ON todos (owner_id, status, due_sort, id);
CREATE INDEX idx_todos_owner_id_status_id ON todos (owner_id, status, id);
-- tenancy-row:end
--rollback DROP TABLE todos;
