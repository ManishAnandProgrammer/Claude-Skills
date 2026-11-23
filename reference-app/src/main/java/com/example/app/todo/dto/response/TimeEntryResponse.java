package com.example.app.todo.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Time spent on a todo")
public record TimeEntryResponse(
        @Schema(description = "Time entry id", example = "0190a9c2-8e22-7a1b-9c3d-4e5f6a7b8c9d") UUID id,
        @Schema(description = "Its todo") UUID todoId,
        @Schema(description = "The owner's account id") UUID ownerId,
        @Schema(description = "Minutes spent", example = "45") Integer minutes,
        @Schema(description = "The day the time was spent", example = "2026-11-12") LocalDate spentOn,
        @Schema(description = "What the time went on; null for none") String note,
        @Schema(description = "Whether the time is billed to a client", example = "true") boolean billable,
        @Schema(description = "When it was created") Instant createdAt,
        @Schema(description = "When it last changed") Instant updatedAt,
        @Schema(description = "Who created it") UUID createdBy,
        @Schema(description = "Who last changed it") UUID updatedBy,
        @Schema(description = "Send it back when updating", example = "0") Long version) {
}
