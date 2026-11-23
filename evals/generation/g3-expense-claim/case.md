# G3 ExpenseClaim: two parties and finance, three reaches

**Pattern:** one record read at three reaches (`OWN` the claimant, `ASSIGNED` the approver the claim names, `ALL`
finance); a detail several reaches read (`@Requires` any-of, the policy picking the reach); one action endpoint per
move, each with its own permission (REST-03); a list per reach (`/mine`, `/team`, bare); `allowedActions` with
`ACCESS_DENIED` for a permission the caller doesn't hold; a rule a date decides against today, read from
`BusinessCalendar.today()`. A new built-in role (Level 1). Golden twins: `TodoPolicy` and `TodoRequesterIntegrationTest`
(two parties), `PlanPolicy` and `PlanAllowedActionsIntegrationTest` (flags with every reason), `PlanStatus` (the
workflow), `RequiresAnyOfIntegrationTest` (any-of), `todo.api.Todos` / `user.api.Users` (a person checked through a
facade), `user.api.NamedParty` / `PermissionHolders` and `TodoRequesterPickerIntegrationTest` (a party who acts, its picker), `BusinessCalendar` (core).

**Run:** `/crud-gen src/main/java/com/acme/g3/claim/domain/ExpenseClaim.java` on a `core,sign-in` copy whose
baseline holds `ExpenseClaim`, `ExpenseClaimStatus` and `0100-expense-claims.sql` (fixture/). The roles piece isn't
needed: a new built-in role is a `Role` constant, a changeset widening `ck_users_role` and the test base's
`newFinance()` (bootstrap.md, *A new built-in role*, Level 1).

## Business rules

- `ExpenseClaim` is the aggregate root of a module of its own, `claim`. Nothing references a claim.
- **The parties:**
  - the **claimant** (`ownerId`, the caller at the create) creates, edits, submits and deletes their own claims;
  - the **approver** (`approverId`): the person the claimant names at the create as the one who decides it: an
    active account holding `EXPENSE_CLAIM_APPROVE_ASSIGNED` now, other than the claimant (the claimant: 400
    `EXPENSE_CLAIM_APPROVER_IS_OWNER`; no account: 404 `USER_NOT_FOUND` through `Users`; suspended, or without the
    permission, a `FINANCE` account for one: 409 `EXPENSE_CLAIM_APPROVER_INELIGIBLE` through `PermissionHolders`).
    The party is a `user.api.NamedParty` constant, `EXPENSE_CLAIM_APPROVER`, named by `EXPENSE_CLAIM_CREATE_OWN`
    and `EXPENSE_CLAIM_EDIT_OWN`. The approver may be changed by the update while the claim is a `DRAFT`, and
    by the reassignment below while it is `SUBMITTED`, each checked again only when it changes. The approver reads
    the claims that name them, and approves or rejects them;
  - **finance** reads every claim, and does nothing else.
  Anyone else gets 404 for a claim, as if it didn't exist; a party trying an action it may not take on a claim it
  can read gets 403 `EXPENSE_CLAIM_NOT_YOURS`.
- **Roles** (Level 1): a new built-in role `FINANCE`. `USER` is both a claimant and an approver (any account can
  be named approver); `FINANCE` holds only the read of every claim; `ADMIN` holds what `USER` holds and the read of
  every claim.
- **Permissions**, in a new menu area `EXPENSE_CLAIMS`:
  - `EXPENSE_CLAIM_READ_OWN`, `EXPENSE_CLAIM_CREATE_OWN`, `EXPENSE_CLAIM_EDIT_OWN` (the update),
    `EXPENSE_CLAIM_SUBMIT_OWN`, `EXPENSE_CLAIM_DELETE_OWN`: the claimant's (reach `OWN`; `USER`, `ADMIN`);
  - `EXPENSE_CLAIM_READ_ASSIGNED`, `EXPENSE_CLAIM_APPROVE_ASSIGNED`, `EXPENSE_CLAIM_REJECT_ASSIGNED`: the
    approver's (reach `ASSIGNED`: assigned by the claim's `approverId`; `USER`, `ADMIN`);
  - `EXPENSE_CLAIM_READ` (reach `ALL`): finance's (`FINANCE`, `ADMIN`).
  Prerequisites: each `_OWN` action needs `EXPENSE_CLAIM_READ_OWN`, each `_ASSIGNED` action
  `EXPENSE_CLAIM_READ_ASSIGNED`. No READ is another READ's prerequisite.
- **Fields:** `amount` (more than 0, at most a configured 10000.00, two decimals at most), `currency` (ISO 4217,
  three upper-case ASCII letters, stored as sent), `spentOn` (the day spent: never after today, `@NotAfterToday`,
  never before 2000-01-01), `description` (required, at most 500 characters, not searched), `approverId`; at a
  rejection `rejectionReason` (required with a rejection, at most 500 characters); `decidedAt` the moment of the
  approval or rejection, the server's clock.
