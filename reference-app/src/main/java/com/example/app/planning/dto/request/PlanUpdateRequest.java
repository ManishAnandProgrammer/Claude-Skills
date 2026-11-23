package com.example.app.planning.dto.request;

import com.example.app.common.limits.ConfiguredMax;
import com.example.app.common.limits.EarliestDate;
import com.example.app.common.limits.LatestDate;
import com.example.app.common.validation.CrossField;
import com.example.app.common.validation.DateRanges;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

@Schema(description = "A plan's new days, both ends at once; your plans never share a day")
public record PlanUpdateRequest(
        @Schema(description = "The new first day (yyyy-MM-dd), between a configured earliest and latest date", example = "2026-11-24")
        @NotNull @EarliestDate("plan.dates") @LatestDate("plan.dates")
        LocalDate startsOn,

        @Schema(description = "The new last day, inclusive; a plan spans at most a configured number of days", example = "2026-11-25")
        @NotNull @EarliestDate("plan.dates") @LatestDate("plan.dates")
        LocalDate endsOn,

        @Schema(description = "The version you last read; a stale one is refused with 409", example = "0")
        @NotNull
        Long version) {

    @CrossField(reportedOn = "endsOn", message = "{plan.date-range}")
    @Schema(hidden = true)
    public boolean isDateRangeOrdered() {
        return DateRanges.isOrdered(startsOn, endsOn);
    }

    @ConfiguredMax(value = "plan.max-days", reportedOn = "endsOn")
    @Schema(hidden = true)
    public Long getLengthInDays() {
        return DateRanges.lengthInDays(startsOn, endsOn);
    }
}
