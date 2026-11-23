package com.example.app.todo.dto.response;

import com.example.app.common.security.AllowedAction;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What you may do with this todo; the server checks again on every call")
public record TodoAllowedActionsResponse(
        @Schema(description = """
                Whether you may change it: its fields, status, tags, files and time entries, and its delete (with \
                the permission each needs). Not allowed for a todo you only requested (TODO_NOT_YOURS)""")
        AllowedAction edit) {
}
