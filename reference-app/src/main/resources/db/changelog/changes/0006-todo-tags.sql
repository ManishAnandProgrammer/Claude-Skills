--liquibase formatted sql

--changeset dev:0006-tags-id-owner-id
-- Target of the todo tags' composite foreign key: a todo carries only tags of its own owner.
-- tenancy-row:begin
-- ALTER TABLE tags ADD CONSTRAINT uq_tags_id_owner_id UNIQUE (id, owner_id, tenant_id);
-- tenancy-row:else
ALTER TABLE tags ADD CONSTRAINT uq_tags_id_owner_id UNIQUE (id, owner_id);
-- tenancy-row:end
--rollback ALTER TABLE tags DROP CONSTRAINT uq_tags_id_owner_id;

--changeset dev:0006-todo-tags
-- The tags on a todo, one row per pair (TodoTag): members of the todo's aggregate, written only
-- through the todo, whose version they bump. owner_id is both sides' owner: the two composite
-- foreign keys refuse a todo and a tag of different owners. It needs no foreign key of its own:
-- either composite one already implies it.
CREATE TABLE todo_tags (
    id         uuid        NOT NULL,
    -- tenancy-row:begin
    -- -- Both sides' tenant, like owner_id: the composite keys refuse a todo and a tag of different tenants.
    -- tenant_id  uuid        NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    -- tenancy-row:end
    todo_id    uuid        NOT NULL,
    tag_id     uuid        NOT NULL,
    owner_id   uuid        NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid,
    updated_by uuid,
    version    bigint      NOT NULL,
    CONSTRAINT pk_todo_tags PRIMARY KEY (id),
    -- A tag is on a todo once. Leading with todo_id, it also serves the todo's foreign key, the
    -- tags of a todo or of a batch of todos, and the tagId filter's exists probe.
    -- tenancy-row:begin
    -- CONSTRAINT uq_todo_tags_todo_id_tag_id UNIQUE (todo_id, tag_id, tenant_id),
    -- CONSTRAINT fk_todo_tags_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    -- tenancy-row:else
    CONSTRAINT uq_todo_tags_todo_id_tag_id UNIQUE (todo_id, tag_id),
    -- tenancy-row:end
    -- No cascade: deleting a todo deletes its rows through the aggregate (TodoService).
    -- tenancy-row:begin
    -- CONSTRAINT fk_todo_tags_todo_id_owner_id FOREIGN KEY (todo_id, owner_id, tenant_id) REFERENCES todos (id, owner_id, tenant_id),
    -- tenancy-row:else
    CONSTRAINT fk_todo_tags_todo_id_owner_id FOREIGN KEY (todo_id, owner_id) REFERENCES todos (id, owner_id),
    -- tenancy-row:end
    -- The owner's rule: a tag's delete is never blocked. It disappears from every todo carrying it,
    -- and those todos keep their version: only the label is lost, never a todo or its content.
    -- A link written while its tag is being deleted is refused by this key: 404 TAG_GONE.
    -- tenancy-row:begin
    -- CONSTRAINT fk_todo_tags_tag_id_owner_id FOREIGN KEY (tag_id, owner_id, tenant_id) REFERENCES tags (id, owner_id, tenant_id) ON DELETE CASCADE
    -- tenancy-row:else
    CONSTRAINT fk_todo_tags_tag_id_owner_id FOREIGN KEY (tag_id, owner_id) REFERENCES tags (id, owner_id) ON DELETE CASCADE
    -- tenancy-row:end
);
-- The tag's foreign key: the cascade of a tag's delete finds its rows through it.
-- tenancy-row:begin
-- CREATE INDEX idx_todo_tags_tag_id_owner_id_todo_id ON todo_tags (tag_id, owner_id, tenant_id, todo_id);
-- ALTER TABLE todo_tags ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE todo_tags FORCE ROW LEVEL SECURITY;
-- CREATE POLICY tenant_isolation ON todo_tags
--     USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
--     WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- tenancy-row:else
CREATE INDEX idx_todo_tags_tag_id_owner_id_todo_id ON todo_tags (tag_id, owner_id, todo_id);
-- tenancy-row:end
--rollback DROP TABLE todo_tags;
