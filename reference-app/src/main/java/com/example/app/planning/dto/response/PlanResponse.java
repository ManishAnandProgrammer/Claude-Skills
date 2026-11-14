package com.example.app.planning.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Days set aside for one of your todos")
public record PlanResponse(
        @Schema(description = "Plan id", example = "0190a9c2-9a10-7c2d-8e3f-1a2b3c4d5e6f") UUID id,
        @Schema(description = "Its todo, with the title to show") PlanTodoResponse todo,
        @Schema(description = "The owner's account id") UUID ownerId,
        @Schema(description = "The first day", example = "2026-11-20") LocalDate startsOn,
        @Schema(description = "The last day, inclusive", example = "2026-11-22") LocalDate endsOn,
        @Schema(description = "When it was created") Instant createdAt,
        @Schema(description = "When it last changed") Instant updatedAt,
        @Schema(description = "Who created it") UUID createdBy,
        @Schema(description = "Who last changed it") UUID updatedBy,
        @Schema(description = "Its version; a plan never changes, so it stays 0", example = "0") Long version) {
}
