# R5: an update that drops the optimistic version check

**Defect:** `PlanService.update` (`PUT /plans/{id}`) no longer calls `plan.checkVersion(request.version())`: a client
holding a stale plan overwrites a newer reschedule, and the request's `version` is ignored. Its golden twins
(`changeStatus`, every other update) check it first.

**Rule:** DATA-06 (user actions on reviewed records take the version the user saw); also accepted: CONC-01.

**Passes when** one of them is reported on `planning/service/PlanService.java`.

## Commit message

Plans: reschedule reads the plan once
