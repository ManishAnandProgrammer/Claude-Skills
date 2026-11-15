package com.example.app.user.dto.request;

import com.example.app.user.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "A member's new role in the caller's tenant")
public record TenantMemberUpdateRequest(
        @Schema(description = "The role in this tenant", example = "ADMIN")
        @NotNull
        Role role,

        @Schema(description = """
                The version the add or the last role change answered; a stale one is 409 \
                OPTIMISTIC_LOCK_CONFLICT""", example = "0")
        @NotNull
        Long version) {
}
