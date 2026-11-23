package com.example.app.todo.dto.request;

// media:begin
import com.example.app.common.limits.ConfiguredMaxSize;
// media:end
import com.example.app.common.limits.EarliestDate;
import com.example.app.common.limits.LatestDate;
import com.example.app.common.limits.MaxLength;
import com.example.app.todo.domain.TodoPriority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
// media:begin
import java.util.Collections;
import java.util.Objects;
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
        @EarliestDate("todo.due-date") @LatestDate("todo.due-date")
        LocalDate dueDate,

        // tenancy:begin
        // @Schema(description = """
        //         The member who asked for it; null or absent for none. The person it names already is kept even \
        //         after they left the organisation; someone new must be a member now.""",
        //         example = "0190a9c2-5b21-7d3e-8f4a-2b3c4d5e6f7a")
        // tenancy:else
        @Schema(description = """
                Another account that asked for it, never yours; null or absent for none. The account it names \
                already is kept; a new one must exist.""", example = "0190a9c2-5b21-7d3e-8f4a-2b3c4d5e6f7a")
        // tenancy:end
        UUID requesterId,

        // media:begin
        @Schema(description = """
                uploadKeys from POST /upload-urls (purpose TODO_ATTACHMENT) of files to attach; null, absent \
                or [] for none; never more than a configured maximum. A repeated key counts once.""",
                // tenancy:begin
                // example = "[\"0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d/TODO_ATTACHMENT/0b6f1c1e-3c55-4c3a-9b1e-5f0c2d7a8e41\"]")
                // tenancy:else
                example = "[\"TODO_ATTACHMENT/0b6f1c1e-3c55-4c3a-9b1e-5f0c2d7a8e41\"]")
                // tenancy:end
        @ConfiguredMaxSize("upload-policy.max-files")
        Set<@NotBlank @MaxLength("media.upload-key") String> addAttachmentKeys,

        @Schema(description = """
                Ids of its attachments to remove (attachments[].id); null, absent or [] for none; never more \
                than a configured maximum. With addAttachmentKeys, the todo's files must end within the \
                purpose's minFiles and maxFiles: removing below the minimum needs files added in the same \
                request.""")
        @ConfiguredMaxSize("upload-policy.max-files")
        Set<@NotNull UUID> removeAttachmentIds,

        // media:end
        @Schema(description = "The version you last read; a stale one is refused with 409", example = "0")
        @NotNull
        Long version) {
    // media:begin

    // Jackson builds it before validation: a null, or a null element, reaches the constraints that name it.
    public TodoUpdateRequest {
        addAttachmentKeys = Objects.isNull(addAttachmentKeys) ? null : Collections.unmodifiableSet(addAttachmentKeys);
        removeAttachmentIds = Objects.isNull(removeAttachmentIds) ? null : Collections.unmodifiableSet(removeAttachmentIds);
    }
    // media:end
}
