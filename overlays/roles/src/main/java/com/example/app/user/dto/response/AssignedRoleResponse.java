package com.example.app.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A role a person holds")
public record AssignedRoleResponse(
        @Schema(description = "Role id", example = "0190a9c2-7d10-7b2c-8e3f-1a2b3c4d5e6f") UUID roleId,
        @Schema(description = "Its code", example = "ACCOUNTANT") String code,
        @Schema(description = "Its name", example = "Accountant") String name,
        @Schema(description = "When it stops giving anything; null for good (past it, it gives nothing)") Instant validUntil,
        @Schema(description = "When it was given") Instant createdAt,
        @Schema(description = "When it last changed") Instant updatedAt,
        @Schema(description = "Who gave it; empty for signup's or a changeset's") UUID createdBy,
        @Schema(description = "Who last changed it") UUID updatedBy) {
}
