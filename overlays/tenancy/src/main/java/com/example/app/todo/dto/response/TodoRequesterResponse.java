package com.example.app.todo.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "The member who asked for a todo")
public record TodoRequesterResponse(
        @Schema(description = "Their account id", example = "0190a9c2-5b21-7d3e-8f4a-2b3c4d5e6f7a") UUID id,
        @Schema(description = "Their full name; null once they have left the organisation (the todo stays)", example = "Ada Lovelace")
        String fullName) {
}
