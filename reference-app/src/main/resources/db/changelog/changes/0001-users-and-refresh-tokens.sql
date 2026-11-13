--liquibase formatted sql

-- Text columns throughout: the API's @MaxLength (app.limits.text) is the length contract.

--changeset dev:0001-users
CREATE TABLE users (
    id            uuid        NOT NULL,
    email         text        NOT NULL,
    full_name     text        NOT NULL,
    password_hash text        NOT NULL,
    role          text        NOT NULL,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    created_by    uuid,
    updated_by    uuid,
    version       bigint      NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT ck_users_email_normalized CHECK (email = lower(trim(email))),
    CONSTRAINT ck_users_role CHECK (role IN ('USER'))
);
-- Unique as people read it: case and surrounding spaces don't make a different address.
CREATE UNIQUE INDEX uq_users_email ON users (lower(trim(email)));
--rollback DROP TABLE users;

--changeset dev:0001-refresh-tokens
CREATE TABLE refresh_tokens (
    id                uuid        NOT NULL,
    user_id           uuid        NOT NULL,
    token_hash        text        NOT NULL,
    expires_at        timestamptz NOT NULL,
    revoked_at        timestamptz,
    revocation_reason text,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    created_by        uuid,
    updated_by        uuid,
    version           bigint      NOT NULL,
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user_id FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_refresh_tokens_revocation_reason CHECK (revocation_reason IN ('ROTATED', 'LOGOUT', 'REUSE')),
    CONSTRAINT ck_refresh_tokens_revocation CHECK ((revoked_at IS NULL) = (revocation_reason IS NULL))
);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);
--rollback DROP TABLE refresh_tokens;
