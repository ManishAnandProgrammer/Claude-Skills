package com.example.app.todo.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A tag")
public record TagResponse(
        @Schema(description = "Tag id", example = "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d") UUID id,
        @Schema(description = "Name", example = "Urgent") String name,
        @Schema(description = "Colour as #RRGGBB; null for none", example = "#FF8800") String color,
        @Schema(description = "Whether it is in use: the dropdown lists only active tags by default (status ACTIVE)", example = "true") boolean active,
        @Schema(description = "The owner's account id") UUID ownerId,
        @Schema(description = "When it was created") Instant createdAt,
        @Schema(description = "When it last changed") Instant updatedAt,
        @Schema(description = "Who created it") UUID createdBy,
        @Schema(description = "Who last changed it") UUID updatedBy,
        @Schema(description = "Send it back when updating", example = "0") Long version) {
}
