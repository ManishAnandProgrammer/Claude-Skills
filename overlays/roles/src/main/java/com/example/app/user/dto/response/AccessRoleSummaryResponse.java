package com.example.app.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A role in the list (its permissions are the detail's)")
public record AccessRoleSummaryResponse(
        @Schema(description = "Role id", example = "0190a9c2-7d10-7b2c-8e3f-1a2b3c4d5e6f") UUID id,
        @Schema(description = "Stable code", example = "ACCOUNTANT") String code,
        @Schema(description = "Name shown in the app", example = "Accountant") String name,
        @Schema(description = "The built-in role it was seeded from (USER, ADMIN); null for one an admin created", example = "USER")
        String builtIn,
        @Schema(description = "When it was created") Instant createdAt,
        @Schema(description = "When it last changed") Instant updatedAt,
        @Schema(description = "Who created it; empty for a built-in one") UUID createdBy,
        @Schema(description = "Who last changed it") UUID updatedBy,
        @Schema(description = "Optimistic-locking version, sent back with an update", example = "0") Long version) {
}
