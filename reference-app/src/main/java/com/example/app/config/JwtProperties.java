package com.example.app.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(@NotBlank String secret,
                            @NotBlank String issuer,
                            @NotNull Duration accessTokenTtl,
                            @NotNull Duration refreshTokenTtl,
                            @NotNull Duration rotationGrace) {

    private static final int HS256_MIN_KEY_BYTES = 32;
    // A token's exp is whole seconds; a bare number (JWT_ACCESS_TTL=15) binds as milliseconds and fails this.
    private static final Duration MIN_ACCESS_TOKEN_TTL = Duration.ofSeconds(1);
    private static final Duration MAX_ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    // Sessions last days; an hour's floor refuses a bare number of seconds or days, which binds as milliseconds.
    private static final Duration MIN_REFRESH_TOKEN_TTL = Duration.ofHours(1);

    public JwtProperties {
        if (Objects.nonNull(secret) && secret.getBytes(StandardCharsets.UTF_8).length < HS256_MIN_KEY_BYTES) {
            throw new IllegalArgumentException("app.jwt.secret (JWT_SECRET) must be at least 32 bytes");
        }
        if (Objects.nonNull(accessTokenTtl) && (accessTokenTtl.compareTo(MIN_ACCESS_TOKEN_TTL) < 0
                || accessTokenTtl.compareTo(MAX_ACCESS_TOKEN_TTL) > 0)) {
            throw new IllegalArgumentException("""
                    app.jwt.access-token-ttl (JWT_ACCESS_TTL) must be between 1s and 15m: a stolen access token \
                    works until it expires""");
        }
        if (Objects.nonNull(refreshTokenTtl) && refreshTokenTtl.compareTo(MIN_REFRESH_TOKEN_TTL) < 0) {
            throw new IllegalArgumentException("app.jwt.refresh-token-ttl (JWT_REFRESH_TTL) must be at least 1h");
        }
        if (Objects.nonNull(rotationGrace) && Objects.nonNull(refreshTokenTtl)
                && (rotationGrace.isNegative() || rotationGrace.compareTo(refreshTokenTtl) >= 0)) {
            throw new IllegalArgumentException("""
                    app.jwt.rotation-grace must be at least 0s and shorter than refresh-token-ttl: within it, a \
                    rotated token presented again is taken for a client's duplicate, never for reuse""");
        }
    }
}
