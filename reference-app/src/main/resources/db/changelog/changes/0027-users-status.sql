--liquibase formatted sql

-- The account lifecycle (the sign-in piece): an account is ACTIVE or SUSPENDED. A suspended account gets
-- no token from any path (login, refresh, a password change; with tenancy switch-tenant and impersonation),
-- and its suspension revoked its refresh tokens. Every existing account is ACTIVE. The refresh tokens'
-- revocation reasons gain the two that end every session of an account at once.

--changeset dev:0027-users-status
ALTER TABLE users ADD COLUMN status text NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE users ADD CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED'));
--rollback ALTER TABLE users DROP COLUMN status;

--changeset dev:0027-refresh-tokens-revocation-reasons
ALTER TABLE refresh_tokens DROP CONSTRAINT ck_refresh_tokens_revocation_reason;
ALTER TABLE refresh_tokens ADD CONSTRAINT ck_refresh_tokens_revocation_reason
    CHECK (revocation_reason IN ('ROTATED', 'LOGOUT', 'REUSE', 'PASSWORD_CHANGED', 'SUSPENDED'));
--rollback ALTER TABLE refresh_tokens DROP CONSTRAINT ck_refresh_tokens_revocation_reason;
--rollback ALTER TABLE refresh_tokens ADD CONSTRAINT ck_refresh_tokens_revocation_reason CHECK (revocation_reason IN ('ROTATED', 'LOGOUT', 'REUSE'));
