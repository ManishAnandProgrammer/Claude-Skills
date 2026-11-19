package com.example.app.user.dto.response;

import com.example.app.user.domain.GrantEffect;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A permission given to or taken from a person, whatever their roles give")
public record PersonalGrantResponse(
        @Schema(description = "A Permission code (one that names no permission grants nothing)", example = "ACCOUNT_STATUS_CHANGE")
        String permission,
        @Schema(description = "ALLOW or DENY", example = "DENY") GrantEffect effect,
        @Schema(description = "When it stops; null for good (past it, it does nothing)") Instant validUntil,
        @Schema(description = "When it was given") Instant createdAt,
        @Schema(description = "When it last changed") Instant updatedAt,
        @Schema(description = "Who gave it") UUID createdBy,
        @Schema(description = "Who last changed it") UUID updatedBy) {
}
