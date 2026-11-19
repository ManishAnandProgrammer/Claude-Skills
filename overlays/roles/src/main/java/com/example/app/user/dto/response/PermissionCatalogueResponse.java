package com.example.app.user.dto.response;

import com.example.app.common.security.Area;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Every permission and every area, for a role editor: what exists, never who holds it")
public record PermissionCatalogueResponse(
        @Schema(description = "Every permission, in the enum's order") List<PermissionDescription> permissions,
        @Schema(description = "Every area, in the enum's order") List<Area> areas) {

    public PermissionCatalogueResponse {
        permissions = List.copyOf(permissions);
        areas = List.copyOf(areas);
    }
}
