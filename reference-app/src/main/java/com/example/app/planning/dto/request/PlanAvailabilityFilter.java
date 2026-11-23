package com.example.app.planning.dto.request;

import com.example.app.common.limits.ConfiguredMax;
import com.example.app.common.limits.EarliestDate;
import com.example.app.common.limits.LatestDate;
import com.example.app.common.validation.CrossField;
import com.example.app.common.validation.DateRanges;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Getter
@Setter
public class PlanAvailabilityFilter {

    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @EarliestDate("plan.dates") @LatestDate("plan.dates")
    @Schema(description = "The first day (yyyy-MM-dd), inclusive", example = "2026-11-16")
    private LocalDate from;

    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @EarliestDate("plan.dates") @LatestDate("plan.dates")
    @Schema(description = "The last day (yyyy-MM-dd), inclusive; at most a configured number of days after from",
            example = "2026-11-22")
    private LocalDate to;

    @CrossField(reportedOn = "to", message = "{plan.availability-range}")
    public boolean isRangeOrdered() {
        return DateRanges.isOrdered(from, to);
    }

    @ConfiguredMax(value = "plan.max-availability-days", reportedOn = "to")
    public Long getLengthInDays() {
        return DateRanges.lengthInDays(from, to);
    }
}
