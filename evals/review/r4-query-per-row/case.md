# R4: a list that runs a query per row

**Defect:** `PlanService.withTodos` reads each row's todo with `Todos.findOwnedTodoView`, one statement per plan on the
page, instead of the page's one `getOwnedTodoViews` lookup. The list's statement-count test fails.

**Rule:** DATA-11 (no N+1); also accepted: PERF-01, TEST-06, ARCH-15 (another module's data for the whole page in one
facade call).

**Passes when** one of those rules is reported on `planning/service/PlanService.java`.

## Commit message

Plans: read each plan's todo where the row is mapped
