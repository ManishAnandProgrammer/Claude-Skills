package com.example.app.metadata.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

@Schema(description = "A configuration's new value")
public record MetadataUpdateRequest(
        @Schema(description = """
                The whole value, shaped as the key's record (GET shows it); a field left out takes its \
                default. Unknown fields are refused.""",
                type = "object")
        @NotNull
        JsonNode value,

        @Schema(description = "The version you last read; a stale one is refused with 409", example = "0")
        @NotNull
        Long version) {
}