- **The workflow:** `DRAFT` → `SUBMITTED` (submit, the claimant) → `APPROVED` (approve, the approver) or
  `REJECTED` (reject, the approver, with the reason). `APPROVED` and `REJECTED` are final. A move the workflow
  doesn't list, the move to the current status included, is 409 `EXPENSE_CLAIM_STATUS_CHANGE_INVALID`.
- **One endpoint per move**, each with its own permission: `PATCH /expense-claims/{id}/submit`
  (`EXPENSE_CLAIM_SUBMIT_OWN`), `PATCH /expense-claims/{id}/approve` (`EXPENSE_CLAIM_APPROVE_ASSIGNED`), `PATCH
  /expense-claims/{id}/reject` (`EXPENSE_CLAIM_REJECT_ASSIGNED`); each body holds the version and what the move takes
  (the reason for a rejection).
- **Frozen:** only a `DRAFT` is edited (PUT, a full replace of amount, currency, spentOn, description, approverId)
  or deleted; otherwise 409 `EXPENSE_CLAIM_SUBMITTED`, `EXPENSE_CLAIM_APPROVED` or `EXPENSE_CLAIM_REJECTED`.
- **A stale approver** (suspended, or who lost the permission after being named) can't decide: every move checks
  the caller's permission at the request. The claimant reassigns the claim while it is not final: `PATCH
  /expense-claims/{id}/approver` (`EXPENSE_CLAIM_EDIT_OWN`; the body holds `approverId` and the version), allowed
  on a `DRAFT` or `SUBMITTED` claim (a final one: 409 `EXPENSE_CLAIM_APPROVED` or `EXPENSE_CLAIM_REJECTED`), the
  new approver checked as at the create. Its `allowedActions` flag is `reassign`.
- **The date rule:** a claim is approved only while its `spentOn` is within the last 90 days of the organisation's
  calendar (`spentOn` on or after today minus 90 days, today from `BusinessCalendar.today()`); later it is 409
  `EXPENSE_CLAIM_TOO_OLD` (it can still be rejected). The 90 days are a module setting (a `Period`, `90d`). The
  business zone stays as configured (UTC): don't change it.
- **The detail** `GET /expense-claims/{id}` is read by all three parties: it `@Requires` any of the three READ
  permissions, and the policy decides which reach applies to the caller.
- **allowedActions** on the detail and on every list row: `edit`, `delete`, `submit`, `reassign`, `approve`, `reject`, each with
  every reason; a caller without the action's permission (finance looking at a claim) gets `allowed: false` with
  `ACCESS_DENIED` alone.
- **The lists**, cursor-paged, one per reach: `GET /expense-claims/mine` (the claimant's, `OWN`), `GET
  /expense-claims/team` (the claims naming the caller as approver, `ASSIGNED`), `GET /expense-claims` (every claim,
  finance, `ALL`). Consumers: each party's own screen, scrolled. Sorts: `NEWEST` (the default) and `SPENT_ON` (the
  latest day first). Filter: `status`. The team and finance lists show the claimant by id only (no names).
- **No dropdown** of claims. The approver is picked from the user module's picker, `GET
  /users/dropdown?for=EXPENSE_CLAIM_CREATE_OWN` (or `EXPENSE_CLAIM_EDIT_OWN` on the edit and the reassignment),
  which lists the accounts the `EXPENSE_CLAIM_APPROVER` constant accepts: no dropdown of the module's own.
- **Retention:** claims are kept (a business record); no purge.

## Expected outcomes

The gates decide the verdict (README.md); a reader checks by hand in the work copy:
- `ExpenseClaimPolicy` with `read` picking the reach, an action method per move (the party's rule, then
  `ExpenseClaimStatus.canBecome`), `listScope` per reach; its table test with each refusal;
- `@Requires({EXPENSE_CLAIM_READ_OWN, EXPENSE_CLAIM_READ_ASSIGNED, EXPENSE_CLAIM_READ})` on the detail; each list
  `@Requires` its reach's READ; each move's endpoint its own permission, and its flag built with that permission
  (an `AllowedActionContract` row per flag);
- `BusinessCalendar.today()` read once in the service and passed to the policy's approve rule (never
  `LocalDate.now(clock)`); the window a typed `Period` setting; a policy test that fixes the day;
- `Role.FINANCE` with `EXPENSE_CLAIM_READ`, a changeset widening `ck_users_role`, `newFinance()` in the test base;
- the approver checked through `Users` and `PermissionHolders.holds(id, NamedParty.EXPENSE_CLAIM_APPROVER.mustHold())`
  at the create, at an update that changes it and at the reassignment; the `NamedParty` constant the only user-module
  change; tests.md row 27e (a `FINANCE` approver refused, the picker leaving them out);
- the tests of references/tests.md, each party by each call (`TodoRequesterIntegrationTest`'s shape), the flags'
  `ACCESS_DENIED` for finance.
