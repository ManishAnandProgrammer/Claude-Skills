package com.example.app.user.dto.request;

import com.example.app.common.limits.ConfiguredMaxSize;
import com.example.app.common.limits.MaxLength;
import com.example.app.common.pagination.PageFilter;
import com.example.app.user.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** The members page is an admin screen that needs page numbers and a total, so it is offset-paged. */
@Getter
@Setter
public class TenantMemberFilter extends PageFilter {

    @ConfiguredMaxSize("offset-page.max-size")
    @Schema(description = """
            Find by account ids (comma-separated, at most the maximum page size): unknown ids, or another \
            tenant's members, are simply absent. Absent or empty: no id filter.""")
    private List<@NotNull UUID> ids;

    @MaxLength("search-query")
    @Schema(description = "Case-insensitive 'contains' match on the email")
    private String query;

    @Schema(description = "Only members with this role in this tenant; absent: every role")
    private Role role;

    @NotNull
    @Schema(description = "Order of the page", defaultValue = "NEWEST")
    private TenantMemberSort sort = TenantMemberSort.NEWEST;

    public boolean requireIdsPredicate() {
        return !CollectionUtils.isEmpty(ids);
    }

    public boolean requireQueryPredicate() {
        return StringUtils.hasText(query);
    }

    public boolean requireRolePredicate() {
        return Objects.nonNull(role);
    }
}
