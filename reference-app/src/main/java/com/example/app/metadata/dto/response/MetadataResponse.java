package com.example.app.metadata.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A configuration")
public record MetadataResponse(
        @Schema(
                // example:begin
                example = "TODO_LIST_DEFAULTS",
                // example:end
                description = "Its key")
        String key,
        @Schema(description = "The value, shaped as the key's record, defaults filled in", type = "object")
        Object value,
        @Schema(description = "When it was created") Instant createdAt,
        @Schema(description = "When it last changed") Instant updatedAt,
        @Schema(description = "Who created it; null when seeded outside a request") UUID createdBy,
        @Schema(description = "Who last changed it") UUID updatedBy,
        @Schema(description = "Send it back when updating", example = "0") Long version) {
}
