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
2. **Sign-in:** the app issues its own tokens (the `sign-in` piece), an external identity
   provider (copy `core` only, then adapt `SecurityConfig` to the provider's issuer and JWK set),
   or none.
3. **Signup policy** (with sign-in): email-verified signup (answers 202 and never reveals an
   existing account; needs an email-sending choice) or immediate accounts (simpler; signup reveals
   whether an email is registered: record the accepted risk).
4. **Breached-password check** (Spring Security's `CompromisedPasswordChecker`, an outbound
   call; fail-open or fail-closed), `tokenRevocation`, the client-IP log mode, `metricsAuth` (how
   the metrics scrape is secured), multi-tenant or not.
5. **Packaging:** a Dockerfile (JDK or jlink runtime with `jcmd` and JFR, non-root, the CFG-08
   flags) or Boot's `build-image` (record which flags Paketo sets).
6. **Rate limiting:** the rate-limiting design is pending a dedicated session; until then the
   `sign-in` piece's per-IP and per-account sign-in limits.

Write `.claude/crud-profile.md` and `.claude/review-profile.md` from their templates.

## 1. Copy the pieces
```bash
~/Documents/claude-skills/scripts/bootstrap-copy.sh --target <project> --package <rootPackage> --with core,sign-in --dry-run
~/Documents/claude-skills/scripts/bootstrap-copy.sh --target <project> --package <rootPackage> --with core,sign-in
```
| Piece | What it brings |
|---|---|
| `core` | `common/` (error contract with `ErrorCode`, mapper, `AppException`, transient-failure 503s; wrappers `Object`/`List`/`Map`/`Pagination`/`Cursor`; `PageFilter`, `CursorFilter`, `Paging`, `OffsetPages`, `KeysetPages`, `TextSearch`; `Limits` with `@MaxLength` / `@ConfiguredMax` and their OpenAPI customizer; the dropdown types; `AuditableEntity`; logging context and filters; admission control; `ProblemTypes`, `ProblemResponses`, `RetryAfterProperties`; `CurrentUser`, `PersonalDataHasher`; `@MultiInstanceSafe`); `config/` (security, JWT validation, messages, auditing, scheduling, OpenAPI, startup checks, pool readiness, clock); `application.yml` + `config/*.yml` split by concern with `-dev`/`-prod` variants; logback; messages; the test base, ArchUnit and contract tests; `compose.yaml`, `.env.example` |
| `sign-in` | The `user` module (signup, login, refresh rotation with a grace window, logout, purge job), its changeset, the Redis rate limiter, its tests. **core's tests need it**: they sign users in |
| `example` | The golden todo module, for study only; not copied into real projects |

## 2. Adapt
- The application class gets `@ConfigurationPropertiesScan`; delete Initializr's
  `application.properties` (the copied `application.yml` replaces it).
- `spring.application.name`: everything else derives from it (error URNs, pool name, JDBC
  `ApplicationName`, JWT issuer, logs, metrics tag).
- The answers of step 0: signup policy, external IdP (`SecurityConfig`), client-IP mode,
  `app.limits.*` values, pool and admission sizes (admission ≥ 2× pool, checked at startup).
- `ErrorCode`: remove the sign-in codes if `sign-in` wasn't copied; `GlobalExceptionHandler`'s
  `CONSTRAINT_CODES` keeps only constraints that exist.
- The ArchUnit rules' module list comes from the profile.
- `.env.example`: every required variable (`DB_USER`, `DB_PASSWORD`, `JWT_SECRET`,
  `PERSONAL_DATA_PEPPER`, ...).

## 3. Dependencies
The script prints the dependencies each piece needs. Propose them (with versions and Boot
compatibility) and add the approved ones. Also propose `micrometer-registry-prometheus` with the
`metricsAuth` decision; without it nothing can scrape the metrics.

## 4. Verify
1. Build and test (`{{buildCommand}}`). **Check the test count is what you expect**: a test class
   Surefire doesn't pick up (named `*Rules` instead of `*Test`) passes silently by not running.
2. Run `/house-review all`. Expected: no findings outside open business decisions.
3. Report what was copied, what was skipped (with the differences proposed), and the decisions.
