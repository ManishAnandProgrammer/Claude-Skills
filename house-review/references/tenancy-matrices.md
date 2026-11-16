# Tenancy test matrices

The two matrices house-review TEN-08 and TEN-34 point to: every path that issues a token against every
change of access, and every per-tenant seed against every tenant status. Each cell names its test; a new
path, state or seed adds its row or column, and a test per cell, in the change that adds it. Tests are in
the tenancy overlay unless named otherwise.

## Every path that issues a token

Each path re-reads what the token stands for from the database at the issue; none trusts the presented
token beyond its own validity: a fix that reaches one path and not another leaves the hole open.

| Path | What it reads and checks at the issue | Tests |
|---|---|---|
| Login (`AuthService.login` → `TokenService.issue`) | the password against the account's row; the account share-locked and ACTIVE (403 `AUTH_ACCOUNT_SUSPENDED`, after a matching password only); the person's memberships (`forLogin`: the most recently used one whose tenant is ACTIVE; none: 403 `TENANT_MEMBERSHIP_REQUIRED`); the tenant ACTIVE (403 `TENANT_NOT_ACTIVE`); role and session version from that membership row | `TenantPlanesIntegrationTest.accountWithoutMembershipIsRefused`, `suspendedTenantGetsNoTokens`, `TokenPathIntegrationTest`, `tokensCarryTheTenant`; reference `AuthIntegrationTest.loginRefusalsLookTheSame` |
| Refresh (`TokenService.refresh`) | the refresh token's row (revoked, expired, reused: 401 `AUTH_INVALID_REFRESH_TOKEN`, a reuse revokes every session); the account share-locked and ACTIVE before the token is spent (403 `AUTH_ACCOUNT_SUSPENDED`); the membership of the token's tenant (gone: 404 `MEMBERSHIP_NOT_FOUND`); the token issued after that membership was created (a removal and re-add: 401); the tenant ACTIVE; role and session version read again after the rotation | `TokenPathIntegrationTest`, `aReAddedMembersOldSessionStaysEnded`; `TenantPlanesIntegrationTest.suspendedTenantGetsNoTokens`, `tokenIssuesReadTheCurrentRole`; `TokenPathIntegrationTest`, `aPromotionRefreshesIntoTheHigherRole`; reference `AuthIntegrationTest.reusingARefreshTokenRevokesEverySession`, `expiredRefreshTokenIsRefused` |
| Switch tenant (`TokenService.switchTenant`) | the refresh token's row, as for a refresh; the account share-locked and ACTIVE (403 `AUTH_ACCOUNT_SUSPENDED`); the membership of the token's **own** tenant still there and created before the token (else 401 `AUTH_INVALID_REFRESH_TOKEN`: a removed member's old session switches nowhere, re-added or not); the target membership (404 `MEMBERSHIP_NOT_FOUND`) and its tenant ACTIVE (403); role and session version from the target membership row | `TenantMemberIntegrationTest.aRemovedMembersOldSessionDoesNotSwitch`; `TenantPlanesIntegrationTest.switchTenantMovesToAnotherMembership`, `switchToAForeignTenantIs404`, `TokenPathIntegrationTest`, `tokenIssuesReadTheCurrentRole` |
| Impersonation start (`ImpersonationService.impersonate` → `TokenService.issueImpersonation`) | the staff row re-read (deactivated: 401 `AUTHENTICATION_REQUIRED`); the tenant ACTIVE; the impersonated membership read now (404 `MEMBERSHIP_NOT_FOUND`), its role and session version; the support policy (403 `SUPPORT_ACCESS_DENIED`), audited before it is asked; the member's account share-locked and ACTIVE (403 `AUTH_ACCOUNT_SUSPENDED`) | `ImpersonationIntegrationTest.impersonationTokenWorksOnTheTenantPlane`, `impersonateANamedMember`, `refusingPolicyBlocksIssuance`; `SessionVersionTenancyIntegrationTest.anImpersonationEndsWithTheMembersRoleChange`; `TokenPathIntegrationTest`; `TokenPathIntegrationTest` |
| Impersonation stop | no endpoint: the token is short-lived (`app.impersonation.token-ttl`) and has no refresh token; with the session version the member's access change ends it at once | as above |
| Platform login (`PlatformAuthService.login` → `TokenService.issuePlatform`) | the password against the staff row; the row active (deactivated: 401 `AUTH_INVALID_CREDENTIALS`, as an unknown email); the role from that row; no refresh token, so staff sign in again after one lifetime | `PlatformTenantIntegrationTest.platformLoginIsForStaffOnly`; `TokenPathIntegrationTest` |
| Each platform request (`ActiveStaffFilter`, the platform chain) | the staff row by primary key, whatever the session-version piece: a deactivated member's token is 401 `AUTHENTICATION_REQUIRED` from their next request on | `TokenPathIntegrationTest` |
| Password change (`AuthService.changePassword` → `TokenService.replacePassword`) | the current password against the account's row (400 `USER_PASSWORD_INCORRECT`, rate limited as the account's login); an impersonation token refused (403 `ACCESS_DENIED`); then, in one transaction, the hash written (the account's row first), every refresh token revoked (`PASSWORD_CHANGED`), every membership's session version raised, the account read again ACTIVE, and tokens for the token's **own** tenant (not login's most recently used) | reference `AccountLifecycleIntegrationTest.aPasswordChangeEndsEverySession`, `aWrongCurrentPasswordChangesNothing`, `wrongCurrentPasswordsAreLimitedLikeLogins`; `PlatformUserStatusIntegrationTest.aPasswordChangeStaysInTheDevicesTenant`, `anImpersonationChangesNoPassword` |
| Account suspension, reactivation (`AccountStatusService.changeStatus`, staff: `PATCH /platform/users/{id}/status`) | issue nothing: the suspension updates the account's row first, then revokes every refresh token (`SUSPENDED`) and raises every membership's session version, in one transaction; the last ACTIVE ADMIN of an open tenant is refused (409 `TENANT_MEMBER_LAST_ADMIN`, the tenant locked as a member change locks it). A reactivation revives nothing | reference `aSuspensionEndsEverySession`, `aReactivationRevivesNoSession`, `aSuspensionRacingARefreshLeavesNoToken`, `aSuspensionRacingALoginLeavesNoToken`; `PlatformUserStatusIntegrationTest.aTenantKeepsAnActiveAdmin`, `onlyStaffChangeAnAccountsStatus` |
| Tenant registration, approval, activation | issue no token: registration answers 202 without one, the owner signs in afterwards through login (which checks the tenant ACTIVE) | `TenantRegistrationIntegrationTest.registrationWaitsForApproval` (the owner's login is 403 `TENANT_NOT_ACTIVE` until approved) |
| Sign out everywhere (session-version piece) | issues nothing: revokes every refresh token and raises every membership's version in one transaction | `SessionVersionTenancyIntegrationTest.signingOutEverywhereEndsEveryTenant` |

Staff are rows an operator writes, and one with audit history can't be deleted (`fk_platform_audit_actor_id`), so an operator sets `deactivated_at`; the
platform login, every platform request and every impersonation issue re-read the row, so a platform token
is never trusted for its lifetime.

## Every state × every token path

What each change of access does to each path, with tenancy (the reference, without tenancy, has the
suspended-account row alone, with its login, refresh and request cells). "SV" is the session-version piece;
without it an access token already out lives out its lifetime (15 minutes, REST-15), and every write that
grants or keeps access re-checks the caller in the database (rung 3). Every cell is a row of one parameterized
test, `TokenPathIntegrationTest` (the tenancy overlay's; the reference's holds the suspended-account row), in
both session-version modes; the names in the cells are the tests beside it (races, a re-added member, refused
switches).

| State | Login | Refresh | Switch tenant | Impersonation | Each request |
|---|---|---|---|---|---|
| Removed member | that tenant isn't picked; none left: 403 `TENANT_MEMBERSHIP_REQUIRED` (`TenantPlanesIntegrationTest.accountWithoutMembershipIsRefused`) | 404 `MEMBERSHIP_NOT_FOUND`; re-added, 401 (re-added: `TenantMemberIntegrationTest.aReAddedMembersOldSessionStaysEnded`) | 401 from the old session, re-added or not (`aRemovedMembersOldSessionDoesNotSwitch`) | 404 `MEMBERSHIP_NOT_FOUND`  | SV: 401 `SESSION_VERSION_STALE` (re-added: `SessionVersionTenancyIntegrationTest.aReAddedMembersOldTokenStaysRefused`); else until expiry, member changes 403 (`aRemovedAdminCantComeBack`) |
| Demoted member | the new role (`tokenIssuesReadTheCurrentRole`) | the new role (`tokenIssuesReadTheCurrentRole`) | the target's role read now | the member's role read now (`anImpersonationEndsWithTheMembersRoleChange`) | SV: 401, the refresh the new role (`aDemotionRefreshesIntoTheLowerRole`); else the old role until expiry, member changes 403 (`aDemotedAdminsTokenManagesNoMore`) |
| Suspended account | 403 `AUTH_ACCOUNT_SUSPENDED` after a matching password (racing: `AccountLifecycleIntegrationTest.aSuspensionRacingALoginLeavesNoToken`) | 401 (revoked); one not revoked, 403 (`aSuspendedAccountsLiveRefreshTokenIsRefused`; racing: `aSuspensionRacingARefreshLeavesNoToken`) | 401 (revoked); one not revoked, 403 (`PlatformUserStatusIntegrationTest.aSuspendedMemberSwitchesNowhere`) | 403 `AUTH_ACCOUNT_SUSPENDED` | SV: 401 (every device's: `aSuspensionEndsEverySession`); else until expiry, and it issues nothing (a password change 403) |
| Deactivated staff | platform login 401 `AUTH_INVALID_CREDENTIALS` | none: platform tokens have no refresh token | none: staff have no tenant | 401 | every platform request 401, with or without SV; another staff member works on (`StaffDeactivationIntegrationTest`) |
| Suspended tenant | another ACTIVE tenant; none: 403 `TENANT_NOT_ACTIVE` (none: `TenantPlanesIntegrationTest.suspendedTenantGetsNoTokens`) | 403 `TENANT_NOT_ACTIVE`, the token kept for a reactivation (`suspendedTenantGetsNoTokens`) | into it 403, the session kept (`aRefusedSwitchKeepsTheSession`) | 403 `TENANT_NOT_ACTIVE` (`ImpersonationService`, first check) | SV: 401 for every member there (`aSuspensionEndsEveryMembersTokens`); else until expiry |

A new state or path adds its rows to `TokenPathIntegrationTest`, one per cell, in the change that adds it (TEN-08).

## Tenant lifecycle × seeding

Every way per-tenant data is seeded, against every tenant status. The cells a fresh database can't reach
run on the upgraded one (crud-gen tests.md, *Upgrading a used database*: the previous release's database
with tenants in every status, then the new changesets).

**The convention (house-review TEN-34, TEN-23): a per-tenant seed is create-if-absent and reaches every
tenant row, whatever its status.** Row mode: the backfill changeset reads `FROM tenants`, never filtered by
status, and continues from the tenant's own data (a counter after the tenant's highest number), beside a
`TenantProvisioned` listener that creates the row only when missing; a row the code can't work without is
also created on first use (the create's `Counters.createIfAbsent`). Schema per tenant: the seed is in a
tenant changeset, which every schema runs, at its provisioning and at each deploy. Housekeeping jobs visit
every tenant that holds data (`Tenants.idsHoldingData`), work done for users only the ACTIVE ones
(`activeIds`). An approval during a rolling deploy, by an instance without the new seed, is the one case
the code doesn't close for a row it can work without: the runbooks say to approve once the deploy is
through.

Row mode (tests in the overlays; *Upgrade* is `UpgradeFromPreviousReleaseIntegrationTest`, *Backfill*
`TodoSeedsBackfillIntegrationTest`):

| Seed (how it reaches a tenant) | PENDING | ACTIVE | SUSPENDED | REJECTED, CLOSED | Approved by an old instance during the rollout |
|---|---|---|---|---|---|
| Upload policy `TODO_ATTACHMENT`: `0010`'s backfill + `TodoAttachmentPolicySeed` | the backfill gives it, the approval keeps it (*Backfill* `aPendingTenantThroughTheBackfillsIsActivated`); a tenant registered after the backfill gets it from the listener (*Upgrade* `thePendingTenantIsActivatedAfterTheUpgrade`) | the backfill (*Backfill* `existingTenantsGetThePolicy`); new tenants the listener (every test tenant) | the backfill (`FROM tenants`) | the backfill; never read | none until the backfill's insert for that tenant (runbook section 4) |
| List defaults `TODO_LIST_DEFAULTS`: `0014`'s backfill + `TodoListDefaultsSeed` | as the policy (*Backfill*, *Upgrade* as above) | as the policy | as the policy | as the policy | as the policy |
| Display-number counter `TODO_NUMBER`: `0026`'s counters backfill (after the tenant's highest number) + `TodoNumberSeed` + the create's `createIfAbsent` | the backfill gives it (0), the approval keeps it, the first todo is #1 (*Backfill*, *Upgrade*) | after its highest (*Upgrade* `everyTenantIsNumberedOnItsOwn`, *Backfill* `existingTenantsGetTheirCounter`) | after its highest; reactivated, the next todo goes on (*Upgrade* `theSuspendedTenantGoesOnAfterItsHighestNumber`) | after its highest (*Upgrade* `everyTenantIsNumberedOnItsOwn`) | created on first use (*Backfill* `aTenantWithoutItsCounterNumbersFromOne`) |
| The rows already there: `0026` numbers each tenant's todos 1 to n in id order | none to number | numbered (*Upgrade* `everyTenantIsNumberedOnItsOwn`) | numbered (same) | numbered (same) | a changeset: no instance runs it |
| `members`: written with the membership in one transaction (`MembershipService`), `0017`'s backfill from the memberships | the owner's at registration (`TenantCreated`) | every add; a removal marks the row left, its records stay (`TenantMemberIntegrationTest`, *Upgrade* `aMemberWhoLeftIsNotNamed`) | kept | kept | one transaction with the membership: no gap |
| Jobs' tenant loops: media verification (`activeIds`), media cleanup (`idsHoldingData`) | neither (no data) | both | cleanup only: verification waits, nothing reads the files until ACTIVE | CLOSED: cleanup; REJECTED: neither | a job, no seed (`MediaJobsPerTenantTest`) |

Schema per tenant (tests in the schema overlay; *Upgrade* as above, *Migrations*
`TenantMigrationsIntegrationTest`):

| Seed | PENDING | ACTIVE | SUSPENDED | REJECTED, CLOSED | During the rollout |
|---|---|---|---|---|---|
| Tenant changesets (`0010`'s policy, `0014`'s defaults, `0017`'s members, `0026`'s numbers and counter) | no schema; provisioning on approval runs the whole changelog (*Migrations* `provisioningCreatesMigratesAndActivates`, *Upgrade* `thePendingTenantIsProvisionedAfterTheUpgrade`) | the deploy-time run applies the new changesets over its rows (*Upgrade* `theDeployMigratedEverySeededSchema`, `everySchemaIsNumberedOnItsOwn`) | migrated too: every provisioned schema is (same) | CLOSED migrated; REJECTED never gets a schema (*Migrations*, *Upgrade*) | a schema an old instance provisions after the last new instance started keeps the old release until a run reaches it (runbook section 5) |
| The owner's `members` row | written on `TenantProvisioned` (`MembershipService.addOwnerToTenantData`) | `0017` copied the memberships | as ACTIVE | as ACTIVE | — |

Without tenancy a seed is a changeset (`0010`, `0014`, `0026` with the rows' numbers), and the reference's
*Upgrade* checks it over the seeded rows (`everySeededTodoIsNumberedInOrder`: red when the counter starts
at 0 instead of after the highest, which an empty database never shows).
