package com.example.app.planning.dto.request;

import com.example.app.common.limits.MaxLength;
import com.example.app.common.pagination.CursorFilter;
import com.example.app.common.pagination.QueryFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * The parameters of a plan's todo options: the todo dropdown's (a list, a title search, a batch), without its
 * status and sort direction, since the options are always the list's todos in title order.
 */
@Getter
@Setter
public class PlanTodoOptionsFilter extends CursorFilter implements QueryFilter {

    @NotNull
    @Schema(description = "The list whose todos to offer (another user's list, or an unknown id, offers none)",
            example = "0190a9c2-7d10-7b2c-8e3f-1a2b3c4d5e6f")
    private UUID todoListId;

    @MaxLength("search-query")
    @Schema(description = "Case-insensitive 'contains' match on the title")
    private String query;

    @Override
    public boolean requireQueryPredicate() {
        return StringUtils.hasText(query);
    }
}
