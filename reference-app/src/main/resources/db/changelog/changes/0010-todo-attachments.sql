--liquibase formatted sql

-- Todo attachments: the example module's use of the media piece. bootstrap-copy.sh copies this file
-- only when both pieces are chosen.

--changeset dev:0010-todo-attachment-policy
-- The TODO_ATTACHMENT purpose's starting limits; an admin changes them through PUT /upload-policies/{purpose}.
INSERT INTO upload_policies (id, purpose, min_size_bytes, max_size_bytes, min_files, max_files, content_types,
                             created_at, updated_at, version)
VALUES ('0190a9c2-0000-7000-8000-000000000001', 'TODO_ATTACHMENT', 1, 10485760, 0, 5,
        ARRAY ['application/pdf', 'image/jpeg', 'image/png'], now(), now(), 0);
--rollback DELETE FROM upload_policies WHERE purpose = 'TODO_ATTACHMENT';

--changeset dev:0010-todo-attachments
-- The files on a todo, one row per file (TodoAttachment): members of the todo's aggregate, written
-- only through the todo, whose version they bump. A file is on one todo only.
CREATE TABLE todo_attachments (
    id            uuid        NOT NULL,
    todo_id       uuid        NOT NULL,
    media_file_id uuid        NOT NULL,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    created_by    uuid,
    updated_by    uuid,
    version       bigint      NOT NULL,
    CONSTRAINT pk_todo_attachments PRIMARY KEY (id),
    -- Its index also serves the media file's foreign key.
    CONSTRAINT uq_todo_attachments_media_file_id UNIQUE (media_file_id),
    -- No cascade: deleting a todo deletes its rows through the aggregate (TodoService), which releases the files.
    CONSTRAINT fk_todo_attachments_todo_id FOREIGN KEY (todo_id) REFERENCES todos (id),
    CONSTRAINT fk_todo_attachments_media_file_id FOREIGN KEY (media_file_id) REFERENCES media_files (id)
);
-- The todo's foreign key, and a todo's files (TodoAttachmentRepository).
CREATE INDEX idx_todo_attachments_todo_id_media_file_id ON todo_attachments (todo_id, media_file_id);
--rollback DROP TABLE todo_attachments;
