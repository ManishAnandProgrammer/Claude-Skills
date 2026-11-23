--liquibase formatted sql

-- The pool's role may write a deactivation and nothing else on platform_users, and never undo one: with UPDATE on
-- deactivated_at, one SQL injection could otherwise bring back a staff member who left, and with them every tenant.
-- A privilege can't tell setting the column from clearing it, so the trigger guards it; reactivating is the owner
-- role's SQL (docs/tenancy-runbook.md, "Platform staff").
-- An instance of the previous release leaves impersonated_user_id empty: an impersonation it starts during a rolling
-- deploy isn't found by a deactivation within that impersonation's token lifetime.

--changeset dev:0041-platform-users-deactivation-grant splitStatements:false
GRANT UPDATE (deactivated_at, updated_at, updated_by, version) ON platform_users TO ${appRole};
CREATE FUNCTION platform_users_refuse_reactivation() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NOT pg_has_role(current_user, (SELECT relowner FROM pg_class WHERE oid = TG_RELID), 'MEMBER') THEN
        RAISE EXCEPTION 'A deactivated staff member is reactivated by the owner role only'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER platform_users_reactivation_owner_only BEFORE UPDATE OF deactivated_at ON platform_users
    FOR EACH ROW WHEN (OLD.deactivated_at IS NOT NULL AND NEW.deactivated_at IS DISTINCT FROM OLD.deactivated_at)
    EXECUTE FUNCTION platform_users_refuse_reactivation();
--rollback DROP TRIGGER platform_users_reactivation_owner_only ON platform_users;
--rollback DROP FUNCTION platform_users_refuse_reactivation();
--rollback REVOKE UPDATE (deactivated_at, updated_at, updated_by, version) ON platform_users FROM ${appRole};

--changeset dev:0041-platform-audit-impersonated-user
ALTER TABLE platform_audit ADD COLUMN impersonated_user_id uuid;
UPDATE platform_audit SET impersonated_user_id = substring(detail FROM '^userId=([0-9a-f-]{36}),')::uuid
 WHERE action = 'IMPERSONATION';
ALTER TABLE platform_audit ADD CONSTRAINT fk_platform_audit_impersonated_user_id
    FOREIGN KEY (impersonated_user_id) REFERENCES users (id);
CREATE INDEX idx_platform_audit_impersonated_user_id ON platform_audit (impersonated_user_id);
-- A deactivation's lookup: one staff member's rows of the last token lifetime. It still serves fk_platform_audit_actor_id.
CREATE INDEX idx_platform_audit_actor_id_created_at ON platform_audit (actor_id, created_at);
DROP INDEX idx_platform_audit_actor_id;
--rollback CREATE INDEX idx_platform_audit_actor_id ON platform_audit (actor_id);
--rollback DROP INDEX idx_platform_audit_actor_id_created_at;
--rollback ALTER TABLE platform_audit DROP COLUMN impersonated_user_id;
