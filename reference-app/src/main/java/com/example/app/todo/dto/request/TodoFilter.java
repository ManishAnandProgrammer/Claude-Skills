package com.example.app.todo.dto.request;

import com.example.app.common.limits.ConfiguredMaxSize;
import com.example.app.common.limits.EarliestDate;
import com.example.app.common.limits.LatestDate;
import com.example.app.common.limits.MaxLength;
import com.example.app.common.pagination.CursorFilter;
import com.example.app.common.validation.CrossField;
import com.example.app.todo.domain.TodoPriority;
import com.example.app.todo.domain.TodoStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Getter
@Setter
public class TodoFilter extends CursorFilter {

    @ConfiguredMaxSize("cursor-page.max-size")
    @Schema(description = """
            Find by ids (comma-separated, at most the maximum batch size): unknown or another user's ids \
            are simply absent. Absent or empty: no id filter.""")
    private List<@NotNull UUID> ids;

    @Schema(description = "Only the todos of this list")
    private UUID todoListId;

    @Schema(description = "Only todos carrying this tag (another user's tag matches none)")
    private UUID tagId;

    @Schema(description = "Only todos in this status")
    private TodoStatus status;

    @Schema(description = "Only todos of this priority")
    private TodoPriority priority;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @EarliestDate("todo.due-date") @LatestDate("todo.due-date")
    @Schema(description = "Only todos due on or before this date (yyyy-MM-dd), with sort DUE_DATE only", example = "2026-12-31")
    private LocalDate dueOnOrBefore;

    @MaxLength("search-query")
    @Schema(description = "Case-insensitive 'contains' match on the title")
    private String query;

    @NotNull
    @Schema(description = "Order of the list; a cursor only continues the order it came from", defaultValue = "DUE_DATE", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private TodoSort sort = TodoSort.DUE_DATE;

    public boolean requireIdsPredicate() {
        return !CollectionUtils.isEmpty(ids);
    }

    public boolean requireTodoListIdPredicate() {
        return Objects.nonNull(todoListId);
    }

    public boolean requireTagIdPredicate() {
        return Objects.nonNull(tagId);
    }

    public boolean requireStatusPredicate() {
        return Objects.nonNull(status);
    }

    public boolean requirePriorityPredicate() {
        return Objects.nonNull(priority);
    }

    /** Under NEWEST no index serves the due filter: the walk would read the owner's whole history. */
    @CrossField(reportedOn = "dueOnOrBefore", message = "{todo.due-filter-order}")
    public boolean isDueOnOrBeforeSortedByDueDate() {
        return Objects.isNull(dueOnOrBefore) || sort == TodoSort.DUE_DATE;
    }

    public boolean requireDueOnOrBeforePredicate() {
        return Objects.nonNull(dueOnOrBefore);
    }

    public boolean requireQueryPredicate() {
        return StringUtils.hasText(query);
    }
}
