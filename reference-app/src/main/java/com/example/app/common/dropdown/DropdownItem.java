package com.example.app.common.dropdown;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

@Schema(description = "One dropdown option")
public record DropdownItem(
        @Schema(description = """
                The option's id: a UUID string or a number, depending on the resource. \
                Send it back as is.""", example = "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d")
        Object value,
        @Schema(description = "Text shown to the user", example = "Groceries")
        String label) {

    public DropdownItem {
        Objects.requireNonNull(value, "value must not be null: a Long, UUID or String id");
        Objects.requireNonNull(label, "label must not be null");
    }
}
