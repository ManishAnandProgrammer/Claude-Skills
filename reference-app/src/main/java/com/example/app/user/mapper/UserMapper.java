package com.example.app.user.mapper;

import com.example.app.common.security.Area;
import com.example.app.common.security.Permission;
import com.example.app.user.domain.User;
import com.example.app.user.dto.response.AccessResponse;
import com.example.app.user.dto.response.UserResponse;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Component
public class UserMapper {

    public UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                // tenancy:begin
                // tenancy:else
                // roles:begin
                // roles:else
                user.getRole(),
                // roles:end
                // tenancy:end
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getCreatedBy(),
                user.getUpdatedBy(),
                user.getVersion());
    }

    /** The areas are those of the held permissions; both in their enums' order, so the answer is stable. */
    public AccessResponse toAccessResponse(Set<Permission> held) {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        permissions.addAll(held);
        Set<Area> areas = EnumSet.noneOf(Area.class);
        for (Permission permission : permissions) {
            areas.add(permission.area());
        }
        return new AccessResponse(List.copyOf(areas), List.copyOf(permissions));
    }
}
