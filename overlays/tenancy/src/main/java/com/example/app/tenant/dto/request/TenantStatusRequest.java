package com.example.app.tenant.dto.request;

import com.example.app.tenant.api.TenantStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "A tenant's new status")
public record TenantStatusRequest(
        @Schema(description = """
                SUSPENDED (from ACTIVE), ACTIVE (from SUSPENDED), CLOSED (from SUSPENDED) or REJECTED (from \
                PENDING). A PENDING tenant becomes ACTIVE through provisioning, not here.""", example = "SUSPENDED")
        @NotNull
        TenantStatus status,

        @Schema(description = "The version you last read; a stale one is refused with 409", example = "0")
        @NotNull
        Long version) {
}
