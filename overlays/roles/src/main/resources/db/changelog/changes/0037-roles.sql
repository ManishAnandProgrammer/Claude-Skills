--liquibase formatted sql

-- The roles piece (house-review AUTHZ-11, AUTHZ-12): roles an admin edits, in tables (Spring Security's own shape:
-- groups, group_members, group_authorities), and a person's roles and personal grants.
-- * roles: a code (stable: a changeset names it), a name, and built_in, the Role constant a built-in role was seeded
--   from (a copy: an admin's edit changes this row, never the enum). role_permissions: its permissions. Roles only
--   allow.
-- * user_roles: a person holds a role, for good or until valid_until. user_permissions: one personal grant per person
--   and permission, ALLOW or DENY (a deny always wins), for good or until valid_until.
-- * Permission codes are text the application checks: a code that names no Permission constant grants nothing.
-- * Every reference is a real foreign key with the default ON DELETE (no action): a role is deleted only while
--   nobody holds it, and no assignment ever goes with it.
-- * All four are in the change history (change-history piece, 0035).
-- tenancy:begin
-- * The memberships' role column goes, its roles copied into the tables (each mode below): a role with no built-in copy
--   stops the migration (a member would lose their access). The previous release reads and writes that column: stop
--   its instances before this runs (no rolling deploy across it).
-- tenancy:else
-- -- * The users.role column goes: each account's one role becomes its assignment of the built-in role of that name,
-- --   and the tables are the one source from then on. A role with no built-in copy stops the migration (an account
-- --   would lose its access). The previous release reads and writes that column: stop its instances before this
-- --   runs (no rolling deploy across it).
-- tenancy:end
-- tenancy-row:begin
-- * Row mode: the four are tenant tables (a tenant's roles, its members' assignments and grants), each with its
--   tenant_id, forced row-level security and keys that keep a row inside its tenant (TEN-33); a person is a member
--   of the tenant, (user_id, tenant_id) -> members (TEN-16). Every tenant that exists gets its built-in roles here,
--   whatever its status (TEN-34); a new one gets them at its activation (TenantMemberManager, from the Role enum).
--   The memberships' role column goes, and the accounts' (with tenancy it never decided anything).
-- tenancy-row:end
-- tenancy-schema:begin
-- * Schema per tenant: a tenant changeset (tenant.yaml), so the four live in every tenant schema, a person a member
--   of it, user_id -> members (TEN-16), and every new schema gets the built-in roles from the seed below. Each
--   schema copies its own tenant's memberships' roles and empties them in the platform's table as it goes; the
--   platform's changesets make that column nullable first (0038) and drop it once no schema is left to copy
--   (0039, expand then contract: tenant schemas migrate one after another, TenantMigrations).
-- tenancy-schema:end
-- bootstrap-copy.sh copies this file with the roles piece.

--changeset dev:0037-roles
CREATE TABLE roles (
    id         uuid        NOT NULL,
    -- tenancy-row:begin
    -- No default: every insert names its tenant (TEN-30).
    tenant_id  uuid        NOT NULL,
    -- tenancy-row:end
    code       text        NOT NULL,
    name       text        NOT NULL,
    built_in   text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid,
    updated_by uuid,
    version    bigint      NOT NULL,
    CONSTRAINT pk_roles PRIMARY KEY (id),
    -- tenancy-row:begin
    CONSTRAINT fk_roles_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    -- The target of the members' rows' composite keys, tenant_id last (TEN-33).
    CONSTRAINT uq_roles_id_tenant_id UNIQUE (id, tenant_id),
    -- No owner or parent column: the tenant leads (TEN-33).
    CONSTRAINT uq_roles_code UNIQUE (tenant_id, code),
    -- One copy of each built-in role per tenant.
    CONSTRAINT uq_roles_built_in UNIQUE (tenant_id, built_in),
    -- tenancy-row:else
    -- CONSTRAINT uq_roles_code UNIQUE (code),
    -- -- One copy of each built-in role.
    -- CONSTRAINT uq_roles_built_in UNIQUE (built_in),
    -- tenancy-row:end
    CONSTRAINT ck_roles_code CHECK (code ~ '^[A-Z][A-Z0-9_]*$')
);
-- The list's orders, the id last (DATA-10): NAME here; CODE is uq_roles_code's.
-- tenancy-row:begin
CREATE INDEX idx_roles_name_id ON roles (tenant_id, name, id);
-- tenancy-row:else
-- CREATE INDEX idx_roles_name_id ON roles (name, id);
-- tenancy-row:end
-- tenancy-row:begin
-- Forced under the one tenant policy in the changeset that creates it (TEN-30); row-filtered replication of UPDATE and
-- DELETE needs the filter column in the replica identity (TEN-38).
ALTER TABLE roles REPLICA IDENTITY USING INDEX uq_roles_id_tenant_id;
ALTER TABLE roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE roles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON roles
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- tenancy-row:end
--rollback DROP TABLE roles;

--changeset dev:0037-role-permissions
-- A role's permissions, members of its aggregate (AccessRole): written only through the role, whose version they bump.
CREATE TABLE role_permissions (
    id         uuid        NOT NULL,
    -- tenancy-row:begin
    tenant_id  uuid        NOT NULL,
    -- tenancy-row:end
    role_id    uuid        NOT NULL,
    permission text        NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    created_by uuid,
    updated_by uuid,
    version    bigint      NOT NULL,
    CONSTRAINT pk_role_permissions PRIMARY KEY (id),
    -- tenancy-row:begin
    CONSTRAINT fk_role_permissions_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    -- Once per role. Leading with role_id and the tenant, it serves the role's key and the per-request read's join.
    CONSTRAINT uq_role_permissions_role_id_permission UNIQUE (role_id, tenant_id, permission),
    CONSTRAINT fk_role_permissions_role_id FOREIGN KEY (role_id, tenant_id) REFERENCES roles (id, tenant_id)
    -- tenancy-row:else
    -- -- Once per role. Leading with role_id, it serves the role's key and the per-request read's join.
    -- CONSTRAINT uq_role_permissions_role_id_permission UNIQUE (role_id, permission),
    -- CONSTRAINT fk_role_permissions_role_id FOREIGN KEY (role_id) REFERENCES roles (id)
    -- tenancy-row:end
);
-- Who holds a permission (safety rule 6's count): the roles that give it.
-- tenancy-row:begin
CREATE INDEX idx_role_permissions_permission_role_id ON role_permissions (permission, tenant_id, role_id);
-- tenancy-row:else
-- CREATE INDEX idx_role_permissions_permission_role_id ON role_permissions (permission, role_id);
-- tenancy-row:end
-- tenancy-row:begin
-- Forced under the one tenant policy in the changeset that creates it (TEN-30); row-filtered replication of UPDATE and
-- DELETE needs the filter column in the replica identity (TEN-38).
ALTER TABLE role_permissions REPLICA IDENTITY USING INDEX uq_role_permissions_role_id_permission;
ALTER TABLE role_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON role_permissions
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- tenancy-row:end
--rollback DROP TABLE role_permissions;

--changeset dev:0037-user-roles
CREATE TABLE user_roles (
    id          uuid        NOT NULL,
    -- tenancy-row:begin
    tenant_id   uuid        NOT NULL,
    -- tenancy-row:end
    user_id     uuid        NOT NULL,
    role_id     uuid        NOT NULL,
    valid_until timestamptz,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_by  uuid,
    version     bigint      NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (id),
    -- tenancy-row:begin
    CONSTRAINT fk_user_roles_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    -- A role once per person. Leading with user_id and the tenant, it serves the person's key and the per-request read.
    CONSTRAINT uq_user_roles_user_id_role_id UNIQUE (user_id, tenant_id, role_id),
    -- The tenant's own member, never the platform's users (TEN-16).
    CONSTRAINT fk_user_roles_user_id FOREIGN KEY (user_id, tenant_id) REFERENCES members (user_id, tenant_id),
    CONSTRAINT fk_user_roles_role_id FOREIGN KEY (role_id, tenant_id) REFERENCES roles (id, tenant_id)
    -- tenancy-row:else
    -- -- A role once per person. Leading with user_id, it serves the person's key and the per-request read.
    -- CONSTRAINT uq_user_roles_user_id_role_id UNIQUE (user_id, role_id),
    -- tenancy-schema:begin
    -- -- The schema's own member, never the platform's users (TEN-16).
    -- CONSTRAINT fk_user_roles_user_id FOREIGN KEY (user_id) REFERENCES members (user_id),
    -- tenancy-schema:else
    -- CONSTRAINT fk_user_roles_user_id FOREIGN KEY (user_id) REFERENCES users (id),
    -- tenancy-schema:end
    -- CONSTRAINT fk_user_roles_role_id FOREIGN KEY (role_id) REFERENCES roles (id)
    -- tenancy-row:end
);
-- The role's key (a delete's check), and who holds a role.
-- tenancy-row:begin
CREATE INDEX idx_user_roles_role_id ON user_roles (role_id, tenant_id);
-- tenancy-row:else
-- CREATE INDEX idx_user_roles_role_id ON user_roles (role_id);
-- tenancy-row:end
-- tenancy-row:begin
-- Forced under the one tenant policy in the changeset that creates it (TEN-30); row-filtered replication of UPDATE and
-- DELETE needs the filter column in the replica identity (TEN-38).
ALTER TABLE user_roles REPLICA IDENTITY USING INDEX uq_user_roles_user_id_role_id;
-- A member lives in one tenant: without the dependency the planner multiplies the person's share by the tenant's
-- (TEN-33).
CREATE STATISTICS st_user_roles_user_id_tenant_id (dependencies) ON user_id, tenant_id FROM user_roles;
ALTER TABLE user_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_roles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_roles
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- tenancy-row:end
--rollback DROP TABLE user_roles;

--changeset dev:0037-user-permissions
CREATE TABLE user_permissions (
    id          uuid        NOT NULL,
    -- tenancy-row:begin
    tenant_id   uuid        NOT NULL,
    -- tenancy-row:end
    user_id     uuid        NOT NULL,
    permission  text        NOT NULL,
    effect      text        NOT NULL,
    valid_until timestamptz,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_by  uuid,
    version     bigint      NOT NULL,
    CONSTRAINT pk_user_permissions PRIMARY KEY (id),
    -- tenancy-row:begin
    CONSTRAINT fk_user_permissions_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    -- One grant per person and permission. Leading with user_id and the tenant, it serves the person's key and the read.
    CONSTRAINT uq_user_permissions_user_id_permission UNIQUE (user_id, tenant_id, permission),
    CONSTRAINT fk_user_permissions_user_id FOREIGN KEY (user_id, tenant_id) REFERENCES members (user_id, tenant_id),
    -- tenancy-row:else
    -- -- One grant per person and permission. Leading with user_id, it serves the person's key and the read.
    -- CONSTRAINT uq_user_permissions_user_id_permission UNIQUE (user_id, permission),
    -- tenancy-schema:begin
    -- CONSTRAINT fk_user_permissions_user_id FOREIGN KEY (user_id) REFERENCES members (user_id),
    -- tenancy-schema:else
    -- CONSTRAINT fk_user_permissions_user_id FOREIGN KEY (user_id) REFERENCES users (id),
    -- tenancy-schema:end
    -- tenancy-row:end
    CONSTRAINT ck_user_permissions_effect CHECK (effect IN ('ALLOW', 'DENY'))
);
-- Who holds a permission (safety rule 6's count): the personal ALLOWs of it.
-- tenancy-row:begin
CREATE INDEX idx_user_permissions_permission_user_id ON user_permissions (permission, tenant_id, user_id);
-- tenancy-row:else
-- CREATE INDEX idx_user_permissions_permission_user_id ON user_permissions (permission, user_id);
-- tenancy-row:end
-- tenancy-row:begin
-- Forced under the one tenant policy in the changeset that creates it (TEN-30); row-filtered replication of UPDATE and
-- DELETE needs the filter column in the replica identity (TEN-38).
ALTER TABLE user_permissions REPLICA IDENTITY USING INDEX uq_user_permissions_user_id_permission;
-- A member lives in one tenant: without the dependency the planner multiplies the person's share by the tenant's
-- (TEN-33).
CREATE STATISTICS st_user_permissions_user_id_tenant_id (dependencies) ON user_id, tenant_id FROM user_permissions;
ALTER TABLE user_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_permissions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_permissions
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- tenancy-row:end
--rollback DROP TABLE user_permissions;

--changeset dev:0037-roles-change-history
-- Before the seeds, so the history starts with them.
-- tenancy-schema:begin
-- The platform's function, named with its schema: a tenant's transaction has its own schema alone on the search_path.
CREATE TRIGGER audit_roles AFTER INSERT OR UPDATE OR DELETE ON roles
    FOR EACH ROW EXECUTE FUNCTION platform.audit_row_change();
CREATE TRIGGER audit_role_permissions AFTER INSERT OR UPDATE OR DELETE ON role_permissions
    FOR EACH ROW EXECUTE FUNCTION platform.audit_row_change();
CREATE TRIGGER audit_user_roles AFTER INSERT OR UPDATE OR DELETE ON user_roles
    FOR EACH ROW EXECUTE FUNCTION platform.audit_row_change();
CREATE TRIGGER audit_user_permissions AFTER INSERT OR UPDATE OR DELETE ON user_permissions
    FOR EACH ROW EXECUTE FUNCTION platform.audit_row_change();
-- tenancy-schema:else
-- CREATE TRIGGER audit_roles AFTER INSERT OR UPDATE OR DELETE ON roles
--     FOR EACH ROW EXECUTE FUNCTION audit_row_change();
-- CREATE TRIGGER audit_role_permissions AFTER INSERT OR UPDATE OR DELETE ON role_permissions
--     FOR EACH ROW EXECUTE FUNCTION audit_row_change();
-- CREATE TRIGGER audit_user_roles AFTER INSERT OR UPDATE OR DELETE ON user_roles
--     FOR EACH ROW EXECUTE FUNCTION audit_row_change();
-- CREATE TRIGGER audit_user_permissions AFTER INSERT OR UPDATE OR DELETE ON user_permissions
--     FOR EACH ROW EXECUTE FUNCTION audit_row_change();
-- tenancy-schema:end
--rollback DROP TRIGGER audit_user_permissions ON user_permissions;
--rollback DROP TRIGGER audit_user_roles ON user_roles;
--rollback DROP TRIGGER audit_role_permissions ON role_permissions;
--rollback DROP TRIGGER audit_roles ON roles;

--changeset dev:0037-built-in-roles
-- The built-in roles as the Role enum listed them when this was written: literal rows, never read from the enum
-- (a migration must give the same rows on every run). A new permission comes with a changeset of its own granting
-- it to the built-in roles that should hold it (BuiltInRolesIntegrationTest compares them on a fresh database).
-- tenancy-schema:begin
-- Every tenant schema runs it, the template and each new one too: a new tenant's built-in roles come from here.
-- tenancy-schema:end
-- tenancy-row:begin
-- One copy per tenant that exists; row-level security is lifted for this changeset's transaction only, so the owner
-- writes every tenant's rows (docs/tenancy-runbook.md, section 4).
ALTER TABLE roles NO FORCE ROW LEVEL SECURITY;
ALTER TABLE role_permissions NO FORCE ROW LEVEL SECURITY;
INSERT INTO roles (id, tenant_id, code, name, built_in, created_at, updated_at, version)
SELECT uuidv7(), t.id, b.code, b.name, b.code, now(), now(), 0
  FROM tenants t
 CROSS JOIN (VALUES ('USER', 'User'), ('ADMIN', 'Admin')) AS b (code, name);
-- tenancy-row:else
-- INSERT INTO roles (id, code, name, built_in, created_at, updated_at, version)
-- VALUES (uuidv7(), 'USER', 'User', 'USER', now(), now(), 0),
--        (uuidv7(), 'ADMIN', 'Admin', 'ADMIN', now(), now(), 0);
-- tenancy-row:end
-- example:begin
-- tenancy-row:begin
INSERT INTO role_permissions (id, tenant_id, role_id, permission, created_at, updated_at, version)
SELECT uuidv7(), r.tenant_id, r.id, p.permission, now(), now(), 0
-- tenancy-row:else
-- INSERT INTO role_permissions (id, role_id, permission, created_at, updated_at, version)
-- SELECT uuidv7(), r.id, p.permission, now(), now(), 0
-- tenancy-row:end
  FROM roles r
 CROSS JOIN unnest(ARRAY['TODO_LIST_READ_OWN', 'TODO_LIST_CREATE_OWN', 'TODO_LIST_EDIT_OWN', 'TODO_LIST_DELETE_OWN',
                         'TODO_READ_OWN', 'TODO_CREATE_OWN', 'TODO_EDIT_OWN', 'TODO_DELETE_OWN',
                         'TAG_READ_OWN', 'TAG_CREATE_OWN', 'TAG_EDIT_OWN', 'TAG_DELETE_OWN',
                         'PLAN_READ_OWN', 'PLAN_CREATE_OWN', 'PLAN_EDIT_OWN', 'PLAN_DELETE_OWN']) AS p (permission)
 WHERE r.built_in IN ('USER', 'ADMIN');
-- example:end
-- tenancy-row:begin
INSERT INTO role_permissions (id, tenant_id, role_id, permission, created_at, updated_at, version)
SELECT uuidv7(), r.tenant_id, r.id, p.permission, now(), now(), 0
-- tenancy-row:else
-- INSERT INTO role_permissions (id, role_id, permission, created_at, updated_at, version)
-- SELECT uuidv7(), r.id, p.permission, now(), now(), 0
-- tenancy-row:end
  FROM roles r
 CROSS JOIN unnest(ARRAY[
-- tenancy:begin
                         'TENANT_MEMBERS_MANAGE',
-- tenancy:else
--                       'ACCOUNT_READ', 'ACCOUNT_STATUS_CHANGE',
-- tenancy:end
-- metadata:begin
                         'METADATA_MANAGE',
-- metadata:end
-- media:begin
                         'UPLOAD_POLICY_MANAGE',
-- media:end
                         'ROLES_MANAGE', 'ACCESS_GRANT']) AS p (permission)
 WHERE r.built_in = 'ADMIN';
-- tenancy-row:begin
ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;
ALTER TABLE roles FORCE ROW LEVEL SECURITY;
-- The rollback runs as the owner with no tenant, whom the forced policies hide every row from: lifted the same way.
--rollback ALTER TABLE user_roles NO FORCE ROW LEVEL SECURITY;
--rollback ALTER TABLE role_permissions NO FORCE ROW LEVEL SECURITY;
--rollback ALTER TABLE roles NO FORCE ROW LEVEL SECURITY;
-- tenancy-row:end
--rollback DELETE FROM user_roles;
--rollback DELETE FROM role_permissions;
--rollback DELETE FROM roles;
-- tenancy-row:begin
--rollback ALTER TABLE roles FORCE ROW LEVEL SECURITY;
--rollback ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;
--rollback ALTER TABLE user_roles FORCE ROW LEVEL SECURITY;
-- tenancy-row:end

-- tenancy-schema:begin
--changeset dev:0037-role-column-to-assignments splitStatements:false
--preconditions onFail:MARK_RAN
-- Only while the platform's column is there: once 0039 dropped it, a new schema has nothing to copy.
--precondition-sql-check expectedResult:1 SELECT count(*) FROM information_schema.columns WHERE table_schema = 'platform' AND table_name = 'tenant_memberships' AND column_name = 'role'
-- This schema's tenant's memberships, found through the platform's migration record of the schema (as 0017 finds
-- them): each one's role becomes its member's assignment of the built-in role of that name, then the platform's
-- copy is emptied, in this schema's transaction. None in the template; a schema being provisioned copies nothing
-- (its owner's ADMIN role comes from TenantMemberManager) but empties its tenant's too. A role with no built-in copy
-- stops it: emptied, the member would lose their access for good.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM tenant_memberships m JOIN tenant_migrations t ON t.tenant_id = m.tenant_id
                WHERE t.schema_name = current_schema() AND m.role IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM roles r WHERE r.built_in = m.role)) THEN
        RAISE EXCEPTION 'A membership holds a role with no built-in copy in this changeset: seed it first';
    END IF;
