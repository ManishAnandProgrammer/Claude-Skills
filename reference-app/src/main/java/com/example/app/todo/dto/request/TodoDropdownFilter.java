package com.example.app.todo.dto.request;

import com.example.app.common.dropdown.DropdownFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * The todo dropdown's parameters: a dependent dropdown, so its parent (the list) is mandatory. The parent is a
 * plain condition of the dropdown's one query, never looked up first: a list that is missing, or someone else's,
 * lists no todos, the same answer as an empty list of your own, so nothing tells ids apart.
 */
@Getter
@Setter
public class TodoDropdownFilter extends DropdownFilter {

    @NotNull
    @Schema(description = "The list whose todos to offer (another user's list, or an unknown id, offers none)",
            example = "0190a9c2-7d10-7b2c-8e3f-1a2b3c4d5e6f")
    private UUID todoListId;
}
