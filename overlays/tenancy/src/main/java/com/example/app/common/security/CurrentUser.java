package com.example.app.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The signed-in user of the current request, read from the access token: its subject is the
 * user's id. No database lookup: the token is the proof, checked by signature and expiry.
 */
@Component
public class CurrentUser {

    /** RFC 8693's actor claim: on an impersonation token, the platform staff member acting as the user. */
    public static final String ACTOR_CLAIM = "act";
    public static final String ACTOR_SUBJECT = "sub";

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

    /**
     * The platform staff member acting as this user (an impersonation token's {@code act.sub}); empty on
     * the user's own token. {@link #id} stays the user: what they own and may do is theirs.
     */
    public Optional<UUID> actorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (Objects.nonNull(authentication) && authentication.getPrincipal() instanceof Jwt jwt
                && jwt.getClaim(ACTOR_CLAIM) instanceof Map<?, ?> actor && actor.get(ACTOR_SUBJECT) instanceof String subject) {
            return Optional.of(UUID.fromString(subject));
        }
        return Optional.empty();
    }

    /**
     * The permissions the caller holds: the request's authorities that name a {@link Permission} (the security
     * chain made them from the token, {@link TokenPermissions}); any other authority is ignored. Empty outside a
     * signed-in request.
     */
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

    /**
     * Whether the caller holds {@code permission}: for a check the request's data names (a dropdown's {@code for},
     * an upload's purpose), which no annotation can; a handler's own is {@link Requires}.
     */
    public boolean holds(Permission permission) {
        return permissions().contains(permission);
    }
}
