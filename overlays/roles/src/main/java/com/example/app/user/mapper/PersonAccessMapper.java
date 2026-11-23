package com.example.app.user.mapper;

import com.example.app.common.security.Permission;
import com.example.app.user.domain.EffectiveAccess;
import com.example.app.user.domain.PersonalGrant;
import com.example.app.user.domain.RoleAssignment;
import com.example.app.user.dto.response.AssignedRoleResponse;
import com.example.app.user.dto.response.HeldPermissionResponse;
import com.example.app.user.dto.response.PersonAccessResponse;
import com.example.app.user.dto.response.PersonalGrantResponse;
import com.example.app.user.dto.response.RemovedPermissionResponse;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class PersonAccessMapper {

    public PersonAccessResponse toResponse(UUID userId, List<RoleAssignment> assignments, List<PersonalGrant> grants,
                                           EffectiveAccess access) {
        List<AssignedRoleResponse> roles = assignments.stream()
                .map(PersonAccessMapper::toAssignedRole)
                .sorted(Comparator.comparing(AssignedRoleResponse::code))
                .toList();
        return new PersonAccessResponse(userId, roles,
                grants.stream().map(PersonAccessMapper::toGrant).toList(),
                access.granted().stream().map(PersonAccessMapper::toHeld).toList(),
                access.removed().stream().map(PersonAccessMapper::toRemoved).toList());
    }

    private static AssignedRoleResponse toAssignedRole(RoleAssignment assignment) {
        return new AssignedRoleResponse(assignment.getRole().getId(), assignment.getRole().getCode(),
                assignment.getRole().getName(), assignment.getValidUntil(), assignment.getCreatedAt(),
                assignment.getUpdatedAt(), assignment.getCreatedBy(), assignment.getUpdatedBy());
    }

    private static PersonalGrantResponse toGrant(PersonalGrant grant) {
        return new PersonalGrantResponse(grant.getPermission(), grant.getEffect(), grant.getValidUntil(),
                grant.getCreatedAt(), grant.getUpdatedAt(), grant.getCreatedBy(), grant.getUpdatedBy());
    }

    private static HeldPermissionResponse toHeld(EffectiveAccess.Granted granted) {
        return new HeldPermissionResponse(granted.permission(), granted.roleCodes(), granted.personallyAllowed());
    }

    /** The enum's order, so the answer is stable. */
    private static RemovedPermissionResponse toRemoved(EffectiveAccess.Removed removed) {
        Set<Permission> missing = EnumSet.noneOf(Permission.class);
        missing.addAll(removed.missing());
        return new RemovedPermissionResponse(removed.permission(), removed.reason(), List.copyOf(missing));
    }
}