END $$;
INSERT INTO user_roles (id, user_id, role_id, created_at, updated_at, version)
SELECT uuidv7(), m.user_id, r.id, now(), now(), 0
  FROM tenant_memberships m
  JOIN tenant_migrations t ON t.tenant_id = m.tenant_id
  JOIN members mb ON mb.user_id = m.user_id
  JOIN roles r ON r.built_in = m.role
 WHERE t.schema_name = current_schema()
   AND t.status <> 'PROVISIONING';
UPDATE tenant_memberships m
   SET role = NULL
  FROM tenant_migrations t
 WHERE t.tenant_id = m.tenant_id
   AND t.schema_name = current_schema();
--rollback UPDATE tenant_memberships m SET role = CASE WHEN EXISTS (SELECT 1 FROM user_roles a JOIN roles r ON r.id = a.role_id WHERE a.user_id = m.user_id AND r.built_in = 'ADMIN') THEN 'ADMIN' ELSE 'USER' END FROM tenant_migrations t WHERE t.tenant_id = m.tenant_id AND t.schema_name = current_schema();
--rollback DELETE FROM user_roles;
-- tenancy-schema:end
-- tenancy-row:begin
--changeset dev:0037-role-column-to-assignments splitStatements:false
-- Each membership's one role becomes its member's assignment of the tenant's built-in role of that name (a
-- membership of a PENDING tenant registered after 0017 has no member row yet: its owner's ADMIN role comes at its
-- activation), then the role columns go: the memberships' and, with tenancy meaningless, the accounts'.
-- Every tenant's rows are read and written: row-level security is lifted for this changeset's transaction only.
ALTER TABLE members NO FORCE ROW LEVEL SECURITY;
ALTER TABLE roles NO FORCE ROW LEVEL SECURITY;
ALTER TABLE user_roles NO FORCE ROW LEVEL SECURITY;
-- A role with no built-in copy stops it: the column goes, and the member would lose their access.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM tenant_memberships m
                 JOIN members mb ON mb.user_id = m.user_id AND mb.tenant_id = m.tenant_id
                WHERE NOT EXISTS (SELECT 1 FROM roles r WHERE r.tenant_id = m.tenant_id AND r.built_in = m.role)) THEN
        RAISE EXCEPTION 'A membership holds a role with no built-in copy in this changeset: seed it first';
    END IF;
