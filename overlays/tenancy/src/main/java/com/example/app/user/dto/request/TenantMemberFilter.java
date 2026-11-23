package com.example.app.user.dto.request;

import com.example.app.common.limits.ConfiguredMaxSize;
import com.example.app.common.limits.MaxLength;
import com.example.app.common.pagination.PageFilter;
// roles:begin
// roles:else
// import com.example.app.user.domain.Role;
// roles:end
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
// roles:begin
// roles:else
// import java.util.Objects;
// roles:end
import java.util.UUID;

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

    // roles:begin
    // roles:else
    // @Schema(description = "Only members with this role in this tenant; absent: every role")
    // private Role role;
    //
    // roles:end
    @NotNull
    @Schema(description = "Order of the page", defaultValue = "NEWEST", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private TenantMemberSort sort = TenantMemberSort.NEWEST;

    public boolean requireIdsPredicate() {
        return !CollectionUtils.isEmpty(ids);
    }

    public boolean requireQueryPredicate() {
        return StringUtils.hasText(query);
    }
    // roles:begin
    // roles:else
    //
    // public boolean requireRolePredicate() {
    //     return Objects.nonNull(role);
    // }
    // roles:end
}
