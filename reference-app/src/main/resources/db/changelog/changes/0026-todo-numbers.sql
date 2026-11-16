--liquibase formatted sql

-- A todo's display number (#1, #2, ...) from the gapless counter TODO_NUMBER (the metadata piece's
-- Counters; house-review TEN-18). The todos already there are numbered in the order they were created
-- (UUIDv7 ids are time-ordered) and the counter starts after the highest. A table rewrite: on a big table,
-- run it in a quiet hour (docs/runbook.md). bootstrap-copy.sh copies this file with the example and
-- metadata pieces.
-- tenancy-row:begin
-- -- Row mode: one counter per tenant (a fixed name: the counter is the tenant's own row). Every tenant gets
-- -- it here, whatever its status (a PENDING one too, so its approval finds it: TodoNumberSeed creates it only
-- -- when missing), numbering its own todos from 1; RLS is lifted for these changesets' own transactions
-- -- (docs/tenancy-runbook.md, section 4).
-- --changeset dev:0026-todo-numbers
-- ALTER TABLE todos ADD COLUMN number bigint;
-- ALTER TABLE todos NO FORCE ROW LEVEL SECURITY;
-- UPDATE todos SET number = numbered.number
--   FROM (SELECT id, row_number() OVER (PARTITION BY tenant_id ORDER BY id) AS number FROM todos) numbered
--  WHERE todos.id = numbered.id;
-- ALTER TABLE todos FORCE ROW LEVEL SECURITY;
-- ALTER TABLE todos ALTER COLUMN number SET NOT NULL;
-- ALTER TABLE todos ADD CONSTRAINT uq_todos_number UNIQUE (tenant_id, number);
-- --rollback ALTER TABLE todos DROP COLUMN number;
--
-- --changeset dev:0026-todo-number-counters-existing-tenants
-- ALTER TABLE todos NO FORCE ROW LEVEL SECURITY;
-- ALTER TABLE counters NO FORCE ROW LEVEL SECURITY;
-- INSERT INTO counters (id, tenant_id, key, last_value, created_at, updated_at, version)
-- SELECT uuidv7(), t.id, 'TODO_NUMBER', coalesce((SELECT max(todo.number) FROM todos todo WHERE todo.tenant_id = t.id), 0),
--        now(), now(), 0
--   FROM tenants t
--  WHERE NOT EXISTS (SELECT 1 FROM counters c WHERE c.tenant_id = t.id AND c.key = 'TODO_NUMBER');
-- ALTER TABLE counters FORCE ROW LEVEL SECURITY;
-- ALTER TABLE todos FORCE ROW LEVEL SECURITY;
-- --rollback ALTER TABLE counters NO FORCE ROW LEVEL SECURITY;
-- --rollback DELETE FROM counters WHERE key = 'TODO_NUMBER';
-- --rollback ALTER TABLE counters FORCE ROW LEVEL SECURITY;
-- tenancy-row:else
-- With schema per tenant this runs in every tenant schema and the template: each tenant's own todos and counter.
--changeset dev:0026-todo-numbers
ALTER TABLE todos ADD COLUMN number bigint;
UPDATE todos SET number = numbered.number
  FROM (SELECT id, row_number() OVER (ORDER BY id) AS number FROM todos) numbered
 WHERE todos.id = numbered.id;
ALTER TABLE todos ALTER COLUMN number SET NOT NULL;
ALTER TABLE todos ADD CONSTRAINT uq_todos_number UNIQUE (number);
INSERT INTO counters (id, key, last_value, created_at, updated_at, version)
SELECT uuidv7(), 'TODO_NUMBER', coalesce(max(number), 0), now(), now(), 0 FROM todos;
--rollback DELETE FROM counters WHERE key = 'TODO_NUMBER';
--rollback ALTER TABLE todos DROP COLUMN number;
-- tenancy-row:end
