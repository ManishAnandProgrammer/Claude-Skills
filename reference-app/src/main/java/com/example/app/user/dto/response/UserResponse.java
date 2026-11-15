package com.example.app.user.dto.response;

// tenancy:begin
// tenancy:else
import com.example.app.user.domain.Role;
// tenancy:end
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

// tenancy:begin
// /** An account. No role: with tenancy a role is per tenant, the membership's (GET /users/me/tenants lists them). */
// tenancy:end
@Schema(description = "An account")
public record UserResponse(
        @Schema(description = "Account id", example = "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d") UUID id,
        @Schema(description = "Email address, lower-case", example = "asha@example.com") String email,
        @Schema(description = "Name shown in the app", example = "Asha Kaur") String fullName,
        // tenancy:begin
        // tenancy:else
        @Schema(description = "Role", example = "USER") Role role,
        // tenancy:end
        @Schema(description = "When the account was created") Instant createdAt,
        @Schema(description = "When the account last changed") Instant updatedAt,
        @Schema(description = "Who created it; empty for self-signup") UUID createdBy,
        @Schema(description = "Who last changed it") UUID updatedBy,
        @Schema(description = "Optimistic-locking version", example = "0") Long version) {
}
