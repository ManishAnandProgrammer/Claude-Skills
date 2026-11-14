--liquibase formatted sql

-- The ADMIN role, for the features with admin endpoints: the configuration-metadata admin API (0008)
-- and the upload-policy admin API (0009). bootstrap-copy.sh copies this file with the sign-in piece
-- and either of the metadata and media pieces.

--changeset dev:0007-users-admin-role
ALTER TABLE users DROP CONSTRAINT ck_users_role;
ALTER TABLE users ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'));
--rollback ALTER TABLE users DROP CONSTRAINT ck_users_role;
--rollback ALTER TABLE users ADD CONSTRAINT ck_users_role CHECK (role IN ('USER'));
