package com.example.app.user.dto.request;

import com.example.app.common.limits.MaxLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

@Schema(description = "Whom to act as in the tenant, and why")
public record ImpersonationRequest(
        @Schema(description = "A member of the tenant; absent: its oldest ADMIN (its owner)")
        UUID userId,

        @Schema(description = "Why, for the audit trail: a ticket reference for example; required", example = "SUP-1234")
        @NotBlank @MaxLength("tenant.support-reason")
        String reason) {
}
