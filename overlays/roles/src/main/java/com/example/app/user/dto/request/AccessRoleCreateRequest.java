package com.example.app.user.dto.request;

import com.example.app.common.limits.ConfiguredMaxSize;
import com.example.app.common.limits.MaxLength;
import com.example.app.common.security.Permission;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

@Schema(description = "A new role: its permissions are only ones you hold, each with its prerequisites")
public record AccessRoleCreateRequest(
        @Schema(description = "Stable code, unique; never changes", example = "ACCOUNTANT")
        @NotBlank @MaxLength("role.code") @Pattern(regexp = AccessRoleCode.PATTERN, message = AccessRoleCode.MESSAGE)
        String code,

        @Schema(description = "Name shown in the app", example = "Accountant")
        @NotBlank @MaxLength("role.name")
        String name,

        // tenancy:begin
        @Schema(description = """
                Its permissions (GET /permissions lists them with their prerequisites): only ones you hold, and each \
                one's prerequisites too, never filled in for you""", example = "[\"TENANT_MEMBERS_MANAGE\"]")
        // tenancy:else
        // @Schema(description = """
        //         Its permissions (GET /permissions lists them with their prerequisites): only ones you hold, and each \
        //         one's prerequisites too, never filled in for you""", example = "[\"ACCOUNT_READ\"]")
        // tenancy:end
        @NotNull @ConfiguredMaxSize("role.max-permissions")
        Set<@NotNull Permission> permissions) {

    public AccessRoleCreateRequest {
        permissions = Objects.isNull(permissions) ? null : Collections.unmodifiableSet(permissions);
    }
}
