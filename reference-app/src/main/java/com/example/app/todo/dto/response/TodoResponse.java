package com.example.app.todo.dto.response;

import com.example.app.todo.domain.TodoPriority;
import com.example.app.todo.domain.TodoStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "A todo")
public record TodoResponse(
        @Schema(description = "Todo id", example = "0190a9c2-7d11-7f0e-8a3b-2c4d6e8f0a1b") UUID id,
        @Schema(description = "Its list") UUID todoListId,
        @Schema(description = "The owner's account id") UUID ownerId,
        @Schema(description = "What to do", example = "Buy oat milk") String title,
        @Schema(description = "Free-form notes; null for none") String notes,
        @Schema(description = "OPEN or DONE", example = "OPEN") TodoStatus status,
        @Schema(description = "Priority", example = "MEDIUM") TodoPriority priority,
        @Schema(description = "Due date", example = "2026-11-20") LocalDate dueDate,
        @Schema(description = "When it was marked done; empty while OPEN") Instant completedAt,
        @Schema(description = "When it was created") Instant createdAt,
        @Schema(description = "When it last changed") Instant updatedAt,
        @Schema(description = "Who created it") UUID createdBy,
        @Schema(description = "Who last changed it") UUID updatedBy,
        @Schema(description = "Send it back when updating", example = "0") Long version) {
}
