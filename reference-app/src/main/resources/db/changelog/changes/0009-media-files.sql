--liquibase formatted sql

-- Media uploads (the media piece): every issued upload URL is a media_files row, and each purpose's
-- limits are an upload_policies row an admin changes without a deployment. A purpose's seed row comes
-- with the module that attaches its files, in that module's changeset (row mode: in its listener on
-- TenantProvisioned, since a changeset can't seed a tenant's rows).

--changeset dev:0009-upload-policies
CREATE TABLE upload_policies (
    id             uuid        NOT NULL,
    -- tenancy-row:begin
    -- tenant_id      uuid        NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    -- tenancy-row:end
    -- An UploadPurpose constant: purposes are code, their limits data.
    purpose        text        NOT NULL,
    min_size_bytes bigint      NOT NULL,
    max_size_bytes bigint      NOT NULL,
    min_files      integer     NOT NULL,
    max_files      integer     NOT NULL,
    -- Exact types, each also in the code's allow-list (AllowedContentTypes).
    content_types  text[]      NOT NULL,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    created_by     uuid,
    updated_by     uuid,
    version        bigint      NOT NULL,
    CONSTRAINT pk_upload_policies PRIMARY KEY (id),
    -- tenancy-row:begin
    -- CONSTRAINT uq_upload_policies_purpose UNIQUE (tenant_id, purpose),
    -- CONSTRAINT fk_upload_policies_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    -- tenancy-row:else
    CONSTRAINT uq_upload_policies_purpose UNIQUE (purpose),
    -- tenancy-row:end
    -- An empty file is never an upload: the signature fixes the size, so 0 would accept nothing useful.
    CONSTRAINT ck_upload_policies_min_size_bytes CHECK (min_size_bytes >= 1),
    CONSTRAINT ck_upload_policies_size_range CHECK (min_size_bytes <= max_size_bytes),
    CONSTRAINT ck_upload_policies_min_files CHECK (min_files >= 0),
    CONSTRAINT ck_upload_policies_file_range CHECK (min_files <= max_files AND max_files >= 1),
    CONSTRAINT ck_upload_policies_content_types CHECK (cardinality(content_types) >= 1)
);
-- tenancy-row:begin
-- ALTER TABLE upload_policies ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE upload_policies FORCE ROW LEVEL SECURITY;
-- CREATE POLICY tenant_isolation ON upload_policies
--     USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
--     WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- tenancy-row:end
--rollback DROP TABLE upload_policies;

--changeset dev:0009-media-files
CREATE TABLE media_files (
    id                uuid        NOT NULL,
    -- tenancy-row:begin
    -- tenant_id         uuid        NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    -- tenancy-row:end
    -- {purpose}/{random uuid}: no personal data, nothing to escape. The file name is a column.
    key               text        NOT NULL,
    purpose           text        NOT NULL,
    file_name         text        NOT NULL,
    content_type      text        NOT NULL,
    size_bytes        bigint      NOT NULL,
    status            text        NOT NULL,
    -- Who asked for the upload URL: only they can attach the file. Not a foreign key: the media piece
    -- works without the sign-in piece's users table (users in an identity provider).
    uploaded_by       uuid        NOT NULL,
    -- When the signed PUT stops being accepted; the verification job checks a file only after it.
    upload_expires_at timestamptz NOT NULL,
    -- When a PENDING (never attached) file stops being claimable and becomes the cleanup job's.
    expires_at        timestamptz NOT NULL,
    -- Job bookkeeping, not mapped: the instance that leased the row (MediaVerificationJob, MediaCleanupJob) owns it until then.
    lease_until       timestamptz,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    created_by        uuid,
    updated_by        uuid,
    version           bigint      NOT NULL,
    CONSTRAINT pk_media_files PRIMARY KEY (id),
    -- Serves the claim's key IN (...).
    -- tenancy-row:begin
    -- CONSTRAINT uq_media_files_key UNIQUE (tenant_id, key),
    -- CONSTRAINT fk_media_files_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    -- -- Target of the owning records' composite foreign keys (todo_attachments): a file of their own tenant.
    -- CONSTRAINT uq_media_files_id_tenant_id UNIQUE (id, tenant_id),
    -- tenancy-row:else
    CONSTRAINT uq_media_files_key UNIQUE (key),
    -- tenancy-row:end
    CONSTRAINT ck_media_files_status CHECK (status IN ('PENDING', 'IN_REVIEW', 'UPLOADED', 'MISSING', 'RELEASED')),
    CONSTRAINT ck_media_files_size_bytes CHECK (size_bytes >= 1),
    CONSTRAINT ck_media_files_expiry CHECK (upload_expires_at <= expires_at)
);
-- One per job query (MediaFileRepository), each in that query's order, so a batch is read off the
-- index's front and LIMIT stops early:
-- the verification job's: attached files whose upload URL expired;
-- the cleanup job's: never-attached files past their claim window;
-- the cleanup job's: removed files whose upload URL expired (no PUT can recreate them after the delete).
-- tenancy-row:begin
-- -- The jobs run per tenant (TenantScope), so each index leads with the tenant the policy names.
-- CREATE INDEX idx_media_files_in_review_upload_expires_at_id ON media_files (tenant_id, upload_expires_at, id) WHERE status = 'IN_REVIEW';
-- CREATE INDEX idx_media_files_pending_expires_at_id ON media_files (tenant_id, expires_at, id) WHERE status = 'PENDING';
-- CREATE INDEX idx_media_files_released_upload_expires_at_id ON media_files (tenant_id, upload_expires_at, id) WHERE status = 'RELEASED';
-- ALTER TABLE media_files ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE media_files FORCE ROW LEVEL SECURITY;
-- CREATE POLICY tenant_isolation ON media_files
--     USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
--     WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- tenancy-row:else
CREATE INDEX idx_media_files_in_review_upload_expires_at_id ON media_files (upload_expires_at, id) WHERE status = 'IN_REVIEW';
CREATE INDEX idx_media_files_pending_expires_at_id ON media_files (expires_at, id) WHERE status = 'PENDING';
CREATE INDEX idx_media_files_released_upload_expires_at_id ON media_files (upload_expires_at, id) WHERE status = 'RELEASED';
-- tenancy-row:end
--rollback DROP TABLE media_files;
