--liquibase formatted sql

-- The filter indexes the example's developer asked for (a filter's own index is the developer's call, house-review
-- DATA-10): the todos' priority and the time entries' billable filter, each per offered order: the scope first,
-- then the filter, then the order's keys, the id last.
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
