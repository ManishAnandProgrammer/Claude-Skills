# reference-app

The house reference application: real, building, tested code that the skills copy and point at.

- `/crud-gen bootstrap` copies its **pieces** into a project with `scripts/bootstrap-copy.sh`,
  renaming the root package `com.example.app`:
  - `core`: error contract, wrappers, paging, limits, logging, admission control, security config,
    persistence base, config files, test base, ArchUnit, the incident runbook;
  - `sign-in`: the `user` module (signup, login, tokens) and its Redis rate limiting (the limiter in
    `common/ratelimit`, `config/RedisClientConfig`, the Redis settings, compose service and
    Testcontainer); without it, core keeps only `RateLimitExceededException` (the 429);
  - `metadata` (optional): configuration metadata and gapless counters;
  - `media` (optional): uploads straight from the browser to S3 with presigned PUTs (the `media`
    module: upload policies, upload URLs, the `Uploads` claim/release API, the verification and
    cleanup jobs); with the example, the todo's attachments;
  - `example` (reference only): the `todo` and `planning` modules below.
  - `metadata` and `media` have admin endpoints: with `sign-in`, either brings the `ADMIN` role.
- The `todo` module is the **golden crud-gen output**: what a generated entity looks like, with its
  tests. The `planning` module adds the shapes `todo` can't host: a parent in another module
  (`todo.api.Todos`) and no overlapping ranges (an exclusion constraint). crud-gen generates like it; house-review cites it as compliant code.
- Skills find it at `~/.claude/skills-reference` (linked by the installers).

It is not an application anyone deploys. Its domain (todo lists and todos) only exists to show
every pattern working: offset paging (lists), cursor paging (todos, the dropdown), projections,
ownership, optimistic locking, state actions, a generated keyset sort column.

## Build

JDK 25 and Docker (Testcontainers):
```bash
./mvnw -B verify                    # or: sg docker -c "./mvnw -B verify"
```
`../scripts/verify.sh` builds this project **and** fresh bootstrap copies of it (core with sign-in;
with the example and metadata; metadata without the example; media without the example; the example
with media; core alone), then starts the image. The media tests run an S3-compatible store
(`versity/versitygw`) in Testcontainers.
Run it before pushing: the pre-push hook only checks that it passed on exactly this code, since the
repository is private and has no CI.

## Running it locally

`cp .env.example .env`, fill in the empty values, `docker compose up -d` (PostgreSQL; Redis with
sign-in; with media, an S3-compatible store that creates the bucket), then run the app with the
`.env` values exported (`set -a; . ./.env; set +a; ./mvnw spring-boot:run`). With media, the
presigned flow works against the local store as against S3: `POST /api/v1/upload-urls`, then `PUT`
the bytes to the returned URL with its headers. `docker compose down -v` removes the data.

## Changing it

Every change must keep `scripts/verify.sh` green. Behaviour the skills describe lives here first;
the skill text then points at the file instead of repeating code.

Configuration is split by concern under `src/main/resources/config/` (with profile variants such as
`db-prod.yml`); every tunable number is in `config/limits.yml` or `config/jobs.yml`.

## Container

`Dockerfile` packages the jar `./mvnw -B verify` built (`docker build -t reference-app .`). Give the
container a stop grace period longer than the 30 s drain (`spring.lifecycle.timeout-per-shutdown-phase`):
Docker's default of 10 s kills requests mid-drain. In compose, `stop_grace_period: 40s`; with
`docker run`, `--stop-timeout 40`. The drain covers the normal request, not a rare worst-case signup
or login (about 60 s): the load balancer times out around then anyway, and a short, predictable drain
is safer for deployments (owner's decision). Mount `/diagnostics` (heap dumps, `hs_err`, GC logs, one
directory per start, the newest `DIAGNOSTICS_KEEP` kept, 5 by default) on a private volume: heap dumps
hold tokens and personal data.

## Incidents

`docs/runbook.md`: what to read first (health, pool and bulkhead metrics), the triage SQL (long
queries, idle transactions, blockers, xid age, dead rows), thread dumps under virtual threads, and
the rollback and drain steps. Bootstrap's core piece copies it.
