package com.example.app.todo.dto.request;

import com.example.app.common.limits.ConfiguredMaxSize;
import com.example.app.common.limits.MaxLength;
import com.example.app.common.pagination.PageFilter;
import com.example.app.common.pagination.QueryFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

/** A user has few lists (a bounded table), so this list is offset-paged with page numbers. */
@Getter
@Setter
public class TodoListFilter extends PageFilter implements QueryFilter {

    @ConfiguredMaxSize("offset-page.max-size")
    @Schema(description = """
            Find by ids (comma-separated, at most the maximum page size): unknown or another user's ids \
            are simply absent. Absent or empty: no id filter.""")
    private List<@NotNull UUID> ids;

    @MaxLength("search-query")
    @Schema(description = "Case-insensitive 'contains' match on the name")
    private String query;

    @NotNull
    @Schema(description = "Order of the page", defaultValue = "NAME")
    private TodoListSort sort = TodoListSort.NAME;

    public boolean requireIdsPredicate() {
        return !CollectionUtils.isEmpty(ids);
    }

    @Override
    public boolean requireQueryPredicate() {
        return StringUtils.hasText(query);
    }
}
