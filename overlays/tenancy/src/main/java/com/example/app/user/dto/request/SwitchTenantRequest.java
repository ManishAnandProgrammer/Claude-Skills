package com.example.app.user.dto.request;

import com.example.app.common.limits.MaxLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * The one request that names a tenant: which of the caller's own memberships the next tokens are
 * for. The tenant is checked against the refresh token's user, so naming another tenant gets 404.
 */
@Schema(description = "A refresh token and the tenant to switch to")
public record SwitchTenantRequest(
        @Schema(description = "The refresh token", example = "nT3l0yQ9u0...")
        @NotBlank @MaxLength("user.refresh-token")
        String refreshToken,

        @Schema(description = "One of your tenants", example = "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d")
        @NotNull
        UUID tenantId) {
}
