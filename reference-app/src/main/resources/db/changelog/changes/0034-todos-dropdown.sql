--liquibase formatted sql

-- The todo dropdown (TodoRepositoryImpl.findDropdownItems): a dependent dropdown, one list's todos ordered by title,
-- the id last as the tie-breaker of its keyset (titles repeat), within the caller's reach (the owner). The list and
-- the owner lead, as the statement's two equality conditions, so a batch reads its rows in order and stops
-- (house-review DATA-10). query is a contains search within those rows: the same index bounds it.
-- bootstrap-copy.sh copies this file with the example piece.

--changeset dev:0034-todos-dropdown-index
-- tenancy-row:begin
-- CREATE INDEX idx_todos_todo_list_id_owner_id_title_id ON todos (todo_list_id, owner_id, tenant_id, title, id);
-- tenancy-row:else
CREATE INDEX idx_todos_todo_list_id_owner_id_title_id ON todos (todo_list_id, owner_id, title, id);
-- tenancy-row:end
--rollback DROP INDEX idx_todos_todo_list_id_owner_id_title_id;
