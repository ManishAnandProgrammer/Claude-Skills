package com.example.app.planning.dto.request;

import com.example.app.common.limits.ConfiguredMaxSize;
import com.example.app.common.limits.EarliestDate;
import com.example.app.common.limits.LatestDate;
import com.example.app.common.pagination.CursorFilter;
import com.example.app.common.validation.CrossField;
import com.example.app.common.validation.DateRanges;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Plans pile up over the years, so this list is cursor-paged. */
@Getter
@Setter
public class PlanFilter extends CursorFilter {

    @ConfiguredMaxSize("cursor-page.max-size")
    @Schema(description = """
            Find by ids (comma-separated, at most the maximum batch size): unknown or another user's ids \
            are simply absent. Absent or empty: no id filter.""")
    private List<@NotNull UUID> ids;

    @Schema(description = "Only the plans of this todo (another module's id: another user's todo matches none)")
    private UUID todoId;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @EarliestDate("plan.dates") @LatestDate("plan.dates")
    @Schema(description = """
            Only plans that share a day with plannedFrom..plannedTo (both inclusive): here, plans ending on or \
            after this day (yyyy-MM-dd); not after plannedTo; with sort STARTS_ON only""", example = "2026-11-16")
    private LocalDate plannedFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @EarliestDate("plan.dates") @LatestDate("plan.dates")
    @Schema(description = "Only plans starting on or before this day (yyyy-MM-dd); with sort STARTS_ON only", example = "2026-11-22")
    private LocalDate plannedTo;

    @NotNull
    @Schema(description = "Order of the list; a cursor only continues the order it came from", defaultValue = "STARTS_ON")
    private PlanSort sort = PlanSort.STARTS_ON;

    public boolean requireIdsPredicate() {
        return !CollectionUtils.isEmpty(ids);
    }

    public boolean requireTodoIdPredicate() {
        return Objects.nonNull(todoId);
    }

    /** An inverted range is a mistake the client should hear about, not an empty list. */
    @CrossField(reportedOn = "plannedTo", message = "{plan.planned-range}")
    public boolean isPlannedRangeOrdered() {
        return DateRanges.isOrdered(plannedFrom, plannedTo);
    }

    /**
     * A range filter comes with the order of its own column, the overlap's ends included: under STARTS_ON
     * the planned days are a range of the start's index, read until a page is full; under NEWEST every end,
     * alone or with the other, leaves the walk unbounded (newest first, filtered by days in the past, reads
     * the owner's whole history; both ends, a range as wide as the dates allow). Reported on each end sent.
     */
    @CrossField(reportedOn = "plannedFrom", message = "{plan.planned-filter-order}")
    public boolean isPlannedFromSortedByStartsOn() {
        return Objects.isNull(plannedFrom) || sort == PlanSort.STARTS_ON;
    }

    @CrossField(reportedOn = "plannedTo", message = "{plan.planned-filter-order}")
    public boolean isPlannedToSortedByStartsOn() {
        return Objects.isNull(plannedTo) || sort == PlanSort.STARTS_ON;
    }

    public boolean requirePlannedFromPredicate() {
        return Objects.nonNull(plannedFrom);
    }

    public boolean requirePlannedToPredicate() {
        return Objects.nonNull(plannedTo);
    }
}
