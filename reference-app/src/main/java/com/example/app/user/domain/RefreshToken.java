package com.example.app.user.domain;

import com.example.app.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A refresh token, stored only as its SHA-256 hash (the token itself is 256 random bits, so a fast
 * deterministic hash is enough and allows lookup). Single use: refreshing revokes it and issues a
 * new one.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_reason")
    private RevocationReason revocationReason;

    public static RefreshToken issue(User user, String tokenHash, Instant expiresAt) {
        return RefreshToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .expiresAt(expiresAt)
                .build();
    }

    public boolean isRevoked() {
        return Objects.nonNull(revokedAt);
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /** Stops the token working; a token already revoked keeps its first time and reason. */
    public void revoke(RevocationReason reason, Instant now) {
        if (!isRevoked()) {
            revokedAt = now;
            revocationReason = reason;
        }
    }

    /**
     * Rotated less than {@code grace} ago: a second refresh with it is most likely a client
     * duplicate (two tabs, a retry after a timeout), not a stolen token.
     */
    public boolean wasRotatedWithin(Duration grace, Instant now) {
        return revocationReason == RevocationReason.ROTATED && revokedAt.plus(grace).isAfter(now);
    }
}
