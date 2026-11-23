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

    public void revoke(RevocationReason reason, Instant now) {
        if (!isRevoked()) {
            revokedAt = now;
            revocationReason = reason;
        }
    }

    public boolean wasRotatedWithin(Duration grace, Instant now) {
        return revocationReason == RevocationReason.ROTATED && revokedAt.plus(grace).isAfter(now);
    }
}
