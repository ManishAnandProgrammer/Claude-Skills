package com.example.app.user.dto.request;

import com.example.app.common.validation.MaxUtf8Bytes;
import com.example.app.common.validation.PasswordPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "The current password and the new one")
public record PasswordChangeRequest(
        @Schema(description = "The password the account signs in with now", example = "Correct-Horse-9")
        // bcrypt reads only the first 72 bytes: a longer password can't be the right one.
        @NotBlank @MaxUtf8Bytes(72)
        String currentPassword,

        @Schema(description = """
                At most 72 bytes in UTF-8 (a Gurmukhi letter takes 3), with an \
                upper-case letter, a lower-case letter, a digit and one other character; not from a known breach.""",
                example = "Battery-Staple-7")
        @PasswordPolicy
        String newPassword) {
}
