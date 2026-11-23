--liquibase formatted sql

--changeset dev:0100-rooms
CREATE TABLE rooms (
    id         uuid        NOT NULL,
    name       text        NOT NULL,
    capacity   integer     NOT NULL,
    building   text        NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid,
    updated_by uuid,
    version    bigint      NOT NULL,
    CONSTRAINT pk_rooms PRIMARY KEY (id),
    CONSTRAINT ck_rooms_capacity CHECK (capacity > 0)
);
CREATE UNIQUE INDEX uq_rooms_name ON rooms (lower(trim(name)));
--rollback DROP TABLE rooms;
