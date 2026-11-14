# Running it locally

1. `cp .env.example .env` and fill in the empty values (`openssl rand -base64 48` for each secret).
   `.env` is git-ignored. Its `*_PORT` values are also the host ports `compose.yaml` publishes:
   change them there when another project on the machine uses the defaults.
2. `docker compose up -d` starts what the app needs:
   - PostgreSQL;
<!-- sign-in:begin -->
   - Redis (the sign-in rate limits);
<!-- sign-in:end -->
<!-- media:begin -->
   - an S3-compatible store that creates the bucket and answers CORS for `CORS_ALLOWED_ORIGINS` (one
     origin), so a browser there can PUT to a presigned URL.
<!-- media:end -->
3. Spring Boot doesn't read `.env`: export it into the shell that starts the app,
   `set -a; . ./.env; set +a; ./mvnw spring-boot:run`. The API is on `SERVER_PORT` (8080), health and
   metrics on `MANAGEMENT_PORT` (8081); the `dev` profile serves the OpenAPI UI at `/swagger-ui.html`.
<!-- tenancy:begin -->
<!-- 4. With tenancy, `compose.yaml` creates the two database roles on a new data directory; platform -->
<!--    staff are rows you insert (`docs/tenancy-runbook.md`, section 2); staff then create or approve -->
<!--    tenants. -->
<!-- tenancy:end -->

`docker compose down -v` removes the data. An app started from `target/*.jar` breaks when a build
repackages that jar under it: run a copy of the jar while you keep building.
<!-- sign-in:begin -->
The sign-in rate limits count per client IP: a local browser test run may need the overrides that
`.env.example` shows.
<!-- sign-in:end -->
