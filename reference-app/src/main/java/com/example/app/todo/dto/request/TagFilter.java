package com.example.app.todo.dto.request;

import com.example.app.common.dropdown.DropdownStatus;
import com.example.app.common.limits.MaxLength;
import com.example.app.common.pagination.PageFilter;
import com.example.app.common.pagination.QueryFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.StringUtils;

/**
 * A user has few tags (a bounded table), so this list is offset-paged with page numbers. The active
 * flag is filtered with the dropdown's word and values ({@code status}), so a client learns one; only
 * the default differs: this page manages every tag, the dropdown offers the usable ones.
 */
@Getter
@Setter
public class TagFilter extends PageFilter implements QueryFilter {

    @NotNull
    @Schema(description = "ACTIVE or INACTIVE tags only, or ALL", defaultValue = "ALL")
    private DropdownStatus status = DropdownStatus.ALL;

    @MaxLength("search-query")
    @Schema(description = "Case-insensitive 'contains' match on the name")
    private String query;

    @NotNull
    @Schema(description = "Order of the page", defaultValue = "NAME")
    private TagSort sort = TagSort.NAME;

    public boolean requireStatusPredicate() {
        return status != DropdownStatus.ALL;
    }

    @Override
    public boolean requireQueryPredicate() {
        return StringUtils.hasText(query);
    }
}
