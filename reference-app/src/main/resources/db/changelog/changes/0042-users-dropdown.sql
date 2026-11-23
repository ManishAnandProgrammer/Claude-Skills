--liquibase formatted sql

-- The person picker (GET /users/dropdown, UserRepositoryImpl.findActiveWithRoleIn): active accounts ordered by full
-- name, the id last as the tie-breaker of its keyset (names repeat). A batch reads the index in order and stops; the
-- status and the access are checked on the rows it reads (house-review DATA-10).
-- bootstrap-copy.sh copies this file with the sign-in piece, without tenancy (members are picked another way).

--changeset dev:0042-users-dropdown-index
CREATE INDEX idx_users_full_name_id ON users (full_name, id);
--rollback DROP INDEX idx_users_full_name_id;
