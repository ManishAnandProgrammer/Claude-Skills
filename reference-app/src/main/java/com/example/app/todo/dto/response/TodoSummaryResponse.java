package com.example.app.todo.dto.response;

import com.example.app.todo.domain.TodoPriority;
import com.example.app.todo.domain.TodoStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A todo as listed: the detail ({@link TodoResponse}) adds the notes. */
@Schema(description = "A todo in a list")
public record TodoSummaryResponse(
        @Schema(description = "Todo id") UUID id,
        @Schema(description = "Its list") UUID todoListId,
        @Schema(description = "What to do", example = "Buy oat milk") String title,
        @Schema(description = "OPEN or DONE", example = "OPEN") TodoStatus status,
        @Schema(description = "Priority", example = "MEDIUM") TodoPriority priority,
        @Schema(description = "Due date", example = "2026-11-20") LocalDate dueDate,
        @Schema(description = "When it was marked done; empty while OPEN") Instant completedAt,
        @Schema(description = "When it was created") Instant createdAt,
        @Schema(description = "When it last changed") Instant updatedAt,
        @Schema(description = "Who created it") UUID createdBy,
        @Schema(description = "Who last changed it") UUID updatedBy,
        @Schema(description = "Send it back when changing the todo", example = "0") Long version) {
}
