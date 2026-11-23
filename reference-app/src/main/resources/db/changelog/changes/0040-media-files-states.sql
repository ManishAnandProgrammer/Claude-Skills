--liquibase formatted sql

-- The media files' states renamed for what they are: PENDING is UNCLAIMED, IN_REVIEW is ATTACHED, UPLOADED
-- is VERIFIED, and expires_at, which sat beside upload_expires_at, is claimable_until. The previous release
-- writes the old values, which the new CHECK refuses: stop its instances before this runs (house-review
-- DATA-13). It rewrites every row and builds two indexes, which blocks writes to the table while it runs: on
-- a big table, deploy in a quiet hour (docs/runbook.md). bootstrap-copy.sh copies this file with the media piece.

--changeset dev:0040-media-files-states
-- The partial indexes name the old values, and would match no row after the update.
DROP INDEX idx_media_files_in_review_upload_expires_at_id;
DROP INDEX idx_media_files_pending_expires_at_id;
ALTER TABLE media_files DROP CONSTRAINT ck_media_files_status;
-- tenancy-row:begin
-- -- RLS is lifted for this changeset's own transaction only, so the owner rewrites every tenant's rows.
-- ALTER TABLE media_files NO FORCE ROW LEVEL SECURITY;
-- tenancy-row:end
UPDATE media_files
   SET status = CASE status WHEN 'PENDING' THEN 'UNCLAIMED' WHEN 'IN_REVIEW' THEN 'ATTACHED' ELSE 'VERIFIED' END
 WHERE status IN ('PENDING', 'IN_REVIEW', 'UPLOADED');
-- tenancy-row:begin
-- ALTER TABLE media_files FORCE ROW LEVEL SECURITY;
-- tenancy-row:end
ALTER TABLE media_files ADD CONSTRAINT ck_media_files_status
    CHECK (status IN ('UNCLAIMED', 'ATTACHED', 'VERIFIED', 'MISSING', 'RELEASED'));
ALTER TABLE media_files RENAME COLUMN expires_at TO claimable_until;
-- tenancy-row:begin
-- CREATE INDEX idx_media_files_attached_upload_expires_at_id ON media_files (tenant_id, upload_expires_at, id) WHERE status = 'ATTACHED';
-- CREATE INDEX idx_media_files_unclaimed_claimable_until_id ON media_files (tenant_id, claimable_until, id) WHERE status = 'UNCLAIMED';
-- tenancy-row:else
CREATE INDEX idx_media_files_attached_upload_expires_at_id ON media_files (upload_expires_at, id) WHERE status = 'ATTACHED';
CREATE INDEX idx_media_files_unclaimed_claimable_until_id ON media_files (claimable_until, id) WHERE status = 'UNCLAIMED';
-- tenancy-row:end
--rollback DROP INDEX idx_media_files_unclaimed_claimable_until_id;
--rollback DROP INDEX idx_media_files_attached_upload_expires_at_id;
--rollback ALTER TABLE media_files RENAME COLUMN claimable_until TO expires_at;
--rollback ALTER TABLE media_files DROP CONSTRAINT ck_media_files_status;
-- tenancy-row:begin
-- --rollback ALTER TABLE media_files NO FORCE ROW LEVEL SECURITY;
-- tenancy-row:end
--rollback UPDATE media_files SET status = CASE status WHEN 'UNCLAIMED' THEN 'PENDING' WHEN 'ATTACHED' THEN 'IN_REVIEW' ELSE 'UPLOADED' END WHERE status IN ('UNCLAIMED', 'ATTACHED', 'VERIFIED');
-- tenancy-row:begin
-- --rollback ALTER TABLE media_files FORCE ROW LEVEL SECURITY;
-- tenancy-row:end
--rollback ALTER TABLE media_files ADD CONSTRAINT ck_media_files_status CHECK (status IN ('PENDING', 'IN_REVIEW', 'UPLOADED', 'MISSING', 'RELEASED'));
-- tenancy-row:begin
-- --rollback CREATE INDEX idx_media_files_in_review_upload_expires_at_id ON media_files (tenant_id, upload_expires_at, id) WHERE status = 'IN_REVIEW';
-- --rollback CREATE INDEX idx_media_files_pending_expires_at_id ON media_files (tenant_id, expires_at, id) WHERE status = 'PENDING';
-- tenancy-row:else
--rollback CREATE INDEX idx_media_files_in_review_upload_expires_at_id ON media_files (upload_expires_at, id) WHERE status = 'IN_REVIEW';
--rollback CREATE INDEX idx_media_files_pending_expires_at_id ON media_files (expires_at, id) WHERE status = 'PENDING';
-- tenancy-row:end
