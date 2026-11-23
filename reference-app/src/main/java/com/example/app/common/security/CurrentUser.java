package com.example.app.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class CurrentUser {

    public UUID id() {
        return findId().orElseThrow(() -> new IllegalStateException("No authenticated user in this request"));
    }

    public Optional<UUID> findId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (Objects.nonNull(authentication) && authentication.getPrincipal() instanceof Jwt jwt) {
            return Optional.ofNullable(jwt.getSubject()).map(UUID::fromString);
        }
        return Optional.empty();
    }

    public Set<Permission> permissions() {
        Set<Permission> held = EnumSet.noneOf(Permission.class);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (Objects.isNull(authentication)) {
            return held;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            for (Permission permission : Permission.values()) {
                if (permission.name().equals(authority.getAuthority())) {
                    held.add(permission);
                }
            }
        }
        return held;
    }

    public boolean holds(Permission permission) {
        return permissions().contains(permission);
    }
}
