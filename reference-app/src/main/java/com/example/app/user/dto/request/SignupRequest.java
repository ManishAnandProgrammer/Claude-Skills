package com.example.app.user.dto.request;

import com.example.app.common.validation.AsciiOnly;
import com.example.app.common.validation.PasswordPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import com.example.app.common.limits.MaxLength;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "A new account")
public record SignupRequest(
        @Schema(description = "Email address (ASCII only); stored lower-case", example = "asha@example.com")
        @NotBlank @Email @MaxLength("user.email") @AsciiOnly
        String email,

        @Schema(description = "Name shown in the app", example = "Asha Kaur")
        @NotBlank @MaxLength("user.full-name")
        String fullName,

        @Schema(description = """
                At most 72 bytes in UTF-8 (a Gurmukhi letter takes 3), with \
                an upper-case letter, a lower-case letter, a digit and one other character.""",
                example = "Correct-Horse-9")
        @PasswordPolicy
        String password) {
}
