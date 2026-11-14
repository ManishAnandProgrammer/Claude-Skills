--liquibase formatted sql

-- Row mode (the tenancy-row piece): the application role's rights. ${appRole} is the pool's role
-- (spring.liquibase.parameters.appRole in config/tenancy.yml); this changelog runs as the owner role,
-- which owns every table. Each tenant table's row-level security is in the changeset that creates it.

--changeset dev:0013-app-role-grants
-- The application role reads and writes, never owns: it can't change the schema, turn a policy off or
-- drop one. Every table so far, then, through the default privileges, every table the owner creates
-- later. Liquibase's own tables stay the owner's alone.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${appRole};
REVOKE ALL ON databasechangelog, databasechangeloglock FROM ${appRole};
-- Append-only: the application may add audit and status-history rows, never change or remove them.
REVOKE UPDATE, DELETE ON platform_audit, tenant_status_changes FROM ${appRole};
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${appRole};
--rollback ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE SELECT, INSERT, UPDATE, DELETE ON TABLES FROM ${appRole};
--rollback REVOKE ALL ON ALL TABLES IN SCHEMA public FROM ${appRole};
