package com.example.app.user.dto.response;

import com.example.app.common.security.Area;
import com.example.app.common.security.Permission;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What the caller may do, for a client to show its menus, tabs and buttons by; never which role holds what.
 * The server checks every request itself whatever the client shows.
 */
@Schema(description = "What the signed-in caller may do: the menu areas and the permissions")
public record AccessResponse(
        @ArraySchema(arraySchema = @Schema(description = """
                The menu areas the caller holds at least one permission of, in the API's order; a client shows a \
                menu item by its area"""))
        List<Area> areas,
        @ArraySchema(arraySchema = @Schema(description = """
                The permissions the caller holds, in the API's order; a client shows a tab or a button by its \
                permission, or by a record's allowedActions where the record decides"""))
        List<Permission> permissions) {

    public AccessResponse {
        areas = List.copyOf(areas);
        permissions = List.copyOf(permissions);
    }
}
