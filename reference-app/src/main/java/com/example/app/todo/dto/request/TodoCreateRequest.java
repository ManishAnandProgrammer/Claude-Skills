package com.example.app.todo.dto.request;

import com.example.app.common.limits.ConfiguredMaxSize;
import com.example.app.common.limits.EarliestDate;
import com.example.app.common.limits.LatestDate;
import com.example.app.common.limits.MaxLength;
import com.example.app.todo.domain.TodoPriority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Schema(description = "A new todo; it starts OPEN")
public record TodoCreateRequest(
        @Schema(description = "One of your lists", example = "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d")
        @NotNull
        UUID todoListId,

        @Schema(description = "What to do", example = "Buy oat milk")
        @NotBlank @MaxLength("todo.title")
        String title,

        @Schema(description = "Free-form notes; null, absent or blank for none (returned as null)", example = "The barista one, not the light one")
        @MaxLength("todo.notes")
        String notes,

        @Schema(description = "Priority", example = "MEDIUM")
        @NotNull
        TodoPriority priority,

        @Schema(description = "Due date (yyyy-MM-dd), up to a configured latest date; empty for none", example = "2026-11-20")
        @EarliestDate("todo.due-date") @LatestDate("todo.due-date")
        LocalDate dueDate,
        // tenancy:begin
        // @Schema(description = """
        //         The member of your organisation who asked for it (their account id); null or absent for none. \
        //         A PUT replaces it.""",
        //         example = "0190a9c2-5b21-7d3e-8f4a-2b3c4d5e6f7a")
        // tenancy:else
        @Schema(description = """
                Another account that asked for it (its id), never yours; null or absent for none. A PUT replaces it.""",
                example = "0190a9c2-5b21-7d3e-8f4a-2b3c4d5e6f7a")
        // tenancy:end
        UUID requesterId,

        // media:begin
        @Schema(description = """
                uploadKeys from POST /upload-urls (purpose TODO_ATTACHMENT) of files you uploaded, to attach; \
                null, absent or [] for none. Their number must be within the purpose's minFiles and maxFiles \
                (GET /upload-policies), and never more than a configured maximum. A repeated key counts once.""",
                // tenancy:begin
                // example = "[\"0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d/TODO_ATTACHMENT/0b6f1c1e-3c55-4c3a-9b1e-5f0c2d7a8e41\"]")
                // tenancy:else
                example = "[\"TODO_ATTACHMENT/0b6f1c1e-3c55-4c3a-9b1e-5f0c2d7a8e41\"]")
                // tenancy:end
        @ConfiguredMaxSize("upload-policy.max-files")
        Set<@NotBlank @MaxLength("media.upload-key") String> attachmentKeys,

        // media:end
        @Schema(description = """
                Ids of your tags to put on it, in any order, up to a configured maximum; null, absent or [] \
                for none. A repeated id counts once. Later changes go through PUT /todos/{id}/tags.""",
                example = "[\"0190a9c2-8e21-7a3c-9b4d-3e5f7a9b1c2d\"]")
        @ConfiguredMaxSize("todo.max-tags")
        Set<@NotNull UUID> tagIds) {

    // Jackson builds it before validation: a null, or a null element, reaches the constraints that name it.
    public TodoCreateRequest {
        // media:begin
        attachmentKeys = Objects.isNull(attachmentKeys) ? null : Collections.unmodifiableSet(attachmentKeys);
        // media:end
        tagIds = Objects.isNull(tagIds) ? null : Collections.unmodifiableSet(tagIds);
    }
}
