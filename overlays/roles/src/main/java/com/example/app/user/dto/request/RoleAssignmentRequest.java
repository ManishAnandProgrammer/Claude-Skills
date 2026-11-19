package com.example.app.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;

import java.time.Instant;

@Schema(description = "A role given to a person: for good, or until a moment")
public record RoleAssignmentRequest(
        @Schema(description = "When it stops giving anything; null or absent for good", example = "2027-06-30T23:59:59Z")
        @Future
        Instant validUntil) {
}
