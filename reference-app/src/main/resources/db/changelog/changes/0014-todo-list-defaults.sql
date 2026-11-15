--liquibase formatted sql
-- The fixed key TODO_LIST_DEFAULTS (MetadataKeys): seeded by the changeset that needs it (META-05), so
-- every database has it from the start and a read never meets METADATA_NOT_FOUND; an admin changes it
-- through PUT /metadata/TODO_LIST_DEFAULTS. The value is TodoListSettings.defaults(), written out.
-- bootstrap-copy.sh copies this file only with the example and metadata pieces. Numbered after the
-- tenancy overlays' 0012 and 0013, which a bootstrap copy may also hold.
-- tenancy-row:begin
-- -- Row mode: tenants provisioned from now on get the key from TodoListDefaultsSeed; the tenants that
-- -- already exist when it ships get it here, with the same value (none on a new database). RLS is lifted
-- -- for this changeset's own transaction only, so the owner writes every tenant's row
-- -- (docs/tenancy-runbook.md, section 4).
-- --changeset dev:0014-todo-list-defaults-existing-tenants
-- ALTER TABLE configuration_metadata NO FORCE ROW LEVEL SECURITY;
-- INSERT INTO configuration_metadata (id, tenant_id, key, value, created_at, updated_at, version)
-- SELECT uuidv7(), t.id, 'TODO_LIST_DEFAULTS',
--        '{"reminderMinutesBefore": 30, "archiveDoneTodos": false, "dailyDigestAt": "09:00:00"}'::jsonb,
--        now(), now(), 0
--   FROM tenants t
--  WHERE NOT EXISTS (SELECT 1 FROM configuration_metadata m WHERE m.tenant_id = t.id AND m.key = 'TODO_LIST_DEFAULTS');
-- ALTER TABLE configuration_metadata FORCE ROW LEVEL SECURITY;
-- --rollback SELECT 1;
-- tenancy-row:else
--changeset dev:0014-todo-list-defaults
INSERT INTO configuration_metadata (id, key, value, created_at, updated_at, version)
VALUES ('0190a9c2-0000-7000-8000-000000000002', 'TODO_LIST_DEFAULTS',
        '{"reminderMinutesBefore": 30, "archiveDoneTodos": false, "dailyDigestAt": "09:00:00"}'::jsonb, now(), now(), 0);
--rollback DELETE FROM configuration_metadata WHERE key = 'TODO_LIST_DEFAULTS';
-- tenancy-row:end
