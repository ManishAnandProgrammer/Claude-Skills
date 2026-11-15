--liquibase formatted sql

-- Row mode: every insert names its tenant (house-review TEN-30), so one that forgets it fails at once
-- (its NULL tenant_id is refused by the policy's check, before NOT NULL would). The tenant tables created before that rule (0002 to 0011) took tenant_id from the session's
-- app.tenant_id by default, which let such an insert pass inside a tenant's transaction. Those
-- applied changesets are never edited, so their defaults are dropped here; a new tenant table has none.
-- bootstrap-copy.sh copies this file with row mode and a piece that brings tenant tables.

--changeset dev:0016-tenant-id-no-default
-- example:begin
ALTER TABLE todo_lists ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE todos ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE tags ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE time_entries ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE todo_tags ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE plans ALTER COLUMN tenant_id DROP DEFAULT;
-- example:end
-- metadata:begin
ALTER TABLE configuration_metadata ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE counters ALTER COLUMN tenant_id DROP DEFAULT;
-- metadata:end
-- media:begin
ALTER TABLE media_files ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE upload_policies ALTER COLUMN tenant_id DROP DEFAULT;
-- example:begin
ALTER TABLE todo_attachments ALTER COLUMN tenant_id DROP DEFAULT;
-- example:end
-- media:end
-- The defaults are not restored: an insert that relied on one was a bug the rule exists to find.
--rollback SELECT 1;
