package com.example.app.user.dto.response;

import com.example.app.common.security.Area;
import com.example.app.common.security.Permission;
import com.example.app.common.security.Reach;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "One permission, as a role editor needs it; its label is the client's")
public record PermissionDescription(
        @Schema(description = "The permission", example = "ACCOUNT_STATUS_CHANGE") Permission code,
        @Schema(description = "The menu area it belongs to", example = "ACCOUNTS") Area area,
        @Schema(description = "Which records it reaches: OWN, ASSIGNED or ALL", example = "ALL") Reach reach,
        @Schema(description = "What it needs to be usable: a role holding it holds these too") List<Permission> prerequisites) {

    public PermissionDescription {
        prerequisites = List.copyOf(prerequisites);
    }
}
