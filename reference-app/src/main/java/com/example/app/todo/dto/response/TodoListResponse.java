package com.example.app.todo.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A todo list")
public record TodoListResponse(
        @Schema(description = "List id", example = "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d") UUID id,
        @Schema(description = "Name", example = "Groceries") String name,
        @Schema(description = "The owner's account id") UUID ownerId,
        @Schema(description = "When it was created") Instant createdAt,
        @Schema(description = "When it last changed") Instant updatedAt,
        @Schema(description = "Who created it") UUID createdBy,
        @Schema(description = "Who last changed it") UUID updatedBy,
        @Schema(description = "Send it back when updating", example = "0") Long version) {
}
