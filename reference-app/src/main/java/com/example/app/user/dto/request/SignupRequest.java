package com.example.app.user.dto.request;

import com.example.app.common.validation.MaxUtf8Bytes;
import io.swagger.v3.oas.annotations.media.Schema;
import com.example.app.common.limits.MaxLength;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "A new account")
public record SignupRequest(
        @Schema(description = "Email address (ASCII only); stored lower-case", example = "asha@example.com")
        @NotBlank @Email @MaxLength("user.email")
        @Pattern(regexp = "\\p{ASCII}+", message = "must contain only ASCII characters")
        String email,

        @Schema(description = "Name shown in the app", example = "Asha Kaur")
        @NotBlank @MaxLength("user.full-name")
        String fullName,

        @Schema(description = """
                At least 8 characters and at most 72 bytes in UTF-8 (a Gurmukhi letter takes 3), with \
                an upper-case letter, a lower-case letter, a digit and one other character.""",
                example = "Correct-Horse-9")
        @NotBlank
        // Security floors, not tunables: at least 8 characters, and bcrypt reads only the first 72 bytes.
        @Size(min = 8)
        @MaxUtf8Bytes(72)
        @Pattern(regexp = "^(?=.*\\p{Lu})(?=.*\\p{Ll})(?=.*\\d)(?=.*[^\\p{L}\\d]).*$",
                message = "must contain an upper-case letter, a lower-case letter, a digit and another character")
        String password) {
}
