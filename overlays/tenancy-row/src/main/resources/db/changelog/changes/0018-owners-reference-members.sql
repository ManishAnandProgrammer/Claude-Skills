--liquibase formatted sql

-- Row mode: the example's owners reference the tenant's own members (house-review TEN-16), no longer the
-- platform's users: (owner_id, tenant_id) → members (user_id, tenant_id), so an owner is a member of
-- the row's own tenant and the tenant's rows form a closed graph. The applied changesets 0002 to 0011
-- are never edited, so their keys to users are replaced here, under the same names (a module's
-- ConstraintErrorCodes keep working). bootstrap-copy.sh copies this file with the example piece.

--changeset dev:0018-owners-reference-members
-- People who own records but left their tenant before 0017 have no membership to copy: their row, marked
-- left. RLS lifted for this changeset's own transaction only (docs/tenancy-runbook.md, section 4).
ALTER TABLE members NO FORCE ROW LEVEL SECURITY;
ALTER TABLE todo_lists NO FORCE ROW LEVEL SECURITY;
ALTER TABLE todos NO FORCE ROW LEVEL SECURITY;
ALTER TABLE tags NO FORCE ROW LEVEL SECURITY;
ALTER TABLE time_entries NO FORCE ROW LEVEL SECURITY;
ALTER TABLE plans NO FORCE ROW LEVEL SECURITY;
INSERT INTO members (id, tenant_id, user_id, left_at, created_at, updated_at, version)
SELECT uuidv7(), owners.tenant_id, owners.owner_id, now(), now(), now(), 0
  FROM (SELECT tenant_id, owner_id FROM todo_lists
        UNION SELECT tenant_id, owner_id FROM todos
        UNION SELECT tenant_id, owner_id FROM tags
        UNION SELECT tenant_id, owner_id FROM time_entries
        UNION SELECT tenant_id, owner_id FROM plans) owners
 WHERE NOT EXISTS (SELECT 1 FROM members m WHERE m.tenant_id = owners.tenant_id AND m.user_id = owners.owner_id);
ALTER TABLE members FORCE ROW LEVEL SECURITY;
ALTER TABLE todo_lists FORCE ROW LEVEL SECURITY;
ALTER TABLE todos FORCE ROW LEVEL SECURITY;
ALTER TABLE tags FORCE ROW LEVEL SECURITY;
ALTER TABLE time_entries FORCE ROW LEVEL SECURITY;
ALTER TABLE plans FORCE ROW LEVEL SECURITY;
-- Each table's indexes already lead with (owner_id, tenant_id), so the new keys need no index of their own.
ALTER TABLE todo_lists DROP CONSTRAINT fk_todo_lists_owner_id;
ALTER TABLE todo_lists ADD CONSTRAINT fk_todo_lists_owner_id FOREIGN KEY (owner_id, tenant_id) REFERENCES members (user_id, tenant_id);
ALTER TABLE todos DROP CONSTRAINT fk_todos_owner_id;
ALTER TABLE todos ADD CONSTRAINT fk_todos_owner_id FOREIGN KEY (owner_id, tenant_id) REFERENCES members (user_id, tenant_id);
ALTER TABLE tags DROP CONSTRAINT fk_tags_owner_id;
ALTER TABLE tags ADD CONSTRAINT fk_tags_owner_id FOREIGN KEY (owner_id, tenant_id) REFERENCES members (user_id, tenant_id);
ALTER TABLE time_entries DROP CONSTRAINT fk_time_entries_owner_id;
ALTER TABLE time_entries ADD CONSTRAINT fk_time_entries_owner_id FOREIGN KEY (owner_id, tenant_id) REFERENCES members (user_id, tenant_id);
ALTER TABLE plans DROP CONSTRAINT fk_plans_owner_id;
ALTER TABLE plans ADD CONSTRAINT fk_plans_owner_id FOREIGN KEY (owner_id, tenant_id) REFERENCES members (user_id, tenant_id);
--rollback ALTER TABLE plans DROP CONSTRAINT fk_plans_owner_id;
--rollback ALTER TABLE plans ADD CONSTRAINT fk_plans_owner_id FOREIGN KEY (owner_id) REFERENCES users (id);
--rollback ALTER TABLE time_entries DROP CONSTRAINT fk_time_entries_owner_id;
--rollback ALTER TABLE time_entries ADD CONSTRAINT fk_time_entries_owner_id FOREIGN KEY (owner_id) REFERENCES users (id);
--rollback ALTER TABLE tags DROP CONSTRAINT fk_tags_owner_id;
--rollback ALTER TABLE tags ADD CONSTRAINT fk_tags_owner_id FOREIGN KEY (owner_id) REFERENCES users (id);
--rollback ALTER TABLE todos DROP CONSTRAINT fk_todos_owner_id;
--rollback ALTER TABLE todos ADD CONSTRAINT fk_todos_owner_id FOREIGN KEY (owner_id) REFERENCES users (id);
--rollback ALTER TABLE todo_lists DROP CONSTRAINT fk_todo_lists_owner_id;
--rollback ALTER TABLE todo_lists ADD CONSTRAINT fk_todo_lists_owner_id FOREIGN KEY (owner_id) REFERENCES users (id);
--rollback ALTER TABLE members NO FORCE ROW LEVEL SECURITY;
--rollback DELETE FROM members m WHERE m.left_at IS NOT NULL AND NOT EXISTS (SELECT 1 FROM tenant_memberships t WHERE t.tenant_id = m.tenant_id AND t.user_id = m.user_id);
--rollback ALTER TABLE members FORCE ROW LEVEL SECURITY;
