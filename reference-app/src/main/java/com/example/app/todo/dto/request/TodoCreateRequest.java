package com.example.app.todo.dto.request;

import com.example.app.common.limits.MaxLength;
import com.example.app.todo.domain.TodoPriority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "A new todo; it starts OPEN")
public record TodoCreateRequest(
        @Schema(description = "One of your lists", example = "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d")
        @NotNull
        UUID todoListId,

        @Schema(description = "What to do", example = "Buy oat milk")
        @NotBlank @MaxLength("todo.title")
        String title,

        @Schema(description = "Free-form notes", example = "The barista one, not the light one")
        @MaxLength("todo.notes")
        String notes,

        @Schema(description = "Priority", example = "MEDIUM")
        @NotNull
        TodoPriority priority,

        @Schema(description = "Due date (yyyy-MM-dd); empty for none", example = "2026-11-20")
        LocalDate dueDate) {
}
