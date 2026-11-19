package com.example.app.todo.dto.response;

import com.example.app.todo.domain.TodoPriority;
import com.example.app.todo.domain.TodoStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "A todo")
public record TodoResponse(
        @Schema(description = "Todo id", example = "0190a9c2-7d11-7f0e-8a3b-2c4d6e8f0a1b") UUID id,
        @Schema(description = "Its list") UUID todoListId,
        @Schema(description = "Its list, with its name") TodoTodoListResponse todoList,
        // metadata:begin
        @Schema(description = """
                Its display number: 1 for the first todo, then one more for each (with tenancy, counted per \
                organisation). A deleted todo's number is not handed out again.""", example = "42") Long number,
        // metadata:end
        @Schema(description = "The owner's account id") UUID ownerId,
        // tenancy:begin
        // @Schema(description = "The member who asked for it; null for none") TodoRequesterResponse requester,
        // tenancy:else
        @Schema(description = "The account that asked for it; null for none") UUID requesterId,
        // tenancy:end
        @Schema(description = "What to do", example = "Buy oat milk") String title,
        @Schema(description = "Free-form notes; null for none") String notes,
        @Schema(description = "OPEN or DONE", example = "OPEN") TodoStatus status,
        @Schema(description = "Priority", example = "MEDIUM") TodoPriority priority,
        @Schema(description = "Due date", example = "2026-11-20") LocalDate dueDate,
        @Schema(description = "When it was marked done; empty while OPEN") Instant completedAt,
        @Schema(description = "Its tags, in the order they were created; empty for none") List<TodoTagResponse> tags,
        // media:begin
        @Schema(description = "Its files, in the order they were uploaded; empty for none") List<TodoAttachmentResponse> attachments,
        // media:end
        @Schema(description = "What you may do with it") TodoAllowedActionsResponse allowedActions,
        @Schema(description = "When it was created") Instant createdAt,
        @Schema(description = "When it last changed") Instant updatedAt,
        @Schema(description = "Who created it") UUID createdBy,
        @Schema(description = "Who last changed it") UUID updatedBy,
        @Schema(description = "Send it back when updating", example = "0") Long version) {

    public TodoResponse {
        tags = List.copyOf(tags);
        // media:begin
        attachments = List.copyOf(attachments);
        // media:end
    }
}
