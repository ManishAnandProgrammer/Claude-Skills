--liquibase formatted sql

-- Who asked for a todo: a person of another module, so an id with a real foreign key, checked through that
-- module's facade at the create (Users; with tenancy Members). Optional, so existing todos stay as they are.
-- With tenancy the key is to the tenant's own members (a member who left keeps their row, so the key
-- never blocks a removal and the todo keeps its requester), never to the platform's users (TEN-16).
-- bootstrap-copy.sh copies this file with the example piece.

--changeset dev:0025-todos-requester
ALTER TABLE todos ADD COLUMN requester_id uuid;
-- tenancy-row:begin
-- ALTER TABLE todos ADD CONSTRAINT fk_todos_requester_id FOREIGN KEY (requester_id, tenant_id) REFERENCES members (user_id, tenant_id);
-- CREATE INDEX idx_todos_requester_id ON todos (requester_id, tenant_id);
-- tenancy-row:else
-- tenancy-schema:begin
-- ALTER TABLE todos ADD CONSTRAINT fk_todos_requester_id FOREIGN KEY (requester_id) REFERENCES members (user_id);
-- tenancy-schema:else
ALTER TABLE todos ADD CONSTRAINT fk_todos_requester_id FOREIGN KEY (requester_id) REFERENCES users (id);
-- tenancy-schema:end
CREATE INDEX idx_todos_requester_id ON todos (requester_id);
-- tenancy-row:end
--rollback DROP INDEX idx_todos_requester_id;
--rollback ALTER TABLE todos DROP COLUMN requester_id;
