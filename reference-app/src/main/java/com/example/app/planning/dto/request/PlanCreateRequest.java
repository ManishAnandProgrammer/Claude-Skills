package com.example.app.planning.dto.request;

import com.example.app.common.limits.EarliestDate;
import com.example.app.common.limits.LatestDate;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Schema(description = "Days to set aside for one of your todos; your plans never share a day")
public record PlanCreateRequest(
        @Schema(description = "One of your todos", example = "0190a9c2-7d10-7b2c-8e3f-1a2b3c4d5e6f")
        @NotNull
        UUID todoId,

        @Schema(description = """
                The first day (yyyy-MM-dd), in your own calendar. Planned days may lie ahead or behind, so \
                the only bounds are a configured earliest and latest date.""", example = "2026-11-20")
        @NotNull @EarliestDate("plan.dates") @LatestDate("plan.dates")
        LocalDate startsOn,

        @Schema(description = "The last day, inclusive: a one-day plan starts and ends on the same day", example = "2026-11-22")
        @NotNull @EarliestDate("plan.dates") @LatestDate("plan.dates")
        LocalDate endsOn) {

    /** A range in a body is checked like a range filter: inverted is a 400 naming dateRangeOrdered. */
    @AssertTrue(message = "{plan.date-range}")
    @Schema(hidden = true)
    public boolean isDateRangeOrdered() {
        return Objects.isNull(startsOn) || Objects.isNull(endsOn) || !endsOn.isBefore(startsOn);
    }
}
