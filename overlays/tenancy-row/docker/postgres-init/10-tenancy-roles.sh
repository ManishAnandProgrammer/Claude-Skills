#!/bin/sh
# Local only (compose.yaml): the roles of docs/tenancy-runbook.md, section 1. The postgres image runs this
# once, when its data directory is new.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v db="$POSTGRES_DB" -v owner="$DB_OWNER_USER" -v owner_password="$DB_OWNER_PASSWORD" \
    -v app="$DB_USER" -v app_password="$DB_PASSWORD" <<'SQL'
CREATE ROLE :"owner" LOGIN PASSWORD :'owner_password';
CREATE ROLE :"app" LOGIN PASSWORD :'app_password' NOSUPERUSER NOBYPASSRLS;
GRANT CREATE ON DATABASE :"db" TO :"owner";
GRANT CREATE ON SCHEMA public TO :"owner";
SQL
