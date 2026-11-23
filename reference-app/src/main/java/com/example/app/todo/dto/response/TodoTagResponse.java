package com.example.app.todo.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "A tag on a todo")
public record TodoTagResponse(
        @Schema(description = "Tag id", example = "0190a9c2-8e21-7a3c-9b4d-3e5f7a9b1c2d") UUID id,
        @Schema(description = "Its name", example = "Errands") String name) {
}
