--liquibase formatted sql

-- NEWEST orders by the creation time, then the id, never by the id alone. The primary key leads with the
-- id, so an id order gives every scoped list a competitor: with UUIDv7 ids in heap order the planner
-- costs a backward walk of pk_<table> as cheap and takes it whenever the scope looks like a big share of
-- the table, and for a scope whose rows are old a page then reads every newer row of every other owner
-- (or tenant) first. No index leads with created_at, so the scope's own index
-- has no competitor. Each NEWEST index becomes (scope..., created_at, id), still leading with the same
-- columns, so the foreign keys it served stay served (house-review DATA-10; TodoIndexIntegrationTest,
-- TagIndexIntegrationTest, PlanIndexIntegrationTest). An index build blocks writes to its table while it
-- runs: on a big table, deploy in a quiet hour (docs/runbook.md).
-- bootstrap-copy.sh copies this file with the example piece.

--changeset dev:0029-newest-by-created-at
-- tenancy-row:begin
-- CREATE INDEX idx_todos_owner_id_created_at_id ON todos (owner_id, tenant_id, created_at, id);
-- CREATE INDEX idx_todos_todo_list_id_owner_id_created_at_id ON todos (todo_list_id, owner_id, tenant_id, created_at, id);
-- CREATE INDEX idx_todos_owner_id_status_created_at_id ON todos (owner_id, tenant_id, status, created_at, id);
-- CREATE INDEX idx_todo_lists_owner_id_created_at_id ON todo_lists (owner_id, tenant_id, created_at, id);
-- CREATE INDEX idx_tags_owner_id_created_at_id ON tags (owner_id, tenant_id, created_at, id);
-- CREATE INDEX idx_tags_owner_id_active_created_at_id ON tags (owner_id, tenant_id, active, created_at, id);
-- CREATE INDEX idx_plans_owner_id_created_at_id ON plans (owner_id, tenant_id, created_at, id);
-- CREATE INDEX idx_plans_todo_id_owner_id_created_at_id ON plans (todo_id, owner_id, tenant_id, created_at, id);
-- tenancy-row:else
CREATE INDEX idx_todos_owner_id_created_at_id ON todos (owner_id, created_at, id);
CREATE INDEX idx_todos_todo_list_id_owner_id_created_at_id ON todos (todo_list_id, owner_id, created_at, id);
CREATE INDEX idx_todos_owner_id_status_created_at_id ON todos (owner_id, status, created_at, id);
CREATE INDEX idx_todo_lists_owner_id_created_at_id ON todo_lists (owner_id, created_at, id);
CREATE INDEX idx_tags_owner_id_created_at_id ON tags (owner_id, created_at, id);
CREATE INDEX idx_tags_owner_id_active_created_at_id ON tags (owner_id, active, created_at, id);
CREATE INDEX idx_plans_owner_id_created_at_id ON plans (owner_id, created_at, id);
CREATE INDEX idx_plans_todo_id_owner_id_created_at_id ON plans (todo_id, owner_id, created_at, id);
-- tenancy-row:end
DROP INDEX idx_todos_owner_id_id;
DROP INDEX idx_todos_todo_list_id_owner_id_id;
DROP INDEX idx_todos_owner_id_status_id;
DROP INDEX idx_todo_lists_owner_id_id;
DROP INDEX idx_tags_owner_id_id;
DROP INDEX idx_tags_owner_id_active_id;
DROP INDEX idx_plans_owner_id_id;
DROP INDEX idx_plans_todo_id_owner_id_id;
-- tenancy-row:begin
-- --rollback CREATE INDEX idx_todos_owner_id_id ON todos (owner_id, tenant_id, id);
-- --rollback CREATE INDEX idx_todos_todo_list_id_owner_id_id ON todos (todo_list_id, owner_id, tenant_id, id);
-- --rollback CREATE INDEX idx_todos_owner_id_status_id ON todos (owner_id, tenant_id, status, id);
-- --rollback CREATE INDEX idx_todo_lists_owner_id_id ON todo_lists (owner_id, tenant_id, id);
-- --rollback CREATE INDEX idx_tags_owner_id_id ON tags (owner_id, tenant_id, id);
-- --rollback CREATE INDEX idx_tags_owner_id_active_id ON tags (owner_id, tenant_id, active, id);
-- --rollback CREATE INDEX idx_plans_owner_id_id ON plans (owner_id, tenant_id, id);
-- --rollback CREATE INDEX idx_plans_todo_id_owner_id_id ON plans (todo_id, owner_id, tenant_id, id);
-- tenancy-row:else
--rollback CREATE INDEX idx_todos_owner_id_id ON todos (owner_id, id);
--rollback CREATE INDEX idx_todos_todo_list_id_owner_id_id ON todos (todo_list_id, owner_id, id);
--rollback CREATE INDEX idx_todos_owner_id_status_id ON todos (owner_id, status, id);
--rollback CREATE INDEX idx_todo_lists_owner_id_id ON todo_lists (owner_id, id);
--rollback CREATE INDEX idx_tags_owner_id_id ON tags (owner_id, id);
--rollback CREATE INDEX idx_tags_owner_id_active_id ON tags (owner_id, active, id);
--rollback CREATE INDEX idx_plans_owner_id_id ON plans (owner_id, id);
--rollback CREATE INDEX idx_plans_todo_id_owner_id_id ON plans (todo_id, owner_id, id);
-- tenancy-row:end
--rollback DROP INDEX idx_todos_owner_id_created_at_id, idx_todos_todo_list_id_owner_id_created_at_id,
--rollback     idx_todos_owner_id_status_created_at_id, idx_todo_lists_owner_id_created_at_id, idx_tags_owner_id_created_at_id,
--rollback     idx_tags_owner_id_active_created_at_id, idx_plans_owner_id_created_at_id, idx_plans_todo_id_owner_id_created_at_id;
