package com.example.app.user.dto.response;

import com.example.app.common.security.Permission;
import com.example.app.user.domain.EffectiveAccess;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "A permission something gives a person but they don't hold, and why")
public record RemovedPermissionResponse(
        @Schema(description = "The permission", example = "ACCOUNT_STATUS_CHANGE") Permission permission,
        @Schema(description = "DENIED (a personal DENY), or PREREQUISITE_MISSING (one it needs isn't held)", example = "DENIED")
        EffectiveAccess.Removal reason,
        @Schema(description = "With PREREQUISITE_MISSING, the prerequisites not held") List<Permission> missing) {

    public RemovedPermissionResponse {
        missing = List.copyOf(missing);
    }
}
