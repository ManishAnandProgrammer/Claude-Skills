# Bootstrap (`/crud-gen bootstrap`)

One-time setup of the shared house infrastructure, **copied from the reference application**
(`~/.claude/skills-reference`, the skills repo's `reference-app/`) by
`~/Documents/claude-skills/scripts/bootstrap-copy.sh` (on a zip copy: `<copy>/scripts/…`). The
reference builds and passes its tests, and `scripts/verify.sh` proves a fresh copy does too, so
bootstrap copies tested code instead of writing it from a description. Claude only adapts what
differs per project.

**Rules:**
- **Never overwrite.** The script skips files that exist and reports them; on an existing project,
  compare each skipped file with the reference and propose the differences one by one. To compare
  pieces without copying into the project, copy them into a scratch directory holding an empty
  `pom.xml` (the script refuses a target without a build file and never touches it) and diff the two
  trees.
  A project's own `lombok.config` is skipped like any other: add the reference's
  `lombok.copyableAnnotations` line to it, or every `@Qualifier` on a Lombok-injected field is silently
  dropped (house-review JAVA-26).
- **Never add a dependency without asking** (libraries-first): list each with its version from
  Maven Central and its Boot compatibility, and wait for the decision.
- No `var`, text blocks, why-only comments, no tunables in code: the house conventions.

## 0. Ask, then write the profiles
With **AskUserQuestion** (several per call):
1. `rootPackage`, the modules and their one-line owners, `moduleContracts` (suggest
   `api-facades`), `instances`.
   Also `requestSuffix` (`Request` by default; `Payload` or `Command` if the team prefers: one per
   project). Aggregates (`aggregates`, ARCH-15) are decided per entity by crud-gen; the sign-in
   piece brings `User (UserService)` and `RefreshToken (TokenService)`.
2. **Sign-in:** the app issues its own tokens (the `sign-in` piece), an external identity
   provider (copy `core` only, then adapt `SecurityConfig` to the provider's issuer and JWK set),
   or none. **Roles:** which beyond USER (and ADMIN with metadata, media or tenancy, where a tenant's
   owner is its ADMIN). An account has exactly one role and roles don't include each other: the
   pieces ship no role hierarchy, on purpose (house-review REST-25). Every signed-in account passes a
   USER-level endpoint (it has no role check); where a role is named, the code lists every role that
   may: an upload purpose its roles (`"USER", "ADMIN"` when members and the owner both write the
   record), a role annotation `hasAnyRole`. So ask which roles write what, not whether roles nest. With public signup, any
   internet account is a USER: a table nobody owns (a catalogue, reference data) needs a role for its
   writes, asked per entity by crud-gen (SKILL.md Step 1, *Owned or shared*).
3. **Signup policy** (with sign-in): the `sign-in` piece implements **immediate accounts** only
   (201 with the account; signup reveals whether an email is registered). Say so; email-verified
   signup (202 that never reveals an existing account, with an email-sending choice) is planned, not
   built: hand work if the project needs it now. Record the accepted enumeration risk in the review
   profile's *Conventions*.
4. **Breached-password check** (the `sign-in` piece's `BreachedPasswordCheck`: Have I Been Pwned
   range API, timeouts, a bulkhead, failures counted; on or off with
   `app.security.breached-passwords.enabled`; fail-open. Fail-closed needs a code change: say so), `tokenRevocation`, the client-IP log mode, `metricsAuth` (how
   the metrics scrape is secured).

   **Session version** (with sign-in; opt-in, default **no**: the `session-version` piece). Every
   access token then carries the account's session version (with tenancy, the membership's: a change
   in one tenant leaves the person's other tenants alone), every request compares it, and every
   access change raises it in its own transaction: the person's older tokens, on every device, are
   refused on their very next request (401 `SESSION_VERSION_STALE`) and the app refreshes into the
   new access (a promotion needs no new sign-in; a removal or suspension makes the refresh fail). It
   brings `DELETE /users/me/sessions` (sign out everywhere); with tenancy, role changes, removals and
   a tenant's suspension bump. **Cost:** one Redis GET per authenticated request (the plain JWT's "no
   lookup per request" is gone), a column, a circuit breaker; on a Redis miss one read by primary key,
   cached for `app.session-version.cache-ttl` (1 minute); while Redis is down, that read on every
   request instead (logged once, counted). **Say yes** when stale access is unacceptable even for
   minutes: firing someone, a compromised account, a school removing a teacher at once, "log out
   everywhere" as a feature. **Say no** (the default) when the 15-minute window is fine: rungs 1 to 3
   below already stop the dangerous case, a stale role that undoes its own demotion. The ladder (pick
   the lowest rung that is enough; 1 to 3 are always there):

   | Rung | What | Revocation delay | Cost |
   |---|---|---|---|
   | 1 | Short access tokens (15 min) + refresh tokens in the database, rotated | ≤ token lifetime | none per request |
   | 2 | A refresh re-reads role, status and membership from the database | ≤ token lifetime | one read per refresh |
   | 3 | Access-changing writes check the caller's current role in the database, not the token | zero, for those writes | one read on those writes |
   | 4 | **Session version** (this piece) | zero, every request | one Redis GET per request |
   | 5 | Opaque tokens / server sessions (not offered) | zero | a session lookup per request and a store |

   With yes, add `session-version` to `--with` and record `tokenRevocation: session-version` in the
   review profile (house-review SEC-07: every later access-changing write bumps it). The sign-in piece's
   password change and account suspension already bump it and revoke the refresh tokens in their own
   transaction (SEC-07), as a project's own access-changing write must. One device's logout stays as it is
   (that refresh token only; a bump would sign out every device). A deny list of tokens is not offered:
   it stores what it revokes and misses tokens issued while it runs.
5. **Packaging:** a Dockerfile (JDK or jlink runtime with `jcmd` and JFR, non-root, the CFG-08
   flags) or Boot's `build-image` (record which flags Paketo sets).
6. **Configuration metadata:** does the project need per-tenant, per-feature configuration (rules a
   tenant or an admin chooses per context) or gapless business numbers? Then the `metadata` piece
   (with tenancy, its tables are per tenant: META-09).
7. **Rate limiting:** the `sign-in` piece ships limits on every public sign-in endpoint, per IP
   and, for login, per account (`AuthRateLimits`: Bucket4j token buckets in Redis through `RedisRateLimiter`, shared by
   all instances, limits in configuration, 429 + `Retry-After`; fails open, logged and counted, while
   Redis is unreachable). Other abuse-prone endpoints (REST-14): ask which, and reuse that limiter.
8. **User files:** does the project store files people upload (attachments, documents, photos)?
   Then the `media` piece (needs `sign-in`). AWS S3 or another S3-compatible store, and whether any
   upload comes from a public form without sign-in (not in the piece: house-review MEDIA-14). The
   purposes (one `UploadPurpose` each, with the role that may upload and its limits) come with their
   entity in crud-gen Step 5, once its owner and writers are known: note them here, add none yet.

9. **Tenancy:** does one deployment serve several organisations whose data must never meet?
   - `none`: single tenant (the default).
   - `schema`: one PostgreSQL schema per tenant (the `tenancy-schema` piece; **needs `sign-in`**). For
     up to a few thousand tenants that want their data apart: export, delete or restore one tenant as
     a schema. Tens of thousands strain the catalog, `pg_dump` and autovacuum: that is `row`.
   - `row`: one schema, `tenant_id` and row-level security on every tenant table (the `tenancy-row`
     piece; **needs `sign-in`**). For many tenants, up to millions, sharing one set of tables:
     one migration run, cross-tenant reporting possible; exporting or deleting one tenant is a query per
     table. Searches on operators that aren't leakproof (`jsonb @>`, `LIKE`, `ILIKE`, range `&&`) can't
     use their own index under the policy (house-review TEN-36): say so when the project's lists search
     that way.

   With `schema` or `row`, also ask **registration**: staff only (platform staff create a tenant for an
   existing account, which becomes its ADMIN; no public endpoint), or self-registration with approval
   (add the `tenant-registration` piece: a public, rate-limited `POST /auth/tenant-registrations`
   creates a PENDING tenant owned by the registering account, staff approve it, and the answer never
   tells whether an email has an account). Email-verified registration needs mail sending, which no
   piece has yet: record that the owner's email is not verified. The deployment needs two database
   roles (and, with `schema`, the platform schema) before its first start (`docs/tenancy-runbook.md`;
   locally `compose.yaml` creates them). Record `tenancy` in both profiles.

   Say what tenancy brings and what it doesn't yet. It brings tenant ADMINs managing their members
   (`/tenant-members`: an offset-paged list of the tenant's members and one member's GET, with `ids`,
   `role` and an email `query`, each row with the `version` a role change sends back; add an existing
   account by email, change a role, remove; the last ADMIN stays. The caller of a change must be an
   ADMIN at the moment of the call, whatever the token says, so a removed or demoted ADMIN's older
   token is 403 there at once; the reads go by the token's role, unless the session version is on: then
   that token is 401 on every endpoint at once. Sign-ins don't move the version), the
   `user.api.Members` facade other modules ask about a person ("a member of this tenant?", a view by
   id or by a batch of ids),
   switch-tenant, impersonation (a reason required) with an audit row that staff read at
   `GET /platform/tenants/{id}/audit`, and a tenant's owner as its ADMIN. Roles don't
   include each other: a record that members and the owner both write names both roles where a role
   is listed (an upload purpose: `"USER", "ADMIN"`). Not built: invitations of people without an
   account, a retention job for CLOSED tenants. Record the ones the project needs as open items.
   (Built since: staff deactivation, `platform_users.deactivated_at` with `ActiveStaffFilter`; the
   `tenant.provisioning.failed` metric.)

Write `.claude/crud-profile.md` and `.claude/review-profile.md` from their templates. The review
profile's `mainBranch` is the repository's base branch, read from git (`git symbolic-ref --short
refs/remotes/origin/HEAD`, else `git branch --show-current` on a new repository), never the
template's `main` unchecked: a `diff` review against a branch that doesn't exist fails. With a
tenancy piece, the review profile's list keys name the piece's own lists too (REST-08 checks them):
`TenantMembership` (`/tenant-members`) and `Tenant` (`/platform/tenants`) under `offsetLists`,
`PlatformAuditEntry` under `cursorLists`.

## 1. Copy the pieces
**No project yet?** Start it from the reference rather than Initializr (same Boot version, the
wrapper, the pom with every dependency the pieces can need; step 3 trims it to the approved ones):
```bash
mkdir -p <project>/src/main/java/<package path> && cd <project> && git init -b main
cp -r ~/Documents/claude-skills/reference-app/{mvnw,mvnw.cmd,.mvn,.gitignore,.gitattributes} .
sed -e 's|<groupId>com.example</groupId>|<groupId><group></groupId>|' \
    -e 's|<artifactId>reference-app</artifactId>|<artifactId><app></artifactId>|' \
    -e 's|<name>reference-app</name>|<name><app></name>|' -e 's|<description>.*</description>|<description><one line></description>|' \
    ~/Documents/claude-skills/reference-app/pom.xml > pom.xml
```
Then write `<App>Application` (`@SpringBootApplication @ConfigurationPropertiesScan`, a `main`) in
the root package, and copy the pieces. Step 2's Initializr clean-up doesn't apply.

```bash
~/Documents/claude-skills/scripts/bootstrap-copy.sh --target <project> --package <rootPackage> --with core,sign-in --dry-run
~/Documents/claude-skills/scripts/bootstrap-copy.sh --target <project> --package <rootPackage> --with core,sign-in
```
| Piece | What it brings |
|---|---|
| `core` | `common/` (error contract with `ErrorCode`, mapper, `AppException`, RFC 9457 `errors` (`RequestError`, `JsonPointers`), transient-failure 503s; wrappers `Object`/`List`/`Map`/`Pagination`/`Cursor`; `PageFilter`, `CursorFilter`, `Paging`, `OffsetPages`, `Cursor`, `Seek`, `TextSearch`; `Limits` with `@MaxLength` / `@ConfiguredMax` and their OpenAPI customizer; the dropdown types; `AuditableEntity`; logging context and filters; admission control; `ProblemTypes`, `ProblemResponses`, `RetryAfterProperties`; `RateLimitExceededException` (429); `CurrentUser`, `PersonalDataHasher`, `@AdminOnly`; `@MultiInstanceSafe`); `config/` (security, JWT validation, messages, auditing, scheduling, OpenAPI, startup checks, pool readiness, clock, Liquibase's own connection with a `lock_timeout`); `application.yml` + `config/*.yml` split by concern with `-dev`/`-prod` variants; logback; messages; the test base, ArchUnit and contract tests; `compose.yaml`, `.env.example`; `lombok.config` (Lombok copies `@Qualifier` onto the constructors it generates, JAVA-26, and marks its code `@lombok.Generated` so coverage leaves it out); `spotbugs-exclude.xml` (the static-analysis gate's false positives, house-review CFG-11: each entry names a piece's class and arrives with its piece); `docs/runbook.md` (the incident runbook, OPS-10) |
| `sign-in` | The `user` module (signup, login, refresh rotation with a grace window, logout, purge job; the account lifecycle: `POST /users/me/password` and, without tenancy, an ADMIN's `GET /users?email=` lookup and `PATCH /users/{id}/status` suspension and reactivation, every token path re-reading the account under a share lock, SEC-07; so the `ADMIN` role, changeset 0007 and `newAdmin()` come with sign-in), its changesets (0001, 0007, 0027), the Redis rate limiter (`RedisRateLimiter`, `RateLimit`, `RateLimitProperties`, `RedisClientConfig` and their configuration), its tests. **core's tests need it**: they sign users in. Tokens are signed HS256 with one shared secret. Later, not built: a key pair (RS256/ES256, a JWKS endpoint, `kid` rotation, and with tenancy each cell checking that it serves the token's tenant), to build when a second deployable checks tokens; HS256 stays the default |
| `metadata` | Optional: configuration metadata (one record per key, `MetadataKeys`, `MetadataService` with `delete`, admin `GET`/`PUT /metadata/{key}` for **fixed keys only**, the `ADMIN` role and the test base's `newAdmin()` with `sign-in`) and gapless `Counters` (`perContext`, `delete`); changesets 0007 (with sign-in) and 0008; rules in house-review `metadata.md`. Per-context configuration is an endpoint of the resource that owns the context, generated by crud-gen (SKILL.md Step 3) |
| `media` | Optional, needs `sign-in`: the `media` module (`Uploads` with `fileCountRange`, `claim`, `release`, `describe`, `allUploaded`; `UploadPurpose`, `MediaFileView`, `MediaUrls`, `AllowedContentTypes`, `MediaFile` + `MediaFileStatus`, `UploadPolicy`; `POST /upload-urls`, `GET /upload-policies`, admin `PUT /upload-policies/{purpose}`; `MediaVerificationJob`, `MediaCleanupJob`), its configuration (`app.media.*`, `app.jobs.media-verification.*`, `app.jobs.media-cleanup.*`, `app.limits.upload-policy.*`), the `media_files` and `upload_policies` changesets, its tests against an S3-compatible server in Testcontainers (the endpoint tests run against the first `UploadPurpose`, so without the example they are **skipped until the project declares one**, crud-gen Step 5); rules in house-review `media.md`. An entity's attachments are generated by crud-gen (SKILL.md Step 5) |
| `tenancy-schema` | Optional, needs `sign-in`: schema-per-tenant multi-tenancy, from the overlays (below), never from the reference itself. Shared by every mode (`overlays/tenancy`): `common/tenancy` (`TenantContext` from the token's `tenant_id`, `TenantScope.runAs`, `NO_TENANT`, Hibernate's resolver, `TenantTaskDecorator`), `config/PlatformSecurityConfig` (the platform chain; the main chain takes tenant tokens only), the `tenant` module (`Tenants` api, lifecycle with `approve` as the one way in, `TenantCreated` / `TenantApproved` / `TenantProvisioned`, `PlatformTenantController`, the `platform_audit` trail, `SupportAccessPolicy` with default allow), in `user`: memberships (role per tenant, `GET /users/me/tenants`) and the tenant's own `members` (`Member`: each mode's changeset `0017-members`; with `example`, `0018-owners-reference-members` points the owners at them instead of `users`, so a tenant's data is a closed graph, TEN-16/17), switch-tenant, platform staff and their login, impersonation (`act` claim); with `metadata`, the golden direct cross-tenant edit (`PlatformMetadataService`); `config/tenancy.yml`; changeset `0012`; the test base's tenancy version (one test tenant per run, `@WithoutTestTenant`, `newTenant()`, `newUserIn`, `platformToken()`), the plane, impersonation and ArchUnit tests (with `example`, the todo cross-tenant test). Schema per tenant only (`overlays/tenancy-schema`): the `TransactionLocalSchemaProvider` hook, `TenantSchemas`, `config/TenancyConfig`, `META-INF/orm.xml` (the platform's entities in schema `platform`), provisioning and the deploy-time migrations (`TenantProvisioningJob`, `TenantMigrations`, `tenant_migrations`, changeset `0013`), the two masters (`platform.yaml`, `tenant.yaml`, written by the script: the platform's changesets in the first, every other in the second), `docs/tenancy-runbook.md`, `docker/postgres-init/` (the roles for `docker compose up`), real roles in Testcontainers, the isolation and migration tests. Core's `LazyConnectionConfig` is left out (the hook borrows lazily). Rules: house-review `tenancy.md` |
| `tenancy-row` | Optional, needs `sign-in`, not with `tenancy-schema`: row-mode multi-tenancy. The shared layer (`overlays/tenancy`) as for `tenancy-schema`, then row mode's own (`overlays/tenancy-row`): the `TransactionLocalTenantProvider` hook (`app.tenant_id`, transaction-local, with the read-only flag), `common/persistence/TenantOwnedEntity` (Hibernate's `@TenantId`; the reference's tenant entities extend it through their `tenancy-row` blocks), `config/TenancyConfig`, `TenantProvisioning` (an approved tenant is activated right after the approval commits, its defaults seeded on `TenantProvisioned`; with `media` and `example`, `TodoAttachmentPolicySeed`), changeset `0013-row-security` (the app role's grants), and in the reference's changesets `0002`–`0011` their `tenant_id`, tenant-carrying keys and composite foreign keys, and forced policies (`0016` drops those changesets' `tenant_id` defaults; the row overlay's `MediaFileRepository` and `CounterRepository` replace the reference's, their native statements naming `tenant_id = :tenantId`). One master, the platform's changesets first (every tenant table references `tenants`). `docs/tenancy-runbook.md`, `docker/postgres-init/` (the roles for `docker compose up`), real roles in Testcontainers (the app role `NOBYPASSRLS`), the row isolation test, the catalogue guard (`RowSecurityCatalogIntegrationTest`: every table a tenant table with its forced policy, a `tenant_id` without a default, a replica identity holding `tenant_id` (`0019-replica-identity`, TEN-38) and no key into the platform but `tenants`, or a named platform table) and the native-SQL guard (`NativeQueryTenantIntegrationTest`). Core's `LazyConnectionConfig` is left out. Rules: house-review `tenancy.md` (TEN-16…19, TEN-30…38) |
| `session-version` | Optional, opt-in (default no), needs `sign-in`: the session version (step 0, question 4). `common/security` `SessionVersions` + `SessionVersionFilter` (after the bearer token's checks, in `SecurityConfig`'s `session-version` block: 401 `SESSION_VERSION_STALE`, a database outage 503), `config/SessionVersionOpenApiCustomizer` (the code on every signed-in endpoint's 401), `user/service/SessionVersionService` (Redis `sv:<id>`, SET NX fill, publish after commit, the database by primary key while Redis is down behind the circuit breaker `session-version-cache`), `SessionVersionProperties` (`app.session-version.cache-ttl`), `SessionVersionsRaised`; the `sv` claim in `TokenService`; `DELETE /users/me/sessions` (`AuthService.logoutEverywhere`, `TokenService.endAllSessions`: every refresh token revoked and the version raised in one transaction). Without tenancy the account's number (`users.session_version`, changeset 0021, `UserService.bumpSessionVersion`); with tenancy the membership's (`tenant_memberships.session_version`, changeset 0022, a platform changeset; tokens also carry `membership_id`, so a member removed and added again is a new key no older token names): `MembershipService` bumps on a role change, a removal (before the row goes) and a tenant's suspension (`TenantDeactivated`, one statement), and on signing out everywhere every membership of the person. The `sv` claim, the column, the filter step and the `app.session-version.*` and circuit-breaker keys exist only with the piece (`verify.sh` checks every copy without it). Tests: `SessionVersionIntegrationTest`, `SessionVersionServiceIntegrationTest` (the fill race, a rollback, Redis down), with tenancy `SessionVersionTenancyIntegrationTest` |
| `tenant-registration` | Optional, with a tenancy piece: `POST /auth/tenant-registrations` (`TenantRegistrationService`: a PENDING tenant owned by a new or existing account; signup's rate limit and breached-password check; the same 202 whether or not the email has an account) and its test. Staff approve with `POST /platform/tenants/{id}/approve` (in the tenancy piece) |
| `example` | The golden `todo` and `planning` modules (changesets 0002–0006 and 0011; 0010 with `media`), for study only; not copied into real projects |

**Where tenancy lives.** The reference application is a plain application without tenancy: most
projects don't use it, and a row-based and a schema-based mode can't run in one application. A
tenancy mode is a stack of **overlays**, each mirroring the reference's tree:
`overlays/tenancy/` (what every mode shares), then `overlays/tenancy-schema/` (schema per tenant
only) or `overlays/tenancy-row/` (row mode only): a copy has one mode, so the two never meet, and files
of the same path in both (`config/TenancyConfig`, the runbook, the roles) are each mode's own. An
overlay file is either new
(it arrives with its mode) or has the path of a reference file and **replaces** it in the copy (the
token service, the test base, the media jobs, ...); `overlays/replaced.sha256` pins the reference
version each replacement was written against, and `verify.sh` fails when the reference moves on
without its replacement (port the change, then `scripts/overlay-replacements.sh --update`). Small
differences stay in the reference file as a tenancy marker block whose lines are commented out there
(the error codes, the token audience in `SecurityConfig`, `compose.yaml`, `.env.example`, a test
assertion), so the reference compiles and reads without them; the copy uncomments them.
`verify.sh` proves each mode through bootstrap copies (schema: 2g alone, 2h every piece; row: 2i
alone, 2j every piece). Row mode's differences in the tenant tables themselves (`tenant_id`, the keys,
the policies, the entities' base class) are `tenancy-row` blocks in the reference's changesets and
entities, so a crud-gen'd table reads like its golden file in both modes.

## 2. Adapt
- The application class gets `@ConfigurationPropertiesScan`; delete Initializr's
  `application.properties` (the copied `application.yml` replaces it) and its
  `<App>ApplicationTests` (a bare `@SpringBootTest` without the test base's Testcontainers: it starts
  a second context with no database and fails the build). **Before the copy**, delete Initializr's
  `TestcontainersConfiguration.java` if it generated one (PostgreSQL only: the script would keep it
  and every test would lack Redis and the media store; the script warns); its
  `Test<App>Application` may stay.
- `.gitignore`: the script appends `.env` and the JVM's dump files (`*.hprof`, `*.jfr`,
  `hs_err_pid*.log`: tokens and personal data, SEC-04) when missing; check it before the first commit.
- `spring.application.name`: everything else derives from it (error URNs, pool name, JDBC
  `ApplicationName`, JWT issuer, the database name, logs, metrics tag). `.env.example`'s `DB_NAME`
  (which `compose.yaml` creates) and its commented `JWT_ISSUER` say `reference`: set them to the
  same name.
- Changesets: the copied ones keep the reference's numbers, with gaps for pieces not copied:
  sign-in 0001, 0007 (the ADMIN role: an ADMIN suspends accounts) and 0027 (account status, the new
  revocation reasons); metadata 0008; media 0009; example 0002–0006 and 0011, plus 0010 with media;
  tenancy 0012 (plus 0015, staff accounts read-only for the app role, and 0028, staff deactivation),
  schema per tenant 0013 (`0013-tenant-schemas`), row mode 0013 (`0013-row-security`) and, with a
  piece that brings tenant tables, 0016 (their `tenant_id` defaults dropped: every insert names its
  tenant); example with metadata 0014 (the fixed key's seed); tenancy 0017 (the tenant's `members`),
  0018 with the example (its owners reference members), row mode 0019 (replica identities, with a
  piece that brings tenant tables), 0020 (the members list's index), row mode with the example 0023
  (the owners' dependency statistics); session-version 0021 without tenancy (the users column) or 0022
  with it (the membership column, a platform changeset); example 0024 (tags by status, newest first) and
  0025 (a todo's requester), with metadata 0026 (the todos' display numbers), 0029 (NEWEST indexes on the
  creation time); tenancy 0030 (the same for the platform's lists). **The pieces own 0001 to
  0099; a project's own changesets start at 0100** (`0100-orders.sql`, then 0101, …), whatever the copy
  holds: a piece turned on later (the session version) or a piece's new changeset in a later skills
  release brings its own number below 0100 without two files sharing one, appended to the master after
  the project's (the master's order is the order they run in, so a late 0027 runs after 0105). An
  existing project whose own changesets already use numbers below 0100 keeps them (an applied changeset
  is never renamed) and numbers its next one 0100; a piece's file that would share a number with one of
  them is copied as is (different names, different ids: only the upgrade path reads the number, and it
  reads the ranges below).
- The answers of step 0: signup policy, external IdP (`SecurityConfig`), client-IP mode,
  `app.limits.*` values, pool and admission sizes (admission ≥ 2× pool, checked at startup).
- The upgrade path (core's `support/PreviousReleaseDatabase`) is inert until the project has a release in production: then it writes
  `src/test/resources/db/upgrade/previous-release-seed.sql` (with the example, the reference's is there to
  follow) and runs `./mvnw verify -Dapp.test.upgrade.previous-release=<pieces>,<project>` (the highest
  number of each range in production, `26,105`) before each release that
  changes existing rows (tests.md, *Upgrading a used database*).
- `ErrorCode`: remove the sign-in codes if `sign-in` wasn't copied. Constraint mappings live in each
  module's `ConstraintErrorCodes` bean (`UserConstraintErrorCodes`, ...), which the handler
  aggregates, so they come and go with their module.
- The ArchUnit rules' module list comes from the profile, and so do the rules the profile can change:
  `LayerArchTest.onlyServicesUseRepositories` follows `repositoryAccess` (`service, mapper` adds
  `"..mapper.."`), `ConventionArchTest.scheduledJobsDeclareTheirConcurrencyPosture` takes the project's
  lock annotation beside `@MultiInstanceSafe` (`.orShould().beAnnotatedWith(SchedulerLock.class)` with
  ShedLock), and `ModuleBoundaryArchTest.commonDependsOnNoFeature` lists the project's own libraries that
  `common` uses (never a feature package). Each rule's comment says so.
- `docs/runbook.md`: the deployment's own commands (how an instance is stopped, how a release is
  rolled back) where it says `docker`; `<app>` stays a placeholder for `spring.application.name`.
  With `sign-in` and an ADMIN role (metadata, media) and no tenancy, `docs/local-dev.md` step 4 and the
  runbook's *Operator tasks* say how the first admin is made (sign up, then the owner's `UPDATE users
  SET role = 'ADMIN'`, then sign in again); name it to the user in the closing summary, and add the
  project's own role names when step 0 added roles. With `session-version`, those statements also
  raise `session_version` and the operator drops the Redis copy (`DEL sv:<id>`), which the copied
  runbook says: an access change in SQL without the bump keeps the older tokens valid.
- With `metadata` but without `example`, `MetadataKeys.byName`'s list of fixed keys is empty (its
  comment stays): the project's first fixed key goes there; per-context keys never do. Until then
  `GET/PUT /metadata/{key}` only answer `METADATA_KEY_UNKNOWN`: ask whether the project has a first
  fixed key (add it now) or only per-context keys and counters; with neither, drop the piece. When
  the owner keeps it anyway (a key is planned), record an open item in the closing summary and the
  profile: the admin API has no test until the first fixed key exists, and that key's change adds them. **A
  first fixed key** is seeded by a changeset of its own (META-05; golden `0014-todo-list-defaults.sql`:
  the record's defaults written out), the project's next number (from 0100); with `tenancy-schema` it runs in every tenant
  schema as it is; with `tenancy-row` the changeset's `tenancy-row` block seeds the existing tenants
  (RLS lifted for its own transaction) and a `TenantProvisioned` listener seeds new ones (golden
  `overlays/tenancy-row/.../todo/service/TodoListDefaultsSeed.java`). Its tests are crud-gen tests.md's
  *A first fixed metadata key* list, ported from the golden `MetadataIntegrationTest` (bootstrap copies
  that class only with `example`), with the test base's `storedMetadata` / `storeMetadata` /
  `restoreMetadata`.
- With `media`: set `app.media.bucket` and `app.media.region` (`app.media.endpoint` stays empty for
  AWS, the server's URL for another S3-compatible store); check `app.media.read-url-expiry`
  against the lifetime of the deployment's credentials (MEDIA-11). Credentials come from the SDK's
  default provider chain, never from configuration. Without `example`, `UploadPurpose` stays empty:
  each purpose and its `upload_policies` seed row come with the entity that owns the files (crud-gen
  Step 5, `references/media.md`), and the media endpoint tests stay skipped until then. Locally,
  `compose.yaml`'s store answers CORS for `CORS_ALLOWED_ORIGINS` (`VGW_CORS_ALLOW_ORIGIN`, one
  origin), so a browser on that origin can PUT to the presigned URL. The bucket itself is a runbook
  step for the owner (MEDIA-13: Block Public Access, owner-enforced ownership, encryption, CORS for
  PUT with `Content-Type` and `Content-Disposition`, a least-privilege IAM role, a budget alarm); add
  it to `docs/runbook.md`.
- `.env.example`: every required variable (`DB_USER`, `DB_PASSWORD`, `JWT_SECRET`,
  `PERSONAL_DATA_PEPPER`, ...). Its `*_PORT` values are also the host ports `compose.yaml` publishes:
  another project on the machine means other ports there, not an edited `compose.yaml`.
- With `tenancy-schema`, `docs/tenancy-runbook.md` is the owner's to run before the first start: the
  roles `<app>_owner` (owns the tables, runs Liquibase: `DB_OWNER_USER` / `DB_OWNER_PASSWORD`) and
  `<app>_app` (the pool: `DB_USER`), the `platform` schema, both roles' default `search_path`; then
  the restore order and the deploy-time migrations. Locally `docker compose up -d` creates the same on
  a new database (`docker/postgres-init/10-tenancy-roles.sh`). The platform's staff are rows the owner
  inserts (no endpoint creates one). Every new tenant table's changeset goes into `tenant.yaml`, every
  platform table's into `platform.yaml` and its entity into `META-INF/orm.xml` (TEN-21, TEN-22).
- With `tenancy-row`, `docs/tenancy-runbook.md` is the owner's to run before the first start: the roles
  `<app>_owner` (owns the tables, runs Liquibase: `DB_OWNER_USER` / `DB_OWNER_PASSWORD`) and `<app>_app`
  (the pool: `DB_USER`, `NOSUPERUSER NOBYPASSRLS`), `CREATE` on the database and on `public` for the
  owner; then data fixes as the owner with a tenant set, plans as the app role, the restore order.
  Locally `docker compose up -d` creates the roles on a new database. A project table of the platform
  (no tenant) joins `RowSecurityCatalogIntegrationTest.PLATFORM_TABLES` with its reason; every other
  table is a tenant table (TEN-30, TEN-35). The master lists the platform's changesets first; a
  project's new changesets go at its end.

## 3. Dependencies
Start the pom from `reference-app/pom.xml` (its dependencies, the annotation processor and the
plugins), keeping only the approved pieces. The PostgreSQL driver is **compile** scope (not
Initializr's `runtime`): constraint names and the Liquibase connection use its classes. The script prints the dependencies each piece needs, every one the reference pom has (`verify.sh` checks the lists against it). Propose them (with versions and Boot
compatibility) and add the approved ones. Also propose `micrometer-registry-prometheus` with the
`metricsAuth` decision; without it nothing can scrape the metrics. With `media`:
`software.amazon.awssdk:s3` through the AWS SDK's BOM (`software.amazon.awssdk:bom`), and the test
image `versity/versitygw` (the version the reference pins) for the S3-compatible server in tests.

**The quality gates** (house-review CFG-11) come with the pom: the reference's `spotbugs-maven-plugin`
(with `findsecbugs-plugin`) and `jacoco-maven-plugin` blocks, their version properties and the
`coverage.line.minimum` / `coverage.branch.minimum` properties; the script prints them with core. A
project started from the reference pom has them; an existing project's pom gets them proposed like a
dependency (the house's decided gate: SpotBugs with FindSecBugs at effort max and threshold medium,
JaCoCo; SonarLint stays an IDE aid). Core copies `spotbugs-exclude.xml`, and `lombok.config` carries
`lombok.addLombokGeneratedAnnotation`; a project's own `lombok.config` gets that line too, or Lombok's
code counts against the minimum. **The minimums are the project's own:** the reference's measure the
reference's suite, so the copy measures its own level in step 4 and sets each by the same rule.

## 4. Verify
1. Build, test and the gates (`{{buildCommand}}`, `verify`: green means that command, never the tests
   alone). **The coverage minimums:** run it three times on the clean copy with
   `-Djacoco.haltOnFailure=false` (each run's report: `target/site/jacoco/index.html`, the total's
   line and branch ratios) and set each minimum **a full point below the lowest run**, rounded down
   (lines 87.6 %, 87.9 %, 87.4 % give 0.86), with the pom comment saying how it was set and that it is
   only ever raised; then the build is green without the flag. **Static analysis:** a finding in a
   copied file is a reference bug to report to the skills (the copy is the tested reference), never a new
   exclude entry; one in the project's adapted code is fixed, or excluded by class and pattern with its
   reason (CFG-11). **Check the test count is what you expect**: a test class
   Surefire doesn't pick up (named `*Rules` instead of `*Test`) passes silently by not running. `ErrorCodeContractTest` runs two cases per `ErrorCode`, so every added code adds two.
2. The project-wide tests exist once: `lockedRowAnswers503` (a write against a row another
   transaction locked: 503 `DATABASE_CONTENTION` + `Retry-After`, row unchanged; ERR-03) and
   `apiDocsShowTheConfiguredLimits` (the configured `maxLength` in the OpenAPI schema; REST-09).
   The reference has them in `TodoIntegrationTest` and `LimitsIntegrationTest`. The sign-in piece's
   copied `LimitsIntegrationTest` already has an `apiDocsShowTheConfiguredLimits` (the paging limits),
   so a grep finds the name: the project's first entity still adds its own, the entity-level one of
   golden `TodoListIntegrationTest` (its `maxLength`, a 401 and a 409 code). Without the `example`
   piece, write `lockedRowAnswers503` for the project's first entity (grep for it first). With
   `media` and no purpose yet, Surefire reports the media endpoint tests as skipped: expected.
3. Review only what step 2 adapted (the application class, the changed configuration, `ErrorCode`,
   `SecurityConfig` for an external IdP, the runbook): `/house-review <those files>`. The unmodified
   copy is the reference, which `verify.sh` builds and tests in the skills repository; reviewing
   the copied files again finds nothing new. Expected: no findings outside open business decisions.
4. Report what was copied, what was skipped (with the differences proposed), and the decisions.
