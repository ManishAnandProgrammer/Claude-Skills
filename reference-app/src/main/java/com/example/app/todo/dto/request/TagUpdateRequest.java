package com.example.app.todo.dto.request;

import com.example.app.common.limits.MaxLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "A tag's new state")
public record TagUpdateRequest(
        @Schema(description = "Name, unique among your tags ignoring case and surrounding spaces", example = "Urgent")
        @NotBlank @MaxLength("tag.name")
        String name,

        @Schema(description = "Colour as #RRGGBB, stored upper-case; null or empty removes it (returned as null)", example = "#FF8800")
        @Pattern(regexp = TagColor.PATTERN, message = TagColor.MESSAGE)
        String color,

        @Schema(description = "false hides the tag from the dropdown's default (ACTIVE) view", example = "true")
        @NotNull
        Boolean active,

        @Schema(description = "The version you last read; a stale one is refused with 409", example = "0")
        @NotNull
        Long version) {
}
