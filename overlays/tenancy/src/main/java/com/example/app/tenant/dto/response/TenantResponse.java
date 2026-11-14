package com.example.app.tenant.dto.response;

import com.example.app.tenant.api.TenantStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A tenant")
public record TenantResponse(
        @Schema(description = "Tenant id", example = "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d") UUID id,
        @Schema(description = "Name", example = "Acme Ltd") String name,
        @Schema(description = "Lifecycle status", example = "ACTIVE") TenantStatus status,
        @Schema(description = "When it was created") Instant createdAt,
        @Schema(description = "When it last changed") Instant updatedAt,
        @Schema(description = "The staff member who created it") UUID createdBy,
        @Schema(description = "Who last changed it; null for a job") UUID updatedBy,
        @Schema(description = "Send it back when changing the status", example = "0") Long version) {
}
