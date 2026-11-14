--liquibase formatted sql

-- The ADMIN role, for the first feature that needs one: the configuration-metadata admin API
-- (0008). bootstrap-copy.sh copies this changeset with the sign-in and metadata pieces together.

--changeset dev:0007-users-admin-role
ALTER TABLE users DROP CONSTRAINT ck_users_role;
ALTER TABLE users ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'));
--rollback ALTER TABLE users DROP CONSTRAINT ck_users_role;
--rollback ALTER TABLE users ADD CONSTRAINT ck_users_role CHECK (role IN ('USER'));
