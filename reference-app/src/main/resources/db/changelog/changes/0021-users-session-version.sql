--liquibase formatted sql

-- The session version (the session-version piece, without tenancy): every access token carries the
-- account's number (claim sv), a request whose token carries another number is refused, and every
-- change of the account's access raises it in the changing transaction. It only goes up: a number used
-- again would bring the tokens issued under it back. Read by primary key (pk_users) whenever Redis
-- doesn't have it. bootstrap-copy.sh copies this file with the session-version piece and no tenancy
-- piece (with tenancy, the number is the membership's: 0022).

--changeset dev:0021-users-session-version
ALTER TABLE users ADD COLUMN session_version int NOT NULL DEFAULT 0;
--rollback ALTER TABLE users DROP COLUMN session_version;
