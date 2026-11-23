package com.example.app.common.security;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One reason you may not do an action")
public record AllowedActionReason(
        @Schema(description = "The errorCode the action's call would answer for this reason", example = "TODO_NOT_YOURS")
        String errorCode,
        @Schema(description = """
                The reason in the request's language (Accept-Language): the detail the action's error response \
                would give""", example = """
                Todo 0190a9c2-7d11-7f0e-8a3b-2c4d6e8f0a1b isn't yours: you asked for it, so you may read it, but \
                only its owner can change it.""")
        String message) {
}
