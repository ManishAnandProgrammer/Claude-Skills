package com.example.app.user.service;

import com.example.app.common.security.Permission;
import com.example.app.common.security.TokenPermissions;
import com.example.app.user.domain.Role;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

@Slf4j(topic = "com.example.app.authorization")
@Component
class RolePermissions implements TokenPermissions {

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

    @EventListener(ApplicationReadyEvent.class)
    public void logRolePermissions() {
        if (!log.isDebugEnabled()) {
            return;
        }
        for (Role role : Role.values()) {
            log.debug("Role {} grants {}", role, new TreeSet<>(role.permissions()));
        }
    }
}
