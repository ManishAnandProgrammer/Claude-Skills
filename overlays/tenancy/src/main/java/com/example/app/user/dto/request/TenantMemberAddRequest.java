package com.example.app.user.dto.request;

import com.example.app.common.limits.MaxLength;
import com.example.app.user.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "An existing account to add to the caller's tenant, with its role there")
public record TenantMemberAddRequest(
        @Schema(description = "The account's email (ignoring case); the person must have signed up", example = "asha@example.com")
        @NotBlank @MaxLength("user.email")
        String email,

        @Schema(description = "The role in this tenant", example = "USER")
        @NotNull
        Role role) {
}
