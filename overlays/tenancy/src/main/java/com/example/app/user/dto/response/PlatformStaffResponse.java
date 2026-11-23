package com.example.app.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A platform staff member")
public record PlatformStaffResponse(
        @Schema(description = "Staff member id", example = "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d") UUID id,
        @Schema(description = "Email address, lower-case", example = "sam@example.com") String email,
        @Schema(description = "When they were deactivated; empty while active") Instant deactivatedAt) {
}
