--liquibase formatted sql

-- The session version per membership (the session-version piece, with tenancy): a tenant token carries its
-- membership's id and number (claims membership_id, sv), and a request whose token carries another number,
-- or names a membership that no longer exists, is refused. Every change of the membership's access (its
-- role, its removal, the tenant's suspension, the member signing out everywhere) raises it in the changing
-- transaction. Per membership, so a change in one tenant leaves the user's tokens for the others alone; a
-- member removed and added again is a new membership, so no older token names it. It only goes up. Read by
-- primary key (pk_tenant_memberships) whenever Redis doesn't have it. A platform table (platform.yaml with
-- schema per tenant). bootstrap-copy.sh copies this file with the session-version piece and a tenancy piece.

--changeset dev:0022-memberships-session-version
ALTER TABLE tenant_memberships ADD COLUMN session_version int NOT NULL DEFAULT 0;
--rollback ALTER TABLE tenant_memberships DROP COLUMN session_version;
