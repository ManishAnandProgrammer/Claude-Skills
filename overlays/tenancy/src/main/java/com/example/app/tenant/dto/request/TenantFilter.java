package com.example.app.tenant.dto.request;

import com.example.app.common.limits.MaxLength;
import com.example.app.common.pagination.PageFilter;
import com.example.app.common.pagination.QueryFilter;
import com.example.app.tenant.api.TenantStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.StringUtils;

import java.util.Objects;

/** Staff's tenant list, an admin screen with page numbers and a total: offset-paged, newest first or by name. */
@Getter
@Setter
public class TenantFilter extends PageFilter implements QueryFilter {

    @Schema(description = "Only tenants in this status; absent: every status")
    private TenantStatus status;

    @MaxLength("search-query")
    @Schema(description = "Case-insensitive 'contains' match on the name")
    private String query;

    @NotNull
    @Schema(description = "Order of the page", defaultValue = "NEWEST", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private TenantSort sort = TenantSort.NEWEST;

    public boolean requireStatusPredicate() {
        return Objects.nonNull(status);
    }

    @Override
    public boolean requireQueryPredicate() {
        return StringUtils.hasText(query);
    }
}
