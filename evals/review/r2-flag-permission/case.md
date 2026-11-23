# R2: a flag built with another permission than its endpoint's

**Defect:** `PlanService.allowedActions` builds the `markDone` flag with `Permission.PLAN_READ_OWN`, while the move's
endpoint (`PATCH /plans/{id}/status`) `@Requires(Permission.PLAN_EDIT_OWN)`: a reader without the edit permission
sees the button enabled.

**Rule:** AUTHZ-07 (`allowedActions`: each flag made with the permission its endpoint `@Requires`). The flags'
contract test (`AllowedActionContract`) may also fail.

**Passes when** house-review reports AUTHZ-07 on `planning/service/PlanService.java`.

## Commit message

Plans: the done flag follows the plan's read
