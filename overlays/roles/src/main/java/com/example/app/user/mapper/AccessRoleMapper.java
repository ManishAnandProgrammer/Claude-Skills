package com.example.app.user.mapper;

import com.example.app.common.security.Area;
import com.example.app.common.security.Permission;
import com.example.app.user.domain.AccessRole;
import com.example.app.user.dto.response.AccessRoleResponse;
import com.example.app.user.dto.response.PermissionCatalogueResponse;
import com.example.app.user.dto.response.PermissionDescription;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Component
public class AccessRoleMapper {

    /** Its permissions in the enum's order, so the answer is stable. */
    public AccessRoleResponse toResponse(AccessRole role) {
        return new AccessRoleResponse(role.getId(), role.getCode(), role.getName(), role.getBuiltIn(),
                List.copyOf(role.permissions()), role.getCreatedAt(), role.getUpdatedAt(), role.getCreatedBy(),
                role.getUpdatedBy(), role.getVersion());
    }

    /** Every permission and area, from the enums: the same list for everyone (no labels: they are the client's). */
    public PermissionCatalogueResponse toCatalogue() {
        List<PermissionDescription> permissions = Arrays.stream(Permission.values()).map(AccessRoleMapper::describe).toList();
        return new PermissionCatalogueResponse(permissions, List.of(Area.values()));
    }

    private static PermissionDescription describe(Permission permission) {
        Set<Permission> prerequisites = EnumSet.noneOf(Permission.class);
        prerequisites.addAll(permission.prerequisites());
        return new PermissionDescription(permission, permission.area(), permission.reach(), List.copyOf(prerequisites));
    }
}
