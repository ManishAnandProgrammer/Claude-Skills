package com.example.app.planning.dto.request;

import com.example.app.common.limits.ConfiguredMaxSize;
import com.example.app.common.pagination.CursorFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.CollectionUtils;

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

    @NotNull
    @Schema(description = "Order of the list; a cursor only continues the order it came from", defaultValue = "STARTS_ON")
    private PlanSort sort = PlanSort.STARTS_ON;

    public boolean requireIdsPredicate() {
        return !CollectionUtils.isEmpty(ids);
    }

    public boolean requireTodoIdPredicate() {
        return Objects.nonNull(todoId);
    }
}
