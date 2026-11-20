package com.example.app.common.dropdown;

import com.example.app.common.security.AllowedAction;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

/**
 * One option of a write's options ({@code GET /plans/todo-options}): a {@link DropdownItem}'s {@code value} and
 * {@code label}, and whether the write would take it now, with every reason when not, from the policy method the
 * write calls. A type of its own, so the plain dropdown stays exactly {@code {value, label}} and no option is
 * ever taken as selectable because a field is missing. The write still decides.
 */
@Schema(description = "One option, and whether the action it serves would take it now")
public record SelectableDropdownItem(
        @Schema(description = """
                The option's id: a UUID string or a number, depending on the resource. \
                Send it back as is.""", example = "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d")
        Object value,
        @Schema(description = "Text shown to the user", example = "Groceries")
        String label,
        @Schema(description = """
                Whether the action would take this option now: when not, show it disabled with its reasons, each \
                the error the action would answer""")
        AllowedAction selectable) {

    public SelectableDropdownItem {
        Objects.requireNonNull(value, "value must not be null: a Long, UUID or String id");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(selectable, "selectable must not be null: every option says whether it may be picked");
    }
}
