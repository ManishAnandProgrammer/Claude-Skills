--liquibase formatted sql

--changeset dev:0004-tags
CREATE TABLE tags (
    id         uuid        NOT NULL,
    owner_id   uuid        NOT NULL,
    name       text        NOT NULL,
    color      text,
    active     boolean     NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid,
    updated_by uuid,
    version    bigint      NOT NULL,
    CONSTRAINT pk_tags PRIMARY KEY (id),
    CONSTRAINT fk_tags_owner_id FOREIGN KEY (owner_id) REFERENCES users (id),
    -- Stored upper-case (the mapper normalises it), so one colour has one spelling.
    CONSTRAINT ck_tags_color CHECK (color ~ '^#[0-9A-F]{6}$')
);
-- One name per owner as people read it ("Urgent" and " urgent" are the same tag); it also serves
-- the owner's foreign key.
CREATE UNIQUE INDEX uq_tags_owner_id_name ON tags (owner_id, lower(trim(name)));
-- One index per offered order, ending in the id tie-breaker: by name (the page's NAME and the
-- dropdown with status ALL, both directions), newest first (UUIDv7 ids are time-ordered), and by
-- name within one active state (status ACTIVE or INACTIVE: the dropdown's default, and the page's
-- status filter under NAME). The status filter under NEWEST and query are left to these: a user's
-- tags are a bounded set, and a contains search no btree serves.
CREATE INDEX idx_tags_owner_id_name_id ON tags (owner_id, name, id);
CREATE INDEX idx_tags_owner_id_id ON tags (owner_id, id);
CREATE INDEX idx_tags_owner_id_active_name_id ON tags (owner_id, active, name, id);
--rollback DROP TABLE tags;
