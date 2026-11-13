package com.example.app.todo.dto.request;

import com.example.app.common.limits.MaxLength;
import com.example.app.common.pagination.PageFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.StringUtils;

/** A user has few lists (a bounded table), so this list is offset-paged with page numbers. */
@Getter
@Setter
public class TodoListFilter extends PageFilter {

    @MaxLength("search-query")
    @Schema(description = "Case-insensitive 'contains' match on the name")
    private String query;

    @NotNull
    @Schema(description = "Order of the page", defaultValue = "NAME")
    private TodoListSort sort = TodoListSort.NAME;

    public boolean requireQueryPredicate() {
        return StringUtils.hasText(query);
    }
}
