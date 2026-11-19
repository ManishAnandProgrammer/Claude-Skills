package com.example.app.todo.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the caller may do with this todo, decided by the rule its writes check (TodoPolicy): a button that depends
 * on the record shows by this, one that doesn't by the caller's permissions ({@code GET /users/me/access}).
 */
@Schema(description = "What you may do with this todo; the server checks again on every call")
public record TodoAllowedActionsResponse(
        @Schema(description = """
                Whether you may change it: its fields, status, tags, files and time entries, and its delete (with \
                the permission each needs). False for a todo you only requested""", example = "true")
        boolean edit) {
}
