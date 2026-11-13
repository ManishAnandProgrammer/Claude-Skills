package com.example.app.user.dto.request;

import com.example.app.common.limits.MaxLength;
import com.example.app.common.validation.MaxUtf8Bytes;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Email and password")
public record LoginRequest(
        @Schema(description = "Email address", example = "asha@example.com")
        @NotBlank @MaxLength("user.email")
        String email,

        @Schema(description = "Password", example = "Correct-Horse-9")
        // bcrypt reads only the first 72 bytes: a longer password can't be the right one.
        @NotBlank @MaxUtf8Bytes(72)
        String password) {
}
