package com.example.app.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Tokens for a signed-in session")
public record TokenResponse(
        @Schema(description = "Send as 'Authorization: Bearer <token>'", example = "eyJhbGciOiJIUzI1NiJ9...")
        String accessToken,
        @Schema(description = "Always 'Bearer'", example = "Bearer")
        String tokenType,
        @Schema(description = "Seconds until the access token expires", example = "900")
        long expiresIn,
        @Schema(description = "Single use: exchange it for new tokens before it expires", example = "nT3l0yQ9u0...")
        String refreshToken) {
}
