package com.example.app.user.service;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Objects;

/**
 * {@code app.tenancy.impersonation.*}.
 *
 * @param tokenTtl how long an impersonation token works; no refresh token: staff ask again. Between 1s (a token's
 *                 {@code exp} is whole seconds) and 15m: like an access token it is never checked against the
 *                 database, and ending the staff member's access doesn't end it
 */
@Validated
@ConfigurationProperties(prefix = "app.tenancy.impersonation")
public record ImpersonationProperties(@NotNull Duration tokenTtl) {

    private static final Duration SHORTEST = Duration.ofSeconds(1);
    /** As the access token's: the review profile's {@code tokenRevocation: short-lived(15)}. */
    private static final Duration LONGEST = Duration.ofMinutes(15);

    public ImpersonationProperties {
        if (Objects.nonNull(tokenTtl) && (tokenTtl.compareTo(SHORTEST) < 0 || tokenTtl.compareTo(LONGEST) > 0)) {
            throw new IllegalArgumentException("app.tenancy.impersonation.token-ttl must be between 1s and 15m");
        }
    }
}
