package com.example.app.user.dto.response;

// roles:begin
// roles:else
// import com.example.app.user.domain.Role;
// roles:end
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

// roles:begin
@Schema(description = "A member of the caller's tenant (their roles and grants: GET /users/{userId}/access)")
// roles:else
// @Schema(description = "A member of the caller's tenant")
// roles:end
public record TenantMemberResponse(
        @Schema(description = "The member's account id: the id in /tenant-members/{userId}") UUID userId,
        @Schema(description = "The account's email", example = "asha@example.com") String email,
        @Schema(description = "The account's name", example = "Asha Kaur") String fullName,
        // roles:begin
        // roles:else
        // @Schema(description = "The role in this tenant", example = "USER") Role role,
        // @Schema(description = "Send it back on PUT /tenant-members/{userId}", example = "0") long version,
        // roles:end
        @Schema(description = "When the person joined this tenant (added again after a removal: that moment)")
        Instant createdAt,
        @Schema(description = "When the membership last changed") Instant updatedAt,
        @Schema(description = "Who added the member; null for the tenant's owner") UUID createdBy,
        @Schema(description = "Who last changed it") UUID updatedBy) {
}
