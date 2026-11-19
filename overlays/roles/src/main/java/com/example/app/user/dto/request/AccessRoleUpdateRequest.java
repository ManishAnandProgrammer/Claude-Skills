package com.example.app.user.dto.request;

import com.example.app.common.limits.ConfiguredMaxSize;
import com.example.app.common.limits.MaxLength;
import com.example.app.common.security.Permission;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

@Schema(description = "A role's name and permissions, replaced; its code never changes")
public record AccessRoleUpdateRequest(
        @Schema(description = "Name shown in the app", example = "Accountant")
        @NotBlank @MaxLength("role.name")
        String name,

        // tenancy:begin
        @Schema(description = """
                All its permissions, replacing the ones it has: only ones you hold, and each one's prerequisites too""",
                example = "[\"TENANT_MEMBERS_MANAGE\"]")
        // tenancy:else
        // @Schema(description = """
        //         All its permissions, replacing the ones it has: only ones you hold, and each one's prerequisites too""",
        //         example = "[\"ACCOUNT_READ\"]")
        // tenancy:end
        @NotNull @ConfiguredMaxSize("role.max-permissions")
        Set<@NotNull Permission> permissions,

        @Schema(description = "The version you last read; a newer one is 409 OPTIMISTIC_LOCK_CONFLICT", example = "0")
        @NotNull
        Long version) {

    public AccessRoleUpdateRequest {
        permissions = Objects.isNull(permissions) ? null : Collections.unmodifiableSet(permissions);
    }
}
