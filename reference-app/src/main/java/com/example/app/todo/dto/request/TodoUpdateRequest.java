package com.example.app.todo.dto.request;

import com.example.app.common.limits.LatestDate;
import com.example.app.common.limits.MaxLength;
import com.example.app.todo.domain.TodoPriority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
// media:begin
import java.util.Set;
// media:end
import java.util.UUID;

@Schema(description = "A todo's new content; its status changes through PATCH .../status")
public record TodoUpdateRequest(
        @Schema(description = "One of your lists; another one moves the todo", example = "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d")
        @NotNull
        UUID todoListId,

        @Schema(description = "What to do", example = "Buy oat milk")
        @NotBlank @MaxLength("todo.title")
        String title,

        @Schema(description = "Free-form notes; null or blank removes them (returned as null)")
        @MaxLength("todo.notes")
        String notes,

        @Schema(description = "Priority", example = "HIGH")
        @NotNull
        TodoPriority priority,

        @Schema(description = "Due date (yyyy-MM-dd), up to a configured latest date; empty for none", example = "2026-11-20")
        @LatestDate("todo.due-date")
        LocalDate dueDate,

        // media:begin
        @Schema(description = """
                uploadKeys from POST /upload-urls (purpose TODO_ATTACHMENT) of files to attach; null, absent \
                or [] for none""",
                example = "[\"TODO_ATTACHMENT/0b6f1c1e-3c55-4c3a-9b1e-5f0c2d7a8e41\"]")
        Set<@NotBlank String> addAttachmentKeys,

        @Schema(description = """
                Ids of its attachments to remove (attachments[].id); null, absent or [] for none. With \
                addAttachmentKeys, the todo's files must end within the purpose's minFiles and maxFiles: \
                removing below the minimum needs files added in the same request.""")
        Set<@NotNull UUID> removeAttachmentIds,

        // media:end
        @Schema(description = "The version you last read; a stale one is refused with 409", example = "0")
        @NotNull
        Long version) {
}
