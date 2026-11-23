package com.example.app.common.dropdown;

import com.example.app.common.limits.MaxLength;
import com.example.app.common.pagination.CursorFilter;
import com.example.app.common.pagination.QueryFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.StringUtils;

@Getter
@Setter
public class DropdownFilter extends CursorFilter implements QueryFilter {

    @Schema(description = "Which records to list (only for entities with an active flag)", defaultValue = "ACTIVE")
    private DropdownStatus status = DropdownStatus.ACTIVE;

    @Schema(description = "Overrides the dropdown's default sort direction")
    private DropdownCandidate.SortDirection sortDirection;

    @MaxLength("search-query")
    @Schema(description = "Case-insensitive 'contains' match on the label (searchable dropdowns only)")
    private String query;

    @Override
    public boolean requireQueryPredicate() {
        return StringUtils.hasText(query);
    }
}
