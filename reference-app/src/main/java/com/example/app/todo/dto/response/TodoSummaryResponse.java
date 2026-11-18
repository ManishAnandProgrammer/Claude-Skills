package com.example.app.todo.dto.response;

import com.example.app.todo.domain.TodoPriority;
import com.example.app.todo.domain.TodoStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A todo as listed: the detail ({@link TodoResponse}) adds the notes. Its list's name comes in a second step
 * (TodoService.findByFilter: one lookup of the batch's lists), the requester as an id (the detail shows more).
 */
@Schema(description = "A todo in a list")
public record TodoSummaryResponse(
        @Schema(description = "Todo id") UUID id,
        @Schema(description = "Its list") UUID todoListId,
        @Schema(description = "Its list, with its name") TodoTodoListResponse todoList,
        // metadata:begin
        @Schema(description = "Its display number (GET /todos/{id} says how it is counted)", example = "42") Long number,
        // metadata:end
        @Schema(description = "Who asked for it; null for none") UUID requesterId,
        @Schema(description = "What to do", example = "Buy oat milk") String title,
        @Schema(description = "OPEN or DONE", example = "OPEN") TodoStatus status,
        @Schema(description = "Priority", example = "MEDIUM") TodoPriority priority,
        @Schema(description = "Due date", example = "2026-11-20") LocalDate dueDate,
        @Schema(description = "When it was marked done; empty while OPEN") Instant completedAt,
        @Schema(description = "Its tags, in the order they were created; empty for none") List<TodoTagResponse> tags,
        @Schema(description = "When it was created") Instant createdAt,
        @Schema(description = "When it last changed") Instant updatedAt,
        @Schema(description = "Who created it") UUID createdBy,
        @Schema(description = "Who last changed it") UUID updatedBy,
        @Schema(description = "Send it back when changing the todo", example = "0") Long version) {

    public TodoSummaryResponse {
        tags = List.copyOf(tags);
    }

    /** The batch's projection: the row without its list's name and its tags, which the service reads in two more steps. */
    public TodoSummaryResponse(UUID id, UUID todoListId,
                               // metadata:begin
                               Long number,
                               // metadata:end
                               UUID requesterId, String title, TodoStatus status, TodoPriority priority,
                               LocalDate dueDate, Instant completedAt, Instant createdAt, Instant updatedAt,
                               UUID createdBy, UUID updatedBy, Long version) {
        this(id, todoListId, null,
                // metadata:begin
                number,
                // metadata:end
                requesterId, title, status, priority, dueDate, completedAt, List.of(), createdAt, updatedAt, createdBy, updatedBy, version);
    }

    public TodoSummaryResponse withTodoList(TodoTodoListResponse rowList) {
        return new TodoSummaryResponse(id, todoListId, rowList,
                // metadata:begin
                number,
                // metadata:end
                requesterId, title, status, priority, dueDate, completedAt, tags, createdAt, updatedAt, createdBy, updatedBy, version);
    }

    public TodoSummaryResponse withTags(List<TodoTagResponse> rowTags) {
        return new TodoSummaryResponse(id, todoListId, todoList,
                // metadata:begin
                number,
                // metadata:end
                requesterId, title, status, priority, dueDate, completedAt, rowTags, createdAt, updatedAt, createdBy, updatedBy, version);
    }
}
