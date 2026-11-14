package com.example.app.todo.dto.request;

import com.example.app.common.dropdown.DropdownStatus;
import com.example.app.common.limits.ConfiguredMaxSize;
import com.example.app.common.limits.MaxLength;
import com.example.app.common.pagination.PageFilter;
import com.example.app.common.pagination.QueryFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A user has few tags (a bounded table), so this list is offset-paged with page numbers. The active
 * flag is filtered with the dropdown's word and values ({@code status}), so a client learns one; only
 * the default differs: this page manages every tag, the dropdown offers the usable ones.
 */
@Getter
@Setter
public class TagFilter extends PageFilter implements QueryFilter {

    @ConfiguredMaxSize("offset-page.max-size")
    @Schema(description = """
            Find by ids (comma-separated, at most the maximum page size): unknown or another user's ids \
            are simply absent. Absent or empty: no id filter.""")
    private List<@NotNull UUID> ids;

    @NotNull
    @Schema(description = "ACTIVE or INACTIVE tags only, or ALL", defaultValue = "ALL")
    private DropdownStatus status = DropdownStatus.ALL;

    @MaxLength("search-query")
    @Schema(description = "Case-insensitive 'contains' match on the name")
    private String query;

    @NotNull
    @Schema(description = "Order of the page", defaultValue = "NAME")
    private TagSort sort = TagSort.NAME;

    public boolean requireIdsPredicate() {
        return Objects.nonNull(ids) && !ids.isEmpty();
    }

    public boolean requireStatusPredicate() {
        return status != DropdownStatus.ALL;
    }

    @Override
    public boolean requireQueryPredicate() {
        return StringUtils.hasText(query);
    }
}
