package com.example.app.todo.dto.request;

import com.example.app.common.limits.MaxLength;
import com.example.app.common.pagination.CursorFilter;
import com.example.app.todo.domain.TodoPriority;
import com.example.app.todo.domain.TodoStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Todos grow without bound, so this list is cursor-paged. */
@Getter
@Setter
public class TodoFilter extends CursorFilter {

    @Schema(description = "Only the todos of this list")
    private UUID todoListId;

    @Schema(description = "Only todos carrying this tag (another user's tag matches none)")
    private UUID tagId;

    @Schema(description = "Only todos in this status")
    private TodoStatus status;

    @Schema(description = "Only todos of this priority")
    private TodoPriority priority;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @Schema(description = "Only todos due on or before this date (yyyy-MM-dd)", example = "2026-12-31")
    private LocalDate dueOnOrBefore;

    @MaxLength("search-query")
    @Schema(description = "Case-insensitive 'contains' match on the title")
    private String query;

    @NotNull
    @Schema(description = "Order of the list; a cursor only continues the order it came from", defaultValue = "DUE_DATE")
    private TodoSort sort = TodoSort.DUE_DATE;

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

    public boolean requireDueOnOrBeforePredicate() {
        return Objects.nonNull(dueOnOrBefore);
    }

    public boolean requireQueryPredicate() {
        return StringUtils.hasText(query);
    }
}
