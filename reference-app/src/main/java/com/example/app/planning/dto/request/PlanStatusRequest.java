package com.example.app.planning.dto.request;

import com.example.app.common.limits.EarliestDate;
import com.example.app.common.limits.NotAfterToday;
import com.example.app.common.validation.CrossField;
import com.example.app.planning.domain.PlanStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.Objects;

@Schema(description = "The status to move a plan to")
public record PlanStatusRequest(
        @Schema(description = "DONE (with doneOn) or CANCELLED; both are final", example = "CANCELLED")
        @NotNull
        PlanStatus status,

        @Schema(description = """
                With DONE, and only then: the day it was done (yyyy-MM-dd) in your own calendar, at most today \
                (with a configured tolerance) and never before the plan's first day""", example = "2026-11-21")
        @EarliestDate("plan.dates") @NotAfterToday
        LocalDate doneOn,

        @Schema(description = "The version you last read; a stale one is refused with 409", example = "0")
        @NotNull
        Long version) {

    @CrossField(reportedOn = "doneOn", message = "{plan.done-on-with-done}")
    @Schema(hidden = true)
    public boolean isDoneOnGivenWithDoneOnly() {
        return Objects.isNull(status) || (status == PlanStatus.DONE) == Objects.nonNull(doneOn);
    }
}
