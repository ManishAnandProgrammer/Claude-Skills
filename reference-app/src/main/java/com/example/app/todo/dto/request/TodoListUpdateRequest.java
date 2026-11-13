package com.example.app.todo.dto.request;

import com.example.app.common.limits.MaxLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "A todo list's new state")
public record TodoListUpdateRequest(
        @Schema(description = "Name, unique among your lists ignoring case", example = "Weekly groceries")
        @NotBlank @MaxLength("todo-list.name")
        String name,

        @Schema(description = "The version you last read; a stale one is refused with 409", example = "0")
        @NotNull
        Long version) {
}
