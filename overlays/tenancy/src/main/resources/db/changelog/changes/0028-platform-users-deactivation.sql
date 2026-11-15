--liquibase formatted sql

-- Staff deactivation (the tenancy piece): a staff member whose deactivated_at is set signs in no more, and
-- the tokens they hold die at their next request (every platform request re-reads the row by primary key,
-- ActiveStaffFilter), as does an impersonation they would start. Staff rows are the owner role's to write
-- (0015): an operator deactivates one with SQL (docs/tenancy-runbook.md, "Platform staff"). A row with audit
-- history can't be deleted (fk_platform_audit_actor_id), so this is how staff leave.

--changeset dev:0028-platform-users-deactivation
ALTER TABLE platform_users ADD COLUMN deactivated_at timestamptz;
--rollback ALTER TABLE platform_users DROP COLUMN deactivated_at;
