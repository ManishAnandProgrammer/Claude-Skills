package com.example.app.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * {@code app.jwt.*}. The secret has no default: startup fails naming {@code JWT_SECRET} when it is
 * missing, and refuses one shorter than 256 bits (HS256's key size).
 *
 * @param secret          HMAC key, at least 32 bytes
 * @param issuer          the {@code iss} claim written and required
 * @param accessTokenTtl  access tokens aren't checked against the database, so this bounds how long
 *                        a disabled account or a changed role keeps working
 *   <!-- session-version:begin -->
 *                        (with the session version, an access change ends them at once; this still
 *                        bounds a token stolen without the account changing)
 *   <!-- session-version:end -->
 * @param refreshTokenTtl lifetime of a refresh token (rotated on every use)
 * @param rotationGrace   a refresh token presented again this soon after its rotation is a client
 *                        duplicate, not a leak: refused without revoking every session
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(@NotBlank String secret,
                            @NotBlank String issuer,
                            @NotNull Duration accessTokenTtl,
                            @NotNull Duration refreshTokenTtl,
                            @NotNull Duration rotationGrace) {

    private static final int MIN_SECRET_BYTES = 32;
    // A token's exp is whole seconds; a bare number (JWT_ACCESS_TTL=15) binds as milliseconds and fails this.
    private static final Duration MIN_ACCESS_TOKEN_TTL = Duration.ofSeconds(1);
    /** The review profile's {@code tokenRevocation: short-lived(15)}. */
    private static final Duration MAX_ACCESS_TOKEN_TTL = Duration.ofMinutes(15);

    public JwtProperties {
        if (Objects.nonNull(secret) && secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("app.jwt.secret (JWT_SECRET) must be at least 32 bytes");
        }
        if (Objects.nonNull(accessTokenTtl) && (accessTokenTtl.compareTo(MIN_ACCESS_TOKEN_TTL) < 0
                || accessTokenTtl.compareTo(MAX_ACCESS_TOKEN_TTL) > 0)) {
            throw new IllegalArgumentException("""
                    app.jwt.access-token-ttl (JWT_ACCESS_TTL) must be between 1s and 15m: access tokens aren't \
                    checked against the database, so this bounds how long a revoked session lives""");
        }
        if (Objects.nonNull(refreshTokenTtl) && !refreshTokenTtl.isPositive()) {
            throw new IllegalArgumentException("app.jwt.refresh-token-ttl (JWT_REFRESH_TTL) must be positive");
        }
        if (Objects.nonNull(rotationGrace) && Objects.nonNull(refreshTokenTtl)
                && (rotationGrace.isNegative() || rotationGrace.compareTo(refreshTokenTtl) >= 0)) {
            throw new IllegalArgumentException("""
                    app.jwt.rotation-grace must be at least 0s and shorter than refresh-token-ttl: within it, a \
                    rotated token presented again is taken for a client's duplicate, never for reuse""");
        }
    }
}
