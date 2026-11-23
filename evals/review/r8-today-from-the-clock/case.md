# R8: "today" from the clock in a date rule

**Defect:** a plan whose last day is past can't be rescheduled (409 `PLAN_ENDED`, documented), and the service decides
"past" with `LocalDate.now(clock)`, the server's UTC day, instead of the organisation's `BusinessCalendar.today()`.

**Rule:** DATA-37 (today for a rule a date decides is `BusinessCalendar.today()`); also accepted: JAVA-25. The rule
also sits outside `PlanPolicy`, so the reschedule flag disagrees with the write (AUTHZ-07), and it has no tests: both
expected noise.

**Passes when** DATA-37 or JAVA-25 is reported on `planning/service/PlanService.java`.

## Commit message

Plans: an ended plan keeps its days
