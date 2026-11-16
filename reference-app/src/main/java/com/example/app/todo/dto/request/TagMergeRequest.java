package com.example.app.todo.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "The tag merged into the one in the path: its todos carry the path's tag instead, and it is deleted")
public record TagMergeRequest(
        @Schema(description = "Id of another of your tags", example = "0190a9c2-8e21-7a3c-9b4d-3e5f7a9b1c2d")
        @NotNull
        UUID sourceTagId) {
}
