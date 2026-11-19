--liquibase formatted sql

-- The example's tables in the change history (the change-history piece, 0035): who changed a todo list or a todo,
-- and how. Neither has a secret or a hash, so the triggers copy every column.
-- tenancy-schema:begin
-- -- Schema per tenant: a tenant changeset, so each tenant schema's tables get their own triggers; the function is the
-- -- platform's, named with its schema (a tenant's transaction runs with its own schema alone on the search_path).
-- tenancy-schema:end
-- bootstrap-copy.sh copies this file with the example and change-history pieces.

--changeset dev:0036-todos-change-history
-- tenancy-schema:begin
-- CREATE TRIGGER audit_todo_lists AFTER INSERT OR UPDATE OR DELETE ON todo_lists
--     FOR EACH ROW EXECUTE FUNCTION platform.audit_row_change();
-- CREATE TRIGGER audit_todos AFTER INSERT OR UPDATE OR DELETE ON todos
--     FOR EACH ROW EXECUTE FUNCTION platform.audit_row_change();
-- tenancy-schema:else
CREATE TRIGGER audit_todo_lists AFTER INSERT OR UPDATE OR DELETE ON todo_lists
    FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER audit_todos AFTER INSERT OR UPDATE OR DELETE ON todos
    FOR EACH ROW EXECUTE FUNCTION audit_row_change();
-- tenancy-schema:end
--rollback DROP TRIGGER audit_todos ON todos;
--rollback DROP TRIGGER audit_todo_lists ON todo_lists;
