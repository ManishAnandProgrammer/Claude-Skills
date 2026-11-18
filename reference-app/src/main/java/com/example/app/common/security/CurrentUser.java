package com.example.app.common.security;

import org.springframework.security.core.Authentication;
// media:begin
import org.springframework.security.core.GrantedAuthority;
// media:end
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The signed-in user of the current request, read from the access token: its subject is the
 * user's id. No database lookup: the token is the proof, checked by signature and expiry.
 */
@Component
public class CurrentUser {
    // media:begin

    private static final String ROLE_PREFIX = "ROLE_";
    // media:end

    /** The caller's id; throws when there is none, which on an authenticated endpoint is a bug. */
    public UUID id() {
        return findId().orElseThrow(() -> new IllegalStateException("No authenticated user in this request"));
    }

    /** The caller's id, empty outside an authenticated request (signup, scheduled jobs). */
    public Optional<UUID> findId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (Objects.nonNull(authentication) && authentication.getPrincipal() instanceof Jwt jwt) {
            return Optional.ofNullable(jwt.getSubject()).map(UUID::fromString);
        }
        return Optional.empty();
    }
    // media:begin

    /**
     * Whether the caller's token carries {@code role} (the {@code roles} claim). For a check that
     * depends on the request's data (the purpose of an upload), which an annotation can't express;
     * a role checked per endpoint is a meta-annotation such as {@link AdminOnly}.
     */
    public boolean hasRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (Objects.isNull(authentication)) {
            return false;
        }
        String authority = ROLE_PREFIX + role;
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(authority::equals);
    }
    // media:end
}
