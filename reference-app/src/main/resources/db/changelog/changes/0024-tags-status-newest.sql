--liquibase formatted sql

-- The tag list's status filter under NEWEST. 0004 left it to the newest-first index ("a user's tags are a
-- bounded set"), but nothing bounds them, and under NEWEST the inactive tags are scattered over the whole
-- walk. One index per order under a scoping filter, as for every other list (house-review DATA-10; the
-- index matrix of crud-gen tests.md row 28, TagIndexIntegrationTest).
-- bootstrap-copy.sh copies this file with the example piece.

--changeset dev:0024-tags-status-newest
-- tenancy-row:begin
-- CREATE INDEX idx_tags_owner_id_active_id ON tags (owner_id, tenant_id, active, id);
-- tenancy-row:else
CREATE INDEX idx_tags_owner_id_active_id ON tags (owner_id, active, id);
-- tenancy-row:end
--rollback DROP INDEX idx_tags_owner_id_active_id;
