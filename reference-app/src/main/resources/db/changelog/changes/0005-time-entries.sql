--liquibase formatted sql

--changeset dev:0005-todos-id-owner-id
-- Target of the time entries' composite foreign key: an entry belongs only to a todo of its own owner.
ALTER TABLE todos ADD CONSTRAINT uq_todos_id_owner_id UNIQUE (id, owner_id);
--rollback ALTER TABLE todos DROP CONSTRAINT uq_todos_id_owner_id;

--changeset dev:0005-time-entries
CREATE TABLE time_entries (
    id         uuid        NOT NULL,
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
    CONSTRAINT fk_time_entries_todo_id_owner_id FOREIGN KEY (todo_id, owner_id) REFERENCES todos (id, owner_id),
    CONSTRAINT fk_time_entries_owner_id FOREIGN KEY (owner_id) REFERENCES users (id),
    -- The upper bound is configuration (app.limits.time-entry.max-minutes); zero or less is never time.
    CONSTRAINT ck_time_entries_minutes CHECK (minutes > 0)
);
-- The list is always one todo's entries (the path scopes it), so one index per offered order under
-- that scope, ending in the id tie-breaker: SPENT_ON (spent_on, id) and MINUTES (minutes, id), both
-- read backwards. The first also serves the composite foreign key. spentOnFrom/To are a range of
-- the first under SPENT_ON, minMinutes/maxMinutes of the second under MINUTES; otherwise they and
-- billable are filtered while walking (one todo's entries are few).
CREATE INDEX idx_time_entries_todo_id_owner_id_spent_on_id ON time_entries (todo_id, owner_id, spent_on, id);
CREATE INDEX idx_time_entries_todo_id_owner_id_minutes_id ON time_entries (todo_id, owner_id, minutes, id);
CREATE INDEX idx_time_entries_owner_id ON time_entries (owner_id);
--rollback DROP TABLE time_entries;
