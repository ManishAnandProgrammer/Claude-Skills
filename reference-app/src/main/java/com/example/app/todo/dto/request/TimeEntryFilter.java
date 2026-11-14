package com.example.app.todo.dto.request;

import com.example.app.common.limits.ConfiguredMax;
import com.example.app.common.limits.ConfiguredMaxSize;
import com.example.app.common.limits.EarliestDate;
import com.example.app.common.limits.LatestDate;
import com.example.app.common.pagination.CursorFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Time entries grow with every working day, so this list is cursor-paged. Both ranges are inclusive;
 * an inverted one is a 400, and the minutes bounds take the values an entry can have.
 */
@Getter
@Setter
public class TimeEntryFilter extends CursorFilter {

    @ConfiguredMaxSize("cursor-page.max-size")
    @Schema(description = """
            Find by ids (comma-separated, at most the maximum batch size): unknown ids, or another todo's, \
            are simply absent. Absent or empty: no id filter.""")
    private List<@NotNull UUID> ids;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @EarliestDate("time-entry.spent-on") @LatestDate("time-entry.spent-on")
    @Schema(description = "Only entries spent on or after this day (yyyy-MM-dd); not after spentOnTo", example = "2026-11-01")
    private LocalDate spentOnFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @EarliestDate("time-entry.spent-on") @LatestDate("time-entry.spent-on")
    @Schema(description = "Only entries spent on or before this day (yyyy-MM-dd)", example = "2026-11-30")
    private LocalDate spentOnTo;

    @Min(1)
    @ConfiguredMax("time-entry.max-minutes")
    @Schema(description = "Only entries of at least this many minutes; not above maxMinutes", example = "30")
    private Integer minMinutes;

    @Min(1)
    @ConfiguredMax("time-entry.max-minutes")
    @Schema(description = "Only entries of at most this many minutes", example = "120")
    private Integer maxMinutes;

    @Schema(description = "Only billable (true) or non-billable (false) entries; absent for both")
    private Boolean billable;

    @NotNull
    @Schema(description = "Order of the list; a cursor only continues the order it came from", defaultValue = "SPENT_ON")
    private TimeEntrySort sort = TimeEntrySort.SPENT_ON;

    public boolean requireIdsPredicate() {
        return Objects.nonNull(ids) && !ids.isEmpty();
    }

    /** An inverted range is a mistake the client should hear about, not an empty list. */
    @AssertTrue(message = "{time-entry.spent-on-range}")
    public boolean isSpentOnRangeOrdered() {
        return Objects.isNull(spentOnFrom) || Objects.isNull(spentOnTo) || !spentOnFrom.isAfter(spentOnTo);
    }

    @AssertTrue(message = "{time-entry.minutes-range}")
    public boolean isMinutesRangeOrdered() {
        return Objects.isNull(minMinutes) || Objects.isNull(maxMinutes) || minMinutes <= maxMinutes;
    }

    public boolean requireSpentOnFromPredicate() {
        return Objects.nonNull(spentOnFrom);
    }

    public boolean requireSpentOnToPredicate() {
        return Objects.nonNull(spentOnTo);
    }

    public boolean requireMinMinutesPredicate() {
        return Objects.nonNull(minMinutes);
    }

    public boolean requireMaxMinutesPredicate() {
        return Objects.nonNull(maxMinutes);
    }

    public boolean requireBillablePredicate() {
        return Objects.nonNull(billable);
    }
}
