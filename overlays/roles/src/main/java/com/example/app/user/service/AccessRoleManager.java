package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.Permission;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.domain.EffectiveAccess;
import com.example.app.user.dto.request.AccessRoleCreateRequest;
import com.example.app.user.dto.request.AccessRoleUpdateRequest;
import com.example.app.user.dto.response.AccessRoleResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * A role's create and edit, which read two aggregates (house-review ARCH-15's Manager): the role
 * ({@link AccessRoleService}) and the caller's access ({@link PersonAccessService}). It owns the transaction and
 * holds the safety rules about a role: its permissions are only ones the caller holds (rule 1, 403
 * {@code ACCESS_NOT_HELD}; an edit also only of a role whose every permission they hold), each with its prerequisites (rule 4, 400 {@code ROLE_PREREQUISITE_MISSING}, nothing filled
 * in), and an edit that would leave no active account holding what manages access for good is refused (rule 6, 409
 * {@code ACCESS_LAST_MANAGER}). An edit locks first ({@link PersonAccessService#lockManagersWith}) and checks against
 * what the caller holds under that lock; a create gives nobody anything, so it locks nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessRoleManager {

    private final AccessRoleService roles;
    private final PersonAccessService access;

    /** 409 {@code ROLE_CODE_TAKEN}. */
    @Transactional
    public ObjectWrapper<AccessRoleResponse> create(UUID callerId, AccessRoleCreateRequest request) {
        EffectiveAccess caller = access.accessOf(callerId);
        if (!caller.holds(Permission.ROLES_MANAGE)) {
            throw AppException.of(ErrorCode.ACCESS_DENIED);
        }
        PersonAccessService.requireHeld(caller, request.permissions());
        requirePrerequisites(request.permissions());
        AccessRoleResponse created = roles.toResponse(roles.create(request));
        log.info("Role created: roleId={}, code={}", created.id(), created.code());
        return ObjectWrapper.withObject(created);
    }

    /** Its name and permissions; 404 {@code ROLE_NOT_FOUND}, 409 {@code OPTIMISTIC_LOCK_CONFLICT}. */
    @Transactional
    public ObjectWrapper<AccessRoleResponse> update(UUID callerId, UUID id, AccessRoleUpdateRequest request) {
        EffectiveAccess caller = access.lockManagersWith(callerId, Permission.ROLES_MANAGE);
        // Both what it gives now and what it will: an editor never takes from a role (and from everyone above them who
        // holds it) a permission they don't hold themselves.
        Set<Permission> before = roles.getRole(id).permissions();
        PersonAccessService.requireHeld(caller, before);
        PersonAccessService.requireHeld(caller, request.permissions());
        // Safety rule 2: adding to a role the editor holds would change their own access (a grant of theirs that ends
        // would outlive its end through the role).
        if (!before.containsAll(request.permissions()) && access.holdsRole(callerId, id)) {
            throw AppException.of(ErrorCode.ACCESS_OWN_CHANGE);
        }
        requirePrerequisites(request.permissions());
        AccessRoleResponse updated = roles.toResponse(roles.update(id, request));
        access.requireManagersRemain();
        log.info("Role changed: roleId={}, version={}", id, updated.version());
        return ObjectWrapper.withObject(updated);
    }

    /** Safety rule 4: every permission's prerequisites are among them; the first one missing is named, in enum order. */
    private static void requirePrerequisites(Set<Permission> permissions) {
        Set<Permission> ordered = EnumSet.noneOf(Permission.class);
        ordered.addAll(permissions);
        for (Permission permission : ordered) {
            Set<Permission> missing = EnumSet.noneOf(Permission.class);
            missing.addAll(permission.prerequisites());
            missing.removeAll(ordered);
            if (!missing.isEmpty()) {
                throw AppException.of(ErrorCode.ROLE_PREREQUISITE_MISSING, permission, missing.iterator().next());
            }
        }
    }
}
