package com.example.app.todo.dto.request;

import com.example.app.common.limits.MaxLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "A new tag; it starts active")
public record TagCreateRequest(
        @Schema(description = "Name, unique among your tags ignoring case and surrounding spaces", example = "Urgent")
        @NotBlank @MaxLength("tag.name")
        String name,

        @Schema(description = "Colour as #RRGGBB, stored upper-case; null, absent or empty for none (returned as null)", example = "#FF8800")
        @Pattern(regexp = TagColor.PATTERN, message = TagColor.MESSAGE)
        String color) {
}
