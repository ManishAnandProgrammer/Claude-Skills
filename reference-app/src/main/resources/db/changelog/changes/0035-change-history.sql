--liquibase formatted sql

-- The change history (the change-history piece, house-review DATA-08): one generic trigger function writes every
-- insert, update and delete of the audited tables to the append-only audit_log: the table and row, the operation,
-- the row before and after as jsonb, when, and who. It sees what JPA auditing can't: a JPQL bulk update, native SQL,
-- a fix made by hand in psql.
--
-- * Who: the caller's id, set once per write transaction with set_config('app.user_id', ..., true)
--   (transaction-local, never at session level on a pooled connection: common/persistence/ChangeHistoryActor).
--   NULL outside a signed-in request (a signup, a job, psql), as updated_by is.
-- * What: an INSERT keeps the new row, a DELETE the old one, an UPDATE only the columns that changed, old and new
--   side by side (copying both whole rows on every update would make this the largest table by far). An UPDATE that
--   changes nothing writes nothing.
-- * Never copied: the columns a table's trigger names as its arguments, its secrets and hashes
--   (users.password_hash). A new secret or hash column on an audited table joins its trigger's arguments in
--   the same changeset that adds it; a new table that matters gets a trigger of its own.
-- * Append-only: an UPDATE of audit_log is refused by a trigger. The application's only statement on it is the
--   purge's delete past the retention (ChangeHistoryPurgeJob, app.jobs.change-history-purge); nothing reads it
--   (compliance only) and no entity maps it.
-- * No foreign keys: a row outlives what it describes, and actor_id is like updated_by. The id is the database's
--   (no entity inserts here).
-- tenancy:begin
-- -- * One table for the whole database, the platform's: a tenant's changes carry their table's schema (schema per
-- --   tenant) or their row's tenant_id (row mode), so the history is read, and purged, in one place.
-- tenancy:end
-- bootstrap-copy.sh copies this file with the change-history piece; the example's tables get their triggers in 0036.

--changeset dev:0035-audit-log
CREATE TABLE audit_log (
    id           uuid        NOT NULL DEFAULT uuidv7(),
    table_schema text        NOT NULL,
    table_name   text        NOT NULL,
    row_id       uuid        NOT NULL,
    -- tenancy-row:begin
    -- -- The changed row's tenant (none for a platform table's row).
    -- tenant_id    uuid,
    -- tenancy-row:end
    operation    text        NOT NULL,
    old_row      jsonb,
    new_row      jsonb,
    actor_id     uuid,
    changed_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT ck_audit_log_operation CHECK (operation IN ('INSERT', 'UPDATE', 'DELETE'))
);
-- "Who changed this row, and when?": one row's history, oldest first.
CREATE INDEX idx_audit_log_table_name_row_id_changed_at ON audit_log (table_name, row_id, changed_at);
-- The purge's batches (ChangeHistoryRetention).
CREATE INDEX idx_audit_log_changed_at ON audit_log (changed_at);
--rollback DROP TABLE audit_log;

--changeset dev:0035-audit-row-change splitStatements:false
-- Every audited table's trigger function: its arguments are the columns never copied.
CREATE FUNCTION audit_row_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    left_out   text[] := coalesce(TG_ARGV, '{}');
    old_values jsonb;
    new_values jsonb;
    changed    jsonb;
BEGIN
    IF TG_OP = 'INSERT' THEN
        new_values := to_jsonb(NEW) - left_out;
        changed := to_jsonb(NEW);
    ELSIF TG_OP = 'DELETE' THEN
        old_values := to_jsonb(OLD) - left_out;
        changed := to_jsonb(OLD);
    ELSE
        SELECT jsonb_object_agg(before.key, before.value), jsonb_object_agg(before.key, after.value)
          INTO old_values, new_values
          FROM jsonb_each(to_jsonb(OLD) - left_out) AS before
          JOIN jsonb_each(to_jsonb(NEW) - left_out) AS after ON after.key = before.key
         WHERE after.value IS DISTINCT FROM before.value;
        IF old_values IS NULL THEN
            RETURN NULL;
        END IF;
        changed := to_jsonb(NEW);
    END IF;
    -- tenancy-schema:begin
    -- -- Qualified: a tenant's transaction runs with its own schema alone on the search_path.
    -- INSERT INTO platform.audit_log (table_schema, table_name, row_id, operation, old_row, new_row, actor_id)
    -- tenancy-schema:end
    -- tenancy-row:begin
    -- INSERT INTO audit_log (table_schema, table_name, row_id, tenant_id, operation, old_row, new_row, actor_id)
    -- tenancy-row:end
    -- tenancy:begin
    -- tenancy:else
    INSERT INTO audit_log (table_schema, table_name, row_id, operation, old_row, new_row, actor_id)
    -- tenancy:end
    VALUES (TG_TABLE_SCHEMA, TG_TABLE_NAME, (changed ->> 'id')::uuid,
            -- tenancy-row:begin
            -- (changed ->> 'tenant_id')::uuid,
            -- tenancy-row:end
            TG_OP, old_values, new_values, nullif(current_setting('app.user_id', true), '')::uuid);
    RETURN NULL;
END;
$$;
--rollback DROP FUNCTION audit_row_change();

--changeset dev:0035-audit-log-append-only splitStatements:false
CREATE FUNCTION audit_log_refuse_update() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: rows are inserted, and deleted only past their retention';
END;
$$;
CREATE TRIGGER audit_log_append_only BEFORE UPDATE ON audit_log FOR EACH ROW EXECUTE FUNCTION audit_log_refuse_update();
-- The trigger is the guard, not a revoked privilege: the purge's FOR UPDATE SKIP LOCKED needs UPDATE on the table.
--rollback DROP TRIGGER audit_log_append_only ON audit_log;
--rollback DROP FUNCTION audit_log_refuse_update();
-- sign-in:begin

--changeset dev:0035-audit-users
CREATE TRIGGER audit_users AFTER INSERT OR UPDATE OR DELETE ON users
    FOR EACH ROW EXECUTE FUNCTION audit_row_change('password_hash');
-- tenancy:begin
-- -- Who belongs to which tenant, with which role.
-- CREATE TRIGGER audit_tenant_memberships AFTER INSERT OR UPDATE OR DELETE ON tenant_memberships
--     FOR EACH ROW EXECUTE FUNCTION audit_row_change();
-- --rollback DROP TRIGGER audit_tenant_memberships ON tenant_memberships;
-- tenancy:end
--rollback DROP TRIGGER audit_users ON users;
-- sign-in:end
