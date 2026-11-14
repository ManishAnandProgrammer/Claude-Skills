--liquibase formatted sql

-- Todo attachments: the example module's use of the media piece. bootstrap-copy.sh copies this file
-- only when both pieces are chosen.

-- tenancy-row:begin
-- -- Row mode: tenants provisioned from now on get the TODO_ATTACHMENT purpose's starting limits from
-- -- TodoAttachmentPolicySeed; the tenants that already exist when the purpose ships get them here, with
-- -- the same numbers (none on a new database). RLS is lifted for this changeset's own transaction only,
-- -- so the owner writes every tenant's row (docs/tenancy-runbook.md, section 4).
-- --changeset dev:0010-todo-attachment-policy-existing-tenants
-- ALTER TABLE upload_policies NO FORCE ROW LEVEL SECURITY;
-- INSERT INTO upload_policies (id, tenant_id, purpose, min_size_bytes, max_size_bytes, min_files, max_files,
--                              content_types, created_at, updated_at, version)
-- SELECT uuidv7(), t.id, 'TODO_ATTACHMENT', 1, 10485760, 0, 5, ARRAY ['application/pdf', 'image/jpeg', 'image/png'],
--        now(), now(), 0
--   FROM tenants t
--  WHERE NOT EXISTS (SELECT 1 FROM upload_policies p WHERE p.tenant_id = t.id AND p.purpose = 'TODO_ATTACHMENT');
-- ALTER TABLE upload_policies FORCE ROW LEVEL SECURITY;
-- --rollback SELECT 1;
-- tenancy-row:else
--changeset dev:0010-todo-attachment-policy
-- The TODO_ATTACHMENT purpose's starting limits; an admin changes them through PUT /upload-policies/{purpose}.
INSERT INTO upload_policies (id, purpose, min_size_bytes, max_size_bytes, min_files, max_files, content_types,
                             created_at, updated_at, version)
VALUES ('0190a9c2-0000-7000-8000-000000000001', 'TODO_ATTACHMENT', 1, 10485760, 0, 5,
        ARRAY ['application/pdf', 'image/jpeg', 'image/png'], now(), now(), 0);
--rollback DELETE FROM upload_policies WHERE purpose = 'TODO_ATTACHMENT';
-- tenancy-row:end

-- tenancy-row:begin
-- --changeset dev:0010-todos-id-tenant-id
-- -- Target of the attachments' composite foreign key: a file is attached only to a todo of its own tenant.
-- ALTER TABLE todos ADD CONSTRAINT uq_todos_id_tenant_id UNIQUE (id, tenant_id);
-- --rollback ALTER TABLE todos DROP CONSTRAINT uq_todos_id_tenant_id;
--
-- tenancy-row:end
--changeset dev:0010-todo-attachments
-- The files on a todo, one row per file (TodoAttachment): members of the todo's aggregate, written
-- only through the todo, whose version they bump. A file is on one todo only.
CREATE TABLE todo_attachments (
    id            uuid        NOT NULL,
    -- tenancy-row:begin
    -- tenant_id     uuid        NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    -- tenancy-row:end
    todo_id       uuid        NOT NULL,
    media_file_id uuid        NOT NULL,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    created_by    uuid,
    updated_by    uuid,
    version       bigint      NOT NULL,
    CONSTRAINT pk_todo_attachments PRIMARY KEY (id),
    -- Its index also serves the media file's foreign key.
    -- tenancy-row:begin
    -- CONSTRAINT uq_todo_attachments_media_file_id UNIQUE (media_file_id, tenant_id),
    -- CONSTRAINT fk_todo_attachments_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    -- -- No cascade: deleting a todo deletes its rows through the aggregate (TodoService), which releases the files.
    -- CONSTRAINT fk_todo_attachments_todo_id FOREIGN KEY (todo_id, tenant_id) REFERENCES todos (id, tenant_id),
    -- CONSTRAINT fk_todo_attachments_media_file_id FOREIGN KEY (media_file_id, tenant_id) REFERENCES media_files (id, tenant_id)
    -- tenancy-row:else
    CONSTRAINT uq_todo_attachments_media_file_id UNIQUE (media_file_id),
    -- No cascade: deleting a todo deletes its rows through the aggregate (TodoService), which releases the files.
    CONSTRAINT fk_todo_attachments_todo_id FOREIGN KEY (todo_id) REFERENCES todos (id),
    CONSTRAINT fk_todo_attachments_media_file_id FOREIGN KEY (media_file_id) REFERENCES media_files (id)
    -- tenancy-row:end
);
-- The todo's foreign key, and a todo's files (TodoAttachmentRepository).
-- tenancy-row:begin
-- CREATE INDEX idx_todo_attachments_todo_id_media_file_id ON todo_attachments (todo_id, tenant_id, media_file_id);
-- ALTER TABLE todo_attachments ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE todo_attachments FORCE ROW LEVEL SECURITY;
-- CREATE POLICY tenant_isolation ON todo_attachments
--     USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
--     WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- tenancy-row:else
CREATE INDEX idx_todo_attachments_todo_id_media_file_id ON todo_attachments (todo_id, media_file_id);
-- tenancy-row:end
--rollback DROP TABLE todo_attachments;
