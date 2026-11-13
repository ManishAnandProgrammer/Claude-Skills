--liquibase formatted sql

--changeset dev:0003-todos
CREATE TABLE todos (
    id           uuid        NOT NULL,
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
    CONSTRAINT fk_todos_todo_list_id_owner_id FOREIGN KEY (todo_list_id, owner_id) REFERENCES todo_lists (id, owner_id),
    CONSTRAINT fk_todos_owner_id FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT ck_todos_status CHECK (status IN ('OPEN', 'DONE')),
    CONSTRAINT ck_todos_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_todos_completed_at CHECK ((status = 'DONE') = (completed_at IS NOT NULL))
);
CREATE INDEX idx_todos_todo_list_id_owner_id ON todos (todo_list_id, owner_id);
-- One index per offered order, each ending in the id tie-breaker of its keyset.
CREATE INDEX idx_todos_owner_id_due_sort_id ON todos (owner_id, due_sort, id);
CREATE INDEX idx_todos_owner_id_status_due_sort_id ON todos (owner_id, status, due_sort, id);
CREATE INDEX idx_todos_owner_id_id ON todos (owner_id, id);
--rollback DROP TABLE todos;
