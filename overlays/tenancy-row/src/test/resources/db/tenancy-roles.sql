-- The tenancy piece's database roles in the test container, as docs/tenancy-runbook.md creates them in
-- a real deployment: Liquibase migrates as the owner, the application's pool connects as the app role,
-- which owns nothing and doesn't bypass row-level security. Tests that bypassed these roles would prove
-- nothing about isolation.
CREATE ROLE test_owner LOGIN PASSWORD 'test-owner';
CREATE ROLE test_app LOGIN PASSWORD 'test-app' NOSUPERUSER NOBYPASSRLS;
-- The extensions a changeset creates (btree_gist, with an exclusion constraint), and the tables.
GRANT CREATE ON DATABASE test TO test_owner;
GRANT CREATE ON SCHEMA public TO test_owner;
-- The index tests ANALYZE their tables before EXPLAIN; production's app role never needs this.
ALTER DEFAULT PRIVILEGES FOR ROLE test_owner GRANT MAINTAIN ON TABLES TO test_app;
