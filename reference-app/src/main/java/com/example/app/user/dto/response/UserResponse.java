package com.example.app.user.dto.response;

import com.example.app.user.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "An account")
public record UserResponse(
        @Schema(description = "Account id", example = "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d") UUID id,
        @Schema(description = "Email address, lower-case", example = "asha@example.com") String email,
        @Schema(description = "Name shown in the app", example = "Asha Kaur") String fullName,
        @Schema(description = "Role", example = "USER") Role role,
        @Schema(description = "When the account was created") Instant createdAt,
        @Schema(description = "When the account last changed") Instant updatedAt,
        @Schema(description = "Who created it; empty for self-signup") UUID createdBy,
        @Schema(description = "Who last changed it") UUID updatedBy,
        @Schema(description = "Optimistic-locking version", example = "0") Long version) {
}
