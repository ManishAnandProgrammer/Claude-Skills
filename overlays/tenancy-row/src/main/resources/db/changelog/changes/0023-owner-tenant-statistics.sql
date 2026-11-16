--liquibase formatted sql

-- Row mode, with the example: an owner (a member) belongs to one tenant, so "owner_id = ? and
-- tenant_id = ?" selects the owner's rows, not their share of the table times the tenant's. Without these
-- statistics the planner multiplies the two and, once other tenants fill the table, estimates a few rows
-- for an owner with thousands: it then reads the exclusion constraint's GiST index (the same leading
-- columns) plus a sort instead of the plans' order index (measured in round 3), and joins a
-- todo's tags by id with a sort instead of walking the order's index (TodoIndexIntegrationTest in a full
-- run). Every tenant table whose rows an owner scopes gets them (house-review TEN-33).

--changeset dev:0023-owner-tenant-statistics
CREATE STATISTICS st_todo_lists_owner_id_tenant_id (dependencies) ON owner_id, tenant_id FROM todo_lists;
CREATE STATISTICS st_todos_owner_id_tenant_id (dependencies) ON owner_id, tenant_id FROM todos;
CREATE STATISTICS st_tags_owner_id_tenant_id (dependencies) ON owner_id, tenant_id FROM tags;
CREATE STATISTICS st_time_entries_owner_id_tenant_id (dependencies) ON owner_id, tenant_id FROM time_entries;
CREATE STATISTICS st_todo_tags_owner_id_tenant_id (dependencies) ON owner_id, tenant_id FROM todo_tags;
CREATE STATISTICS st_plans_owner_id_tenant_id (dependencies) ON owner_id, tenant_id FROM plans;
--rollback DROP STATISTICS st_todo_lists_owner_id_tenant_id, st_todos_owner_id_tenant_id, st_tags_owner_id_tenant_id, st_time_entries_owner_id_tenant_id, st_todo_tags_owner_id_tenant_id, st_plans_owner_id_tenant_id;
