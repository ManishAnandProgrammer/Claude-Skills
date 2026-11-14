#!/bin/sh
# Local only (compose.yaml): the roles and the platform schema of docs/tenancy-runbook.md, section 1.
# The postgres image runs this once, when its data directory is new.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v db="$POSTGRES_DB" -v owner="$DB_OWNER_USER" -v owner_password="$DB_OWNER_PASSWORD" \
    -v app="$DB_USER" -v app_password="$DB_PASSWORD" <<'SQL'
CREATE ROLE :"owner" LOGIN PASSWORD :'owner_password';
CREATE ROLE :"app" LOGIN PASSWORD :'app_password';
GRANT CREATE ON DATABASE :"db" TO :"owner";
CREATE SCHEMA platform AUTHORIZATION :"owner";
ALTER ROLE :"app" IN DATABASE :"db" SET search_path = platform;
ALTER ROLE :"owner" IN DATABASE :"db" SET search_path = platform;
SQL
