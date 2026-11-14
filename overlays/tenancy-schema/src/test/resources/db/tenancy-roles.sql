-- The tenancy piece's database roles in the test container, as docs/runbook.md creates them in a real
-- deployment: Liquibase migrates as the owner, the application's pool connects as the app role, which
-- owns nothing. Tests that bypassed these roles would prove nothing about isolation.
CREATE ROLE test_owner LOGIN PASSWORD 'test-owner';
CREATE ROLE test_app LOGIN PASSWORD 'test-app';
GRANT CREATE ON DATABASE test TO test_owner;
CREATE SCHEMA platform AUTHORIZATION test_owner;
-- Connections start in the platform schema: a tenant's tables exist only inside its transactions.
ALTER ROLE test_app IN DATABASE test SET search_path = platform;
-- Liquibase prepends the schema it migrates, so tenant changesets still find the platform's tables.
ALTER ROLE test_owner IN DATABASE test SET search_path = platform;
-- The index tests ANALYZE their tables before EXPLAIN; production's app role never needs this.
ALTER DEFAULT PRIVILEGES FOR ROLE test_owner GRANT MAINTAIN ON TABLES TO test_app;
