package com.example.app.user.service;

import com.example.app.common.security.Permission;
import com.example.app.common.security.TokenPermissions;
import com.example.app.user.domain.Role;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A token's permissions are its role's ({@link Role#permissions()}): the role is the one {@link TokenService}
 * wrote into the {@code roles} claim. A name that is no {@link Role} (a platform token's staff role, a role
 * removed since the token was issued) grants nothing.
 */
@Component
class RolePermissions implements TokenPermissions {

    /** The claim TokenService writes the account's (with tenancy, the membership's) one role into. */
    static final String ROLES_CLAIM = "roles";

    @Override
    public Set<Permission> of(Jwt token) {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        List<String> roles = token.getClaimAsStringList(ROLES_CLAIM);
        if (Objects.isNull(roles)) {
            return permissions;
        }
        for (Role role : Role.values()) {
            if (roles.contains(role.name())) {
                permissions.addAll(role.permissions());
            }
        }
        return permissions;
    }
}
