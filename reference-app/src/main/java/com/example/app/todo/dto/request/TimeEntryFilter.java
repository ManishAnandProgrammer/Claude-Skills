package com.example.app.todo.dto.request;

import com.example.app.common.limits.ConfiguredMax;
import com.example.app.common.limits.ConfiguredMaxSize;
import com.example.app.common.limits.EarliestDate;
import com.example.app.common.limits.LatestDate;
import com.example.app.common.pagination.CursorFilter;
import com.example.app.common.validation.CrossField;
import com.example.app.common.validation.DateRanges;
import com.example.app.common.validation.NumberRanges;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
    @Schema(description = "Only entries spent on or after this day (yyyy-MM-dd); not after spentOnTo; with sort SPENT_ON only", example = "2026-11-01")
    private LocalDate spentOnFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @EarliestDate("time-entry.spent-on") @LatestDate("time-entry.spent-on")
    @Schema(description = "Only entries spent on or before this day (yyyy-MM-dd); with sort SPENT_ON only", example = "2026-11-30")
    private LocalDate spentOnTo;

    @Min(1)
    @ConfiguredMax("time-entry.max-minutes")
    @Schema(description = "Only entries of at least this many minutes; not above maxMinutes; with sort MINUTES only", example = "30")
    private Integer minMinutes;

    @Min(1)
    @ConfiguredMax("time-entry.max-minutes")
    @Schema(description = "Only entries of at most this many minutes; with sort MINUTES only", example = "120")
    private Integer maxMinutes;

    @Schema(description = "Only billable (true) or non-billable (false) entries; absent for both")
    private Boolean billable;

    @NotNull
    @Schema(description = "Order of the list; a cursor only continues the order it came from", defaultValue = "SPENT_ON", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private TimeEntrySort sort = TimeEntrySort.SPENT_ON;

    public boolean requireIdsPredicate() {
        return !CollectionUtils.isEmpty(ids);
    }

    @CrossField(reportedOn = "spentOnTo", message = "{time-entry.spent-on-range}")
    public boolean isSpentOnRangeOrdered() {
        return DateRanges.isOrdered(spentOnFrom, spentOnTo);
    }

    @CrossField(reportedOn = "maxMinutes", message = "{time-entry.minutes-range}")
    public boolean isMinutesRangeOrdered() {
        return NumberRanges.isOrdered(minMinutes, maxMinutes);
    }

    /** A range filter needs its own column's order: under any other, no index serves it and the walk reads every entry. */
    @CrossField(reportedOn = "spentOnFrom", message = "{time-entry.spent-on-filter-order}")
    public boolean isSpentOnFromSortedBySpentOn() {
        return Objects.isNull(spentOnFrom) || sort == TimeEntrySort.SPENT_ON;
    }

    @CrossField(reportedOn = "spentOnTo", message = "{time-entry.spent-on-filter-order}")
    public boolean isSpentOnToSortedBySpentOn() {
        return Objects.isNull(spentOnTo) || sort == TimeEntrySort.SPENT_ON;
    }

    @CrossField(reportedOn = "minMinutes", message = "{time-entry.minutes-filter-order}")
    public boolean isMinMinutesSortedByMinutes() {
        return Objects.isNull(minMinutes) || sort == TimeEntrySort.MINUTES;
    }

    @CrossField(reportedOn = "maxMinutes", message = "{time-entry.minutes-filter-order}")
    public boolean isMaxMinutesSortedByMinutes() {
        return Objects.isNull(maxMinutes) || sort == TimeEntrySort.MINUTES;
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
