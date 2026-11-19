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
-- * The users.role column goes: each account's one role becomes its assignment of the built-in role of that name,
--   and the tables are the one source from then on.
-- tenancy-row:begin
-- * Row mode: the four are tenant tables (a tenant's roles, its members' assignments and grants), each with its
--   tenant_id, forced row-level security and keys that keep a row inside its tenant (TEN-33); a person is a member
--   of the tenant, (user_id, tenant_id) -> members. Every tenant that exists gets its built-in roles here; a new one
--   gets them at its activation (BuiltInRolesSeed). The membership's role column goes.
-- tenancy-row:end
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
    CONSTRAINT uq_roles_code UNIQUE (code, tenant_id),
    -- One copy of each built-in role per tenant.
    CONSTRAINT uq_roles_built_in UNIQUE (built_in, tenant_id),
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
    -- Once per role. Leading with role_id, it serves the role's key and the per-request read's join.
    CONSTRAINT uq_role_permissions_role_id_permission UNIQUE (role_id, permission, tenant_id),
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
    -- A role once per person. Leading with user_id, it serves the person's key and the per-request read.
    CONSTRAINT uq_user_roles_user_id_role_id UNIQUE (user_id, role_id, tenant_id),
    -- The tenant's own member, never the platform's users (TEN-16).
    CONSTRAINT fk_user_roles_user_id FOREIGN KEY (user_id, tenant_id) REFERENCES members (user_id, tenant_id),
    CONSTRAINT fk_user_roles_role_id FOREIGN KEY (role_id, tenant_id) REFERENCES roles (id, tenant_id)
    -- tenancy-row:else
    -- -- A role once per person. Leading with user_id, it serves the person's key and the per-request read.
    -- CONSTRAINT uq_user_roles_user_id_role_id UNIQUE (user_id, role_id),
    -- CONSTRAINT fk_user_roles_user_id FOREIGN KEY (user_id) REFERENCES users (id),
    -- CONSTRAINT fk_user_roles_role_id FOREIGN KEY (role_id) REFERENCES roles (id)
    -- tenancy-row:end
);
-- The role's key (a delete's check), and who holds a role.
-- tenancy-row:begin
CREATE INDEX idx_user_roles_role_id ON user_roles (role_id, tenant_id);
-- tenancy-row:else
-- CREATE INDEX idx_user_roles_role_id ON user_roles (role_id);
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
    -- One grant per person and permission. Leading with user_id, it serves the person's key and the read.
    CONSTRAINT uq_user_permissions_user_id_permission UNIQUE (user_id, permission, tenant_id),
    CONSTRAINT fk_user_permissions_user_id FOREIGN KEY (user_id, tenant_id) REFERENCES members (user_id, tenant_id),
    -- tenancy-row:else
    -- -- One grant per person and permission. Leading with user_id, it serves the person's key and the read.
    -- CONSTRAINT uq_user_permissions_user_id_permission UNIQUE (user_id, permission),
    -- CONSTRAINT fk_user_permissions_user_id FOREIGN KEY (user_id) REFERENCES users (id),
    -- tenancy-row:end
    CONSTRAINT ck_user_permissions_effect CHECK (effect IN ('ALLOW', 'DENY'))
);
-- Who holds a permission (safety rule 6's count): the personal ALLOWs of it.
-- tenancy-row:begin
CREATE INDEX idx_user_permissions_permission_user_id ON user_permissions (permission, tenant_id, user_id);
-- tenancy-row:else
-- CREATE INDEX idx_user_permissions_permission_user_id ON user_permissions (permission, user_id);
-- tenancy-row:end
--rollback DROP TABLE user_permissions;
-- tenancy-row:begin

--changeset dev:0037-roles-row-security
-- Row-filtered replication of UPDATE and DELETE needs the filter column in the replica identity (TEN-38).
ALTER TABLE roles REPLICA IDENTITY USING INDEX uq_roles_id_tenant_id;
ALTER TABLE role_permissions REPLICA IDENTITY USING INDEX uq_role_permissions_role_id_permission;
ALTER TABLE user_roles REPLICA IDENTITY USING INDEX uq_user_roles_user_id_role_id;
ALTER TABLE user_permissions REPLICA IDENTITY USING INDEX uq_user_permissions_user_id_permission;
ALTER TABLE roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE roles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON roles
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
ALTER TABLE role_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON role_permissions
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
ALTER TABLE user_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_roles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_roles
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
ALTER TABLE user_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_permissions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_permissions
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
--rollback DROP POLICY tenant_isolation ON user_permissions;
--rollback DROP POLICY tenant_isolation ON user_roles;
--rollback DROP POLICY tenant_isolation ON role_permissions;
--rollback DROP POLICY tenant_isolation ON roles;
-- tenancy-row:end

--changeset dev:0037-roles-change-history
-- Before the seeds, so the history starts with them.
CREATE TRIGGER audit_roles AFTER INSERT OR UPDATE OR DELETE ON roles
    FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER audit_role_permissions AFTER INSERT OR UPDATE OR DELETE ON role_permissions
    FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER audit_user_roles AFTER INSERT OR UPDATE OR DELETE ON user_roles
    FOR EACH ROW EXECUTE FUNCTION audit_row_change();
CREATE TRIGGER audit_user_permissions AFTER INSERT OR UPDATE OR DELETE ON user_permissions
    FOR EACH ROW EXECUTE FUNCTION audit_row_change();
--rollback DROP TRIGGER audit_user_permissions ON user_permissions;
--rollback DROP TRIGGER audit_user_roles ON user_roles;
--rollback DROP TRIGGER audit_role_permissions ON role_permissions;
--rollback DROP TRIGGER audit_roles ON roles;

--changeset dev:0037-built-in-roles
-- The built-in roles as the Role enum listed them when this was written: literal rows, never read from the enum
-- (a migration must give the same rows on every run). A new permission comes with a changeset of its own granting
-- it to the built-in roles that should hold it (BuiltInRolesIntegrationTest compares them on a fresh database).
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
-- tenancy-row:end
--rollback DELETE FROM role_permissions;
--rollback DELETE FROM roles;

--changeset dev:0037-role-column-to-assignments
-- tenancy-row:begin
-- Each membership's one role becomes its member's assignment of the tenant's built-in role of that name, then the
-- membership's role column goes (the tables are the one source).
ALTER TABLE user_roles NO FORCE ROW LEVEL SECURITY;
INSERT INTO user_roles (id, tenant_id, user_id, role_id, created_at, updated_at, version)
SELECT uuidv7(), m.tenant_id, m.user_id, r.id, now(), now(), 0
  FROM tenant_memberships m
  JOIN roles r ON r.tenant_id = m.tenant_id AND r.built_in = m.role;
ALTER TABLE user_roles FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_memberships DROP COLUMN role;
--rollback ALTER TABLE tenant_memberships ADD COLUMN role text;
--rollback UPDATE tenant_memberships m SET role = coalesce((SELECT max(r.built_in) FROM user_roles a JOIN roles r ON r.id = a.role_id WHERE a.user_id = m.user_id AND a.tenant_id = m.tenant_id), 'USER');
--rollback ALTER TABLE tenant_memberships ALTER COLUMN role SET NOT NULL;
--rollback ALTER TABLE tenant_memberships ADD CONSTRAINT ck_tenant_memberships_role CHECK (role IN ('USER', 'ADMIN'));
-- tenancy-row:else
-- -- Each account's one role becomes its assignment of the built-in role of that name, then the column goes.
-- INSERT INTO user_roles (id, user_id, role_id, created_at, updated_at, version)
-- SELECT uuidv7(), u.id, r.id, now(), now(), 0
--   FROM users u
--   JOIN roles r ON r.built_in = u.role;
-- ALTER TABLE users DROP COLUMN role;
-- --rollback ALTER TABLE users ADD COLUMN role text;
-- --rollback UPDATE users u SET role = coalesce((SELECT max(r.built_in) FROM user_roles a JOIN roles r ON r.id = a.role_id WHERE a.user_id = u.id), 'USER');
-- --rollback ALTER TABLE users ALTER COLUMN role SET NOT NULL;
-- --rollback ALTER TABLE users ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'));
-- tenancy-row:end
