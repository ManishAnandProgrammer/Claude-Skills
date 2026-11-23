package com.example.app.todo.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "The list a todo is in")
public record TodoTodoListResponse(
        @Schema(description = "List id", example = "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d") UUID id,
        @Schema(description = "Its name", example = "Groceries") String name) {
}
