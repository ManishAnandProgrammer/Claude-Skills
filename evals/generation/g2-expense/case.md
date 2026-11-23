# G2 Expense: the caller's own records, with state rules

**Pattern:** an owned root (`OWN` reach); a status workflow whose rules decide edit, submit and withdraw;
`allowedActions` with every reason; a cursor list at the bare `/expenses` (one reach keeps the bare collection, REST-08); a recorded day with `@NotAfterToday`.
Golden twins: `Plan` (`PlanStatus.canBecome`, `Plan.changeStatus`, `PlanPolicy`, `PlanAllowedActionsResponse`, a
frozen final status), `Todo` (`TodoPolicy`, the cursor list), `TimeEntry` (`spentOn` with `@NotAfterToday`).

**Run:** `/crud-gen src/main/java/com/acme/g2/expense/domain/Expense.java` on a `core,sign-in` copy whose baseline
holds `Expense`, `ExpenseStatus` and `0100-expenses.sql` (fixture/).

## Business rules

- `Expense` is the aggregate root of a module of its own, `expense`. Nothing references an expense.
- An expense is **owned**: it belongs to the person who created it (`ownerId`, the caller), and only they see it.
  Another person's expense is 404, as if it didn't exist.
- **Permissions** (Level 1), in a new menu area `EXPENSES`, every one of reach `OWN`, held by `USER` and `ADMIN` (an
  admin is an employee too): `EXPENSE_READ_OWN`, `EXPENSE_CREATE_OWN`, `EXPENSE_EDIT_OWN` (the update and both status
  moves), `EXPENSE_DELETE_OWN`. Prerequisites: create, edit and delete need `EXPENSE_READ_OWN`.
- **Fields:**
  - `amount`: required, more than 0 and at most a configured maximum of 10000.00, two decimal places at most;
  - `currency`: required ISO 4217 code, exactly three upper-case ASCII letters (`EUR`), stored as sent (a request
    with lower-case letters is a 400, not normalised);
  - `spentOn`: required, the day the money was spent: never after today (`@NotAfterToday`), never before
    2000-01-01;
  - `description`: required text, at most 500 characters; not searched;
  - `status`: `DRAFT` at the create, never sent by the create.
- **The workflow:** `DRAFT` → `SUBMITTED` (submit) → `WITHDRAWN` (withdraw). The owner makes both moves.
  `WITHDRAWN` is final. A move the workflow doesn't list, the move to the current status included, is 409.
- **Frozen:** only a `DRAFT` is edited (PUT, a full replace of amount, currency, spentOn and description) or
  deleted. A `SUBMITTED` expense refuses both with 409 `EXPENSE_SUBMITTED`, a `WITHDRAWN` one with 409
  `EXPENSE_WITHDRAWN`.
- **allowedActions** on the detail and on every list row: `edit`, `delete`, `submit`, `withdraw`, each with every
  reason it is refused.
- **The list** is cursor-paged at `GET /expenses` (the owner's own, reach `OWN`, the only reach it is listed at, so the bare
  collection, REST-08; there is no list of everyone's expenses). Its consumer is the person's own screen, scrolled. Sorts: `SPENT_ON` (the latest day first, the
  default) and `NEWEST` (the latest created first). Filters: `status`, and a `spentOnFrom` / `spentOnTo` range
  (both inclusive, each bounded like `spentOn`).
- **No dropdown:** no other record picks an expense.
- **Retention:** expenses are kept (a business record); no purge.
- **Personal data:** none besides the owner's id.

## Expected outcomes

The gates decide the verdict (README.md); a reader checks by hand in the work copy:
- `GET /expenses`, `GET /expenses/{id}`, `POST /expenses`, `PUT /expenses/{id}`, `DELETE /expenses/{id}` and the
  moves (one `PATCH /expenses/{id}/status` with the target, as golden `Plan`'s, since one permission covers every
  move), each `@Requires` its `EXPENSE_*_OWN`;
- `ExpenseStatus.canBecome`, the entity's change method refusing the rest with 409
  `EXPENSE_STATUS_CHANGE_INVALID`; `ExpensePolicy` with `edit`, `delete`, `changeStatus` and `listScope`, the frozen
  status first after the version check; its table test;
- `ExpenseAllowedActionsResponse` of four `AllowedAction`s made through `AllowedActionMapper` with
  `EXPENSE_EDIT_OWN` / `EXPENSE_DELETE_OWN`, and an `AllowedActionContract` row per flag;
- `@NotAfterToday` and `@EarliestDate("expense.spent-on")` on `spentOn` and on the range's bounds, the range's
  `@CrossField(reportedOn = "spentOnTo")`; the amount's maximum in `limits.yml`;
- one index per order in `0100-expenses.sql` (not applied: edited); no filter's own index (unattended: the rules ask
  for none, so none, recorded as assumed, DATA-10);
- the integration tests of references/tests.md, the status tests (every allowed and refused move), the frozen
  tests, the allowed-actions tests.
