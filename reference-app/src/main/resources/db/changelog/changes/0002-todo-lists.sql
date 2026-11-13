--liquibase formatted sql

--changeset dev:0002-todo-lists
CREATE TABLE todo_lists (
    id         uuid        NOT NULL,
    owner_id   uuid        NOT NULL,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid,
    updated_by uuid,
    version    bigint      NOT NULL,
    CONSTRAINT pk_todo_lists PRIMARY KEY (id),
    CONSTRAINT fk_todo_lists_owner_id FOREIGN KEY (owner_id) REFERENCES users (id),
    -- Target of the todos' composite foreign key: a todo lives only in a list of its own owner.
    CONSTRAINT uq_todo_lists_id_owner_id UNIQUE (id, owner_id)
);
-- One name per owner as people read it ("Groceries" and " groceries" are the same list); also
-- serves the owner's lists sorted by name, and the dropdown.
CREATE UNIQUE INDEX uq_todo_lists_owner_id_name ON todo_lists (owner_id, lower(trim(name)));
CREATE INDEX idx_todo_lists_owner_id_name_id ON todo_lists (owner_id, name, id);
-- The owner's lists newest first: UUIDv7 ids are time-ordered.
CREATE INDEX idx_todo_lists_owner_id_id ON todo_lists (owner_id, id);
--rollback DROP TABLE todo_lists;
