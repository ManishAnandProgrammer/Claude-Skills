package com.example.app.user.dto.response;

import com.example.app.user.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "One of the signed-in user's tenants, with their role there")
public record MembershipResponse(
        @Schema(description = "Tenant id: switch-tenant's tenantId", example = "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d") UUID tenantId,
        @Schema(description = "The tenant's name", example = "Acme Ltd") String tenantName,
        @Schema(description = "Only an ACTIVE tenant can be signed in to; PENDING awaits approval", example = "ACTIVE")
        String tenantStatus,
        @Schema(description = "The user's role in that tenant", example = "ADMIN") Role role,
        @Schema(description = "True for the tenant of the token that asked") boolean current,
        @Schema(description = "When tokens were last issued for it; null if never") Instant lastUsedAt,
        @Schema(description = "When the user joined it") Instant createdAt,
        @Schema(description = "When the membership last changed") Instant updatedAt,
        @Schema(description = "Who added the user; null for a registration or staff") UUID createdBy,
        @Schema(description = "Who last changed it") UUID updatedBy) {
}
