package com.example.app.user.dto.response;

import com.example.app.common.security.Permission;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "A permission a person holds now, and where it comes from")
public record HeldPermissionResponse(
        @Schema(description = "The permission", example = "ROLES_MANAGE") Permission permission,
        @Schema(description = "The codes of their roles that give it", example = "[\"ADMIN\"]") List<String> roles,
        @Schema(description = "Whether a personal ALLOW gives it", example = "false") boolean personallyAllowed) {

    public HeldPermissionResponse {
        roles = List.copyOf(roles);
    }
}
