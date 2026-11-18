package com.example.app.todo.dto.request;

import com.example.app.common.limits.ConfiguredMaxSize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Schema(description = "The tags a todo carries, as a whole")
public record TodoTagsRequest(
        @Schema(description = """
                Ids of your tags, in any order, up to a configured maximum; [] removes them all. A repeated \
                id counts once.""",
                example = "[\"0190a9c2-8e21-7a3c-9b4d-3e5f7a9b1c2d\"]")
        @NotNull @ConfiguredMaxSize("todo.max-tags")
        Set<@NotNull UUID> tagIds,

        @Schema(description = "The version you last read; a stale one is refused with 409", example = "0")
        @NotNull
        Long version) {

    // Jackson builds it before validation: a null, or a null element, reaches the constraints that name it.
    public TodoTagsRequest {
        tagIds = Objects.isNull(tagIds) ? null : Collections.unmodifiableSet(tagIds);
    }
}
