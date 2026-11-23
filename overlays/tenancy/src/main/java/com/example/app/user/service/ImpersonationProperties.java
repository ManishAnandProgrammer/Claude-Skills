package com.example.app.user.service;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Objects;

@Validated
@ConfigurationProperties(prefix = "app.tenancy.impersonation")
public record ImpersonationProperties(@NotNull Duration tokenTtl) {

    // A token's exp is whole seconds.
    private static final Duration SHORTEST = Duration.ofSeconds(1);
    /** The access token's bound ({@code short-lived(15)}): ending the staff member's access doesn't end the token. */
    private static final Duration LONGEST = Duration.ofMinutes(15);

    public ImpersonationProperties {
        if (Objects.nonNull(tokenTtl) && (tokenTtl.compareTo(SHORTEST) < 0 || tokenTtl.compareTo(LONGEST) > 0)) {
            throw new IllegalArgumentException("app.tenancy.impersonation.token-ttl must be between 1s and 15m");
        }
    }
}
