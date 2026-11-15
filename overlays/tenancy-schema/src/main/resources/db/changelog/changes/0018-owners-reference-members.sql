--liquibase formatted sql

-- Schema per tenant: the example's owners reference the schema's own members (house-review TEN-16), no
-- longer platform.users, so the schema's tables form a closed graph that `pg_dump -n` carries whole. The
-- applied changesets 0002 to 0011 are never edited, so their keys to users are replaced here, under the
-- same names (a module's ConstraintErrorCodes keep working). A tenant changeset (tenant.yaml);
-- bootstrap-copy.sh copies this file with the example piece.

--changeset dev:0018-owners-reference-members
-- People who own records but left the tenant before 0017 have no membership to copy: their row, marked left.
INSERT INTO members (id, user_id, left_at, created_at, updated_at, version)
SELECT uuidv7(), owners.owner_id, now(), now(), now(), 0
  FROM (SELECT owner_id FROM todo_lists
        UNION SELECT owner_id FROM todos
        UNION SELECT owner_id FROM tags
        UNION SELECT owner_id FROM time_entries
        UNION SELECT owner_id FROM plans) owners
 WHERE NOT EXISTS (SELECT 1 FROM members m WHERE m.user_id = owners.owner_id);
-- Each table's indexes already lead with owner_id, so the new keys need no index of their own.
ALTER TABLE todo_lists DROP CONSTRAINT fk_todo_lists_owner_id;
ALTER TABLE todo_lists ADD CONSTRAINT fk_todo_lists_owner_id FOREIGN KEY (owner_id) REFERENCES members (user_id);
ALTER TABLE todos DROP CONSTRAINT fk_todos_owner_id;
ALTER TABLE todos ADD CONSTRAINT fk_todos_owner_id FOREIGN KEY (owner_id) REFERENCES members (user_id);
ALTER TABLE tags DROP CONSTRAINT fk_tags_owner_id;
ALTER TABLE tags ADD CONSTRAINT fk_tags_owner_id FOREIGN KEY (owner_id) REFERENCES members (user_id);
ALTER TABLE time_entries DROP CONSTRAINT fk_time_entries_owner_id;
ALTER TABLE time_entries ADD CONSTRAINT fk_time_entries_owner_id FOREIGN KEY (owner_id) REFERENCES members (user_id);
ALTER TABLE plans DROP CONSTRAINT fk_plans_owner_id;
ALTER TABLE plans ADD CONSTRAINT fk_plans_owner_id FOREIGN KEY (owner_id) REFERENCES members (user_id);
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
