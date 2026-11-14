package com.example.app.tenant.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "One action of platform staff inside the tenant")
public record PlatformAuditResponse(
        @Schema(description = "Entry id; newer entries have greater ids") UUID id,
        @Schema(description = "The platform staff member who acted") UUID actorId,
        @Schema(description = "What they did", example = "IMPERSONATION") String action,
        @Schema(description = "The action's detail as recorded", example = "userId=0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d, reason=SUP-1234")
        String detail,
        @Schema(description = "When it happened") Instant createdAt,
        @Schema(description = "Equal to createdAt: entries never change") Instant updatedAt,
        @Schema(description = "The staff member, as audited") UUID createdBy,
        @Schema(description = "The staff member, as audited") UUID updatedBy) {
}
