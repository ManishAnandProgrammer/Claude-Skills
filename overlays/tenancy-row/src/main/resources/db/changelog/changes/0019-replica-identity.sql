--liquibase formatted sql

-- Row mode: every tenant table's replica identity is a unique index that includes tenant_id (house-review
-- TEN-38). One tenant moves to a database of its own through logical replication filtered by its id
-- (CREATE PUBLICATION ... FOR TABLE x WHERE (tenant_id = '...')), and PostgreSQL refuses every UPDATE and
-- DELETE on a table whose row filter names a column outside the replica identity: the default identity,
-- the primary key (id), lacks tenant_id. Each table uses a unique key it already has; time_entries and
-- plans had none, so they get (id, tenant_id). A new tenant table sets its identity in the changeset that
-- creates it. bootstrap-copy.sh copies this file with row mode and a piece that brings tenant tables.

--changeset dev:0019-replica-identity
-- example:begin
ALTER TABLE todo_lists REPLICA IDENTITY USING INDEX uq_todo_lists_id_owner_id;
ALTER TABLE todos REPLICA IDENTITY USING INDEX uq_todos_id_owner_id;
ALTER TABLE tags REPLICA IDENTITY USING INDEX uq_tags_id_owner_id;
CREATE UNIQUE INDEX uq_time_entries_id_tenant_id ON time_entries (id, tenant_id);
ALTER TABLE time_entries REPLICA IDENTITY USING INDEX uq_time_entries_id_tenant_id;
ALTER TABLE todo_tags REPLICA IDENTITY USING INDEX uq_todo_tags_todo_id_tag_id;
CREATE UNIQUE INDEX uq_plans_id_tenant_id ON plans (id, tenant_id);
ALTER TABLE plans REPLICA IDENTITY USING INDEX uq_plans_id_tenant_id;
-- example:end
-- metadata:begin
ALTER TABLE configuration_metadata REPLICA IDENTITY USING INDEX uq_configuration_metadata_key;
ALTER TABLE counters REPLICA IDENTITY USING INDEX uq_counters_key;
-- metadata:end
-- media:begin
ALTER TABLE upload_policies REPLICA IDENTITY USING INDEX uq_upload_policies_purpose;
ALTER TABLE media_files REPLICA IDENTITY USING INDEX uq_media_files_id_tenant_id;
-- example:begin
ALTER TABLE todo_attachments REPLICA IDENTITY USING INDEX uq_todo_attachments_media_file_id;
-- example:end
-- media:end
--rollback SELECT 1;
