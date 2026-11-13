package com.example.app.todo.dto.request;

import com.example.app.todo.domain.TodoStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "The status to move a todo to")
public record TodoStatusRequest(
        @Schema(description = "DONE completes it; OPEN reopens it", example = "DONE")
        @NotNull
        TodoStatus status,

        @Schema(description = "The version you last read; a stale one is refused with 409", example = "0")
        @NotNull
        Long version) {
}
