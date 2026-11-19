package com.example.app.user.dto.request;

import com.example.app.user.domain.GrantEffect;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

@Schema(description = "One permission given to a person (ALLOW) or taken away (DENY, which wins over every role)")
public record PersonalGrantRequest(
        @Schema(description = "ALLOW or DENY", example = "DENY")
        @NotNull
        GrantEffect effect,

        @Schema(description = "When it stops; null or absent for good", example = "2027-06-30T23:59:59Z")
        @Future
        Instant validUntil) {
}
