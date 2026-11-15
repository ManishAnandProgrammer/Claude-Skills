package com.example.app.user.dto.request;

import com.example.app.user.domain.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "An account's new status")
public record UserStatusRequest(
        @Schema(description = """
                SUSPENDED ends every session of the account and refuses it every new one; ACTIVE lets it sign \
                in again (the sessions a suspension ended stay ended). The current status again changes nothing.""",
                example = "SUSPENDED")
        @NotNull
        UserStatus status,

        @Schema(description = "The version you last read; a stale one is refused with 409", example = "0")
        @NotNull
        Long version) {
}
