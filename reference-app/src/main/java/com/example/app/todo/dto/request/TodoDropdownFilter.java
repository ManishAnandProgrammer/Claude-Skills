package com.example.app.todo.dto.request;

import com.example.app.common.dropdown.DropdownFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class TodoDropdownFilter extends DropdownFilter {

    @NotNull
    @Schema(description = "The list whose todos to offer (another user's list, or an unknown id, offers none)",
            example = "0190a9c2-7d10-7b2c-8e3f-1a2b3c4d5e6f")
    private UUID todoListId;
}
