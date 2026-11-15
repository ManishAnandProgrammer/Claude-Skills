--liquibase formatted sql

-- Staff accounts are written by the owner role only (docs/tenancy-runbook.md, section 2): the application
-- reads them at staff sign-in and never writes them. With the write right, one SQL injection in any
-- request could create a staff account, which may impersonate every tenant. PoolRoleCheck refuses to
-- start while the pool's role can write the table again.

--changeset dev:0015-platform-users-read-only
REVOKE INSERT, UPDATE, DELETE ON platform_users FROM ${appRole};
--rollback GRANT INSERT, UPDATE, DELETE ON platform_users TO ${appRole};
