package com.example.app.user.dto.request;

import com.example.app.common.limits.MaxLength;
import com.example.app.common.validation.AsciiOnly;
import com.example.app.common.validation.PasswordPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "A new organisation, and the account that will own it")
public record TenantRegistrationRequest(
        @Schema(description = "The organisation's name", example = "Acme Ltd")
        @NotBlank @MaxLength("tenant.name")
        String organisationName,

        @Schema(description = "The owner's email address (ASCII only); an existing account's, or a new one's",
                example = "asha@example.com")
        @NotBlank @Email @MaxLength("user.email") @AsciiOnly
        String email,

        @Schema(description = "The owner's name, for a new account", example = "Asha Kaur")
        @NotBlank @MaxLength("user.full-name")
        String fullName,

        @Schema(description = """
                An existing account's password, or a new account's: at least 8 characters and at most 72 \
                bytes in UTF-8, with an upper-case letter, a lower-case letter, a digit and one other character.""",
                example = "Correct-Horse-9")
        @PasswordPolicy
        String password) {
}