END $$;
INSERT INTO user_roles (id, tenant_id, user_id, role_id, created_at, updated_at, version)
SELECT uuidv7(), m.tenant_id, m.user_id, r.id, now(), now(), 0
  FROM tenant_memberships m
  JOIN members mb ON mb.user_id = m.user_id AND mb.tenant_id = m.tenant_id
  JOIN roles r ON r.tenant_id = m.tenant_id AND r.built_in = m.role;
ALTER TABLE user_roles FORCE ROW LEVEL SECURITY;
ALTER TABLE roles FORCE ROW LEVEL SECURITY;
ALTER TABLE members FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_memberships DROP COLUMN role;
ALTER TABLE users DROP COLUMN role;
--rollback ALTER TABLE users ADD COLUMN role text NOT NULL DEFAULT 'USER';
--rollback ALTER TABLE users ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'));
--rollback ALTER TABLE users ALTER COLUMN role DROP DEFAULT;
--rollback ALTER TABLE tenant_memberships ADD COLUMN role text;
--rollback ALTER TABLE roles NO FORCE ROW LEVEL SECURITY;
--rollback ALTER TABLE user_roles NO FORCE ROW LEVEL SECURITY;
--rollback UPDATE tenant_memberships m SET role = CASE WHEN EXISTS (SELECT 1 FROM user_roles a JOIN roles r ON r.id = a.role_id AND r.tenant_id = a.tenant_id WHERE a.user_id = m.user_id AND a.tenant_id = m.tenant_id AND r.built_in = 'ADMIN') THEN 'ADMIN' ELSE 'USER' END;
--rollback DELETE FROM user_roles;
--rollback ALTER TABLE user_roles FORCE ROW LEVEL SECURITY;
--rollback ALTER TABLE roles FORCE ROW LEVEL SECURITY;
--rollback ALTER TABLE tenant_memberships ALTER COLUMN role SET NOT NULL;
--rollback ALTER TABLE tenant_memberships ADD CONSTRAINT ck_tenant_memberships_role CHECK (role IN ('USER', 'ADMIN'));
--rollback CREATE INDEX idx_tenant_memberships_tenant_id_role_created_at_id ON tenant_memberships (tenant_id, role, created_at, id);
-- tenancy-row:end
-- tenancy:begin
-- tenancy:else
-- --changeset dev:0037-role-column-to-assignments splitStatements:false
-- -- Each account's one role becomes its assignment of the built-in role of that name, then the column goes.
-- DO $$
-- BEGIN
--     IF EXISTS (SELECT 1 FROM users u WHERE NOT EXISTS (SELECT 1 FROM roles r WHERE r.built_in = u.role)) THEN
--         RAISE EXCEPTION 'An account holds a role with no built-in copy in this changeset: seed it first';
--     END IF;
-- END $$;
-- INSERT INTO user_roles (id, user_id, role_id, created_at, updated_at, version)
-- SELECT uuidv7(), u.id, r.id, now(), now(), 0
--   FROM users u
--   JOIN roles r ON r.built_in = u.role;
-- ALTER TABLE users DROP COLUMN role;
-- --rollback ALTER TABLE users ADD COLUMN role text;
-- --rollback UPDATE users u SET role = CASE WHEN EXISTS (SELECT 1 FROM user_roles a JOIN roles r ON r.id = a.role_id WHERE a.user_id = u.id AND r.built_in = 'ADMIN') THEN 'ADMIN' ELSE 'USER' END;
-- --rollback ALTER TABLE users ALTER COLUMN role SET NOT NULL;
-- --rollback ALTER TABLE users ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'));
-- tenancy:end
