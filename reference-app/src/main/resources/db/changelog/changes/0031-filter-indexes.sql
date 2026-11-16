--liquibase formatted sql

-- Every offered equality filter gets its own index per offered order: the scope first, then the filter,
-- then the order's keys, the id last (house-review DATA-10). How many rows a filter value holds is a fact
-- about the data, which the schema can't know, so no filter is left to an order's index. The todos'
-- priority filter and the time entries' billable filter get theirs here.
-- bootstrap-copy.sh copies this file with the example piece.

--changeset dev:0031-filter-indexes
-- tenancy-row:begin
-- CREATE INDEX idx_todos_owner_id_priority_due_sort_id ON todos (owner_id, tenant_id, priority, due_sort, id);
-- CREATE INDEX idx_todos_owner_id_priority_created_at_id ON todos (owner_id, tenant_id, priority, created_at, id);
-- CREATE INDEX idx_time_entries_todo_id_owner_id_billable_spent_on_id ON time_entries (todo_id, owner_id, tenant_id, billable, spent_on, id);
-- CREATE INDEX idx_time_entries_todo_id_owner_id_billable_minutes_id ON time_entries (todo_id, owner_id, tenant_id, billable, minutes, id);
-- tenancy-row:else
CREATE INDEX idx_todos_owner_id_priority_due_sort_id ON todos (owner_id, priority, due_sort, id);
CREATE INDEX idx_todos_owner_id_priority_created_at_id ON todos (owner_id, priority, created_at, id);
CREATE INDEX idx_time_entries_todo_id_owner_id_billable_spent_on_id ON time_entries (todo_id, owner_id, billable, spent_on, id);
CREATE INDEX idx_time_entries_todo_id_owner_id_billable_minutes_id ON time_entries (todo_id, owner_id, billable, minutes, id);
-- tenancy-row:end
--rollback DROP INDEX idx_todos_owner_id_priority_due_sort_id, idx_todos_owner_id_priority_created_at_id,
--rollback     idx_time_entries_todo_id_owner_id_billable_spent_on_id, idx_time_entries_todo_id_owner_id_billable_minutes_id;
