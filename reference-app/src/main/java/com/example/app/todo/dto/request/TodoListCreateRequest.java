package com.example.app.todo.dto.request;

import com.example.app.common.limits.MaxLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "A new todo list")
public record TodoListCreateRequest(
        @Schema(description = "Name, unique among your lists ignoring case", example = "Groceries")
        @NotBlank @MaxLength("todo-list.name")
        String name) {
}
