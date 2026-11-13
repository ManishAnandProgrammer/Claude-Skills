package com.example.app.user.dto.request;

import com.example.app.common.limits.MaxLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "A refresh token from login or an earlier refresh")
public record RefreshTokenRequest(
        @Schema(description = "The refresh token", example = "nT3l0yQ9u0...")
        @NotBlank @MaxLength("user.refresh-token")
        String refreshToken) {
}
