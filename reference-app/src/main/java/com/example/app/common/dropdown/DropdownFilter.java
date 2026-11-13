package com.example.app.common.dropdown;

import com.example.app.common.limits.MaxLength;
import com.example.app.common.pagination.CursorFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.StringUtils;

/**
 * The parameters every dropdown accepts: a batch ({@code size}, {@code after}) of a cursor-paged
 * list, so a large set (thousands of cities) is browsed in batches or narrowed with {@code query}.
 * Entities with mandatory or optional filters subclass it.
 */
@Getter
@Setter
public class DropdownFilter extends CursorFilter {

    @Schema(description = "Which records to list (only for entities with an active flag)", defaultValue = "ACTIVE")
    private DropdownStatus status = DropdownStatus.ACTIVE;

    @Schema(description = "Overrides the dropdown's default sort direction")
    private DropdownCandidate.SortDirection sortDirection;

    @MaxLength("search-query")
    @Schema(description = "Case-insensitive 'contains' match on the label (searchable dropdowns only)")
    private String query;

    public boolean requireQueryPredicate() {
        return StringUtils.hasText(query);
    }
}
