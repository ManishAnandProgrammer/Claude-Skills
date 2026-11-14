# Bootstrap (`/crud-gen bootstrap`)

One-time setup of the shared house infrastructure, **copied from the reference application**
(`~/.claude/skills-reference`, the skills repo's `reference-app/`) by
`~/Documents/claude-skills/scripts/bootstrap-copy.sh` (on a zip copy: `<copy>/scripts/…`). The
reference builds and passes its tests, and `scripts/verify.sh` proves a fresh copy does too, so
bootstrap copies tested code instead of writing it from a description. Claude only adapts what
differs per project.

**Rules:**
- **Never overwrite.** The script skips files that exist and reports them; on an existing project,
  compare each skipped file with the reference and propose the differences one by one.
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
   or none. **Roles:** which beyond USER (and ADMIN with metadata or media), and does a higher one
   include a lower one (is a MANAGER also a USER)? The pieces give an account exactly one role; an
   inclusion is a `RoleHierarchy` bean read by method security and `CurrentUser.hasRole` (house-review
   REST-25), and the copied "another role is 403" tests change with it.
3. **Signup policy** (with sign-in): email-verified signup (answers 202 and never reveals an
   existing account; needs an email-sending choice) or immediate accounts (simpler; signup reveals
   whether an email is registered: record the accepted risk).
4. **Breached-password check** (the `sign-in` piece's `BreachedPasswordCheck`: Have I Been Pwned
   range API, timeouts, a bulkhead, failures counted; on or off with
   `app.security.breached-passwords.enabled`; fail-open. Fail-closed needs a code change: say so), `tokenRevocation`, the client-IP log mode, `metricsAuth` (how
   the metrics scrape is secured), multi-tenant or not.
5. **Packaging:** a Dockerfile (JDK or jlink runtime with `jcmd` and JFR, non-root, the CFG-08
   flags) or Boot's `build-image` (record which flags Paketo sets).
6. **Configuration metadata:** does the project need per-tenant, per-feature configuration (rules a
   tenant or an admin chooses per context) or gapless business numbers? Then the `metadata` piece. Tenancy:
   single, schema-per-tenant or row-based (the piece's `package-info.java` says what changes).
7. **Rate limiting:** the `sign-in` piece ships limits on every public sign-in endpoint, per IP
   and, for login, per account (`AuthRateLimits`: Bucket4j token buckets in Redis through `RedisRateLimiter`, shared by
   all instances, limits in configuration, 429 + `Retry-After`; fails open, logged and counted, while
   Redis is unreachable). Other abuse-prone endpoints (REST-14): ask which, and reuse that limiter.
8. **User files:** does the project store files people upload (attachments, documents, photos)?
   Then the `media` piece (needs `sign-in`). Which purposes (one `UploadPurpose` each, with the role
   that may upload and its limits), AWS S3 or another S3-compatible store, and whether any upload
   comes from a public form without sign-in (not in the piece: house-review MEDIA-14).

Write `.claude/crud-profile.md` and `.claude/review-profile.md` from their templates.

## 1. Copy the pieces
```bash
~/Documents/claude-skills/scripts/bootstrap-copy.sh --target <project> --package <rootPackage> --with core,sign-in --dry-run
~/Documents/claude-skills/scripts/bootstrap-copy.sh --target <project> --package <rootPackage> --with core,sign-in
```
| Piece | What it brings |
|---|---|
| `core` | `common/` (error contract with `ErrorCode`, mapper, `AppException`, transient-failure 503s; wrappers `Object`/`List`/`Map`/`Pagination`/`Cursor`; `PageFilter`, `CursorFilter`, `Paging`, `OffsetPages`, `Cursor`, `Seek`, `TextSearch`; `Limits` with `@MaxLength` / `@ConfiguredMax` and their OpenAPI customizer; the dropdown types; `AuditableEntity`; logging context and filters; admission control; `ProblemTypes`, `ProblemResponses`, `RetryAfterProperties`; `RateLimitExceededException` (429); `CurrentUser`, `PersonalDataHasher`, `@AdminOnly`; `@MultiInstanceSafe`); `config/` (security, JWT validation, messages, auditing, scheduling, OpenAPI, startup checks, pool readiness, clock, Liquibase's own connection with a `lock_timeout`); `application.yml` + `config/*.yml` split by concern with `-dev`/`-prod` variants; logback; messages; the test base, ArchUnit and contract tests; `compose.yaml`, `.env.example`; `docs/runbook.md` (the incident runbook, OPS-10) |
| `sign-in` | The `user` module (signup, login, refresh rotation with a grace window, logout, purge job), its changeset, the Redis rate limiter (`RedisRateLimiter`, `RateLimit`, `RateLimitProperties`, `RedisClientConfig` and their configuration), its tests. **core's tests need it**: they sign users in |
| `metadata` | Optional: configuration metadata (one record per key, `MetadataKeys`, `MetadataService` with `delete`, admin `GET`/`PUT /metadata/{key}` for **fixed keys only**, the `ADMIN` role and the test base's `newAdmin()` with `sign-in`) and gapless `Counters` (`perContext`, `delete`); changesets 0007 (with sign-in) and 0008; rules in house-review `metadata.md`. Per-context configuration is an endpoint of the resource that owns the context, generated by crud-gen (SKILL.md Step 3) |
| `media` | Optional, needs `sign-in`: the `media` module (`Uploads` with `fileCountRange`, `claim`, `release`, `describe`, `allUploaded`; `UploadPurpose`, `MediaFileView`, `MediaUrls`, `AllowedContentTypes`, `MediaFile` + `MediaFileStatus`, `UploadPolicy`; `POST /upload-urls`, `GET /upload-policies`, admin `PUT /upload-policies/{purpose}`; `MediaVerificationJob`, `MediaCleanupJob`), its configuration (`app.media.*`, `app.jobs.media-verification.*`, `app.jobs.media-cleanup.*`, `app.limits.upload-policy.*`), the `media_files` and `upload_policies` changesets, its tests against an S3-compatible server in Testcontainers (the endpoint tests run against the first `UploadPurpose`, so without the example they are **skipped until the project declares one**, crud-gen Step 5); rules in house-review `media.md`. An entity's attachments are generated by crud-gen (SKILL.md Step 5) |
| `example` | The golden `todo` and `planning` modules (changesets 0002–0006 and 0011; 0010 with `media`), for study only; not copied into real projects |

## 2. Adapt
- The application class gets `@ConfigurationPropertiesScan`; delete Initializr's
  `application.properties` (the copied `application.yml` replaces it) and its
  `<App>ApplicationTests` (a bare `@SpringBootTest` without the test base's Testcontainers: it starts
  a second context with no database and fails the build).
- `spring.application.name`: everything else derives from it (error URNs, pool name, JDBC
  `ApplicationName`, JWT issuer, the database name, logs, metrics tag). `.env.example`'s `DB_NAME`
  (which `compose.yaml` creates) and its commented `JWT_ISSUER` say `reference`: set them to the
  same name.
- Changesets: the copied ones keep the reference's numbers, with gaps for pieces not copied:
  sign-in 0001; metadata 0008, plus 0007 (the ADMIN role) with sign-in; media 0009, plus 0007 with
  sign-in and without metadata; example 0002–0006 and 0011, plus 0010 with media. A project's own
  changesets continue after the highest copied number.
- The answers of step 0: signup policy, external IdP (`SecurityConfig`), client-IP mode,
  `app.limits.*` values, pool and admission sizes (admission ≥ 2× pool, checked at startup).
- `ErrorCode`: remove the sign-in codes if `sign-in` wasn't copied. Constraint mappings live in each
  module's `ConstraintErrorCodes` bean (`UserConstraintErrorCodes`, ...), which the handler
  aggregates, so they come and go with their module.
- The ArchUnit rules' module list comes from the profile.
- `docs/runbook.md`: the deployment's own commands (how an instance is stopped, how a release is
  rolled back) where it says `docker`; `<app>` stays a placeholder for `spring.application.name`.
- With `metadata` but without `example`, `MetadataKeys.byName`'s list of fixed keys is empty (its
  comment stays): the project's first fixed key goes there; per-context keys never do.
- With `media`: set `app.media.bucket` and `app.media.region` (`app.media.endpoint` stays empty for
  AWS, the server's URL for another S3-compatible store); check `app.media.read-url-expiry`
  against the lifetime of the deployment's credentials (MEDIA-11). Credentials come from the SDK's
  default provider chain, never from configuration. Without `example`, `UploadPurpose` starts with
  the project's own purposes (step 0) and each gets its `upload_policies` seed row in a changeset.
  The bucket itself is a runbook step for the owner (MEDIA-13: Block Public Access, owner-enforced
  ownership, encryption, CORS for PUT with `Content-Type` and `Content-Disposition`, a
  least-privilege IAM role, a budget alarm); add it to `docs/runbook.md`.
- `.env.example`: every required variable (`DB_USER`, `DB_PASSWORD`, `JWT_SECRET`,
  `PERSONAL_DATA_PEPPER`, ...).

## 3. Dependencies
Start the pom from `reference-app/pom.xml` (its dependencies, the annotation processor and the
plugins), keeping only the approved pieces. The PostgreSQL driver is **compile** scope (not
Initializr's `runtime`): constraint names and the Liquibase connection use its classes. The script prints the dependencies each piece needs, every one the reference pom has (`verify.sh` checks the lists against it). Propose them (with versions and Boot
compatibility) and add the approved ones. Also propose `micrometer-registry-prometheus` with the
`metricsAuth` decision; without it nothing can scrape the metrics. With `media`:
`software.amazon.awssdk:s3` through the AWS SDK's BOM (`software.amazon.awssdk:bom`), and the test
image `versity/versitygw` (the version the reference pins) for the S3-compatible server in tests.

## 4. Verify
1. Build and test (`{{buildCommand}}`). **Check the test count is what you expect**: a test class
   Surefire doesn't pick up (named `*Rules` instead of `*Test`) passes silently by not running. `ErrorCodeContractTest` runs two cases per `ErrorCode`, so every added code adds two.
2. The project-wide tests exist once: `lockedRowAnswers503` (a write against a row another
   transaction locked: 503 `DATABASE_CONTENTION` + `Retry-After`, row unchanged; ERR-03) and
   `apiDocsShowTheConfiguredLimits` (the configured `maxLength` in the OpenAPI schema; REST-09).
   The reference has them in `TodoIntegrationTest` and `LimitsIntegrationTest`; without the
   `example` piece, write them for the project's first entity (grep for the names first). With
   `media` and no purpose yet, Surefire reports the media endpoint tests as skipped: expected.
3. Review only what step 2 adapted (the application class, the changed configuration, `ErrorCode`,
   `SecurityConfig` for an external IdP, the runbook): `/house-review <those files>`. The unmodified
   copy is the reference, which `verify.sh` builds and tests in the skills repository; reviewing
   the copied files again finds nothing new. Expected: no findings outside open business decisions.
4. Report what was copied, what was skipped (with the differences proposed), and the decisions.
