package com.example.app.common.security;

import org.springframework.security.core.Authentication;
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

    /** The caller's id; throws when there is none, which on an authenticated endpoint is a bug. */
    public UUID id() {
        return findId().orElseThrow(() -> new IllegalStateException("No authenticated user in this request"));
    }

    /** The caller's id, empty outside an authenticated request (signup, scheduled jobs). */
    public Optional<UUID> findId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (Objects.nonNull(authentication) && authentication.getPrincipal() instanceof Jwt jwt) {
            return Optional.of(UUID.fromString(jwt.getSubject()));
        }
        return Optional.empty();
    }
}
