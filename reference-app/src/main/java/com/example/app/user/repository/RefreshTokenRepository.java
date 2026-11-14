package com.example.app.user.repository;

import com.example.app.user.domain.RefreshToken;
import com.example.app.user.domain.RevocationReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes the token if it is still live, in one statement: of two writers racing on one token (two
     * refreshes, or a refresh and a logout) exactly one gets 1, the other waits for its row lock, re-checks
     * {@code revoked_at} and gets 0. A bulk update: sets {@code updated_at} and bumps {@code version}
     * itself; it clears the persistence context, so a token read before it is detached and stale.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revokedAt = :now, t.revocationReason = :reason,
                   t.updatedAt = :now, t.version = t.version + 1
             where t.tokenHash = :tokenHash and t.revokedAt is null""")
    int revokeIfLive(String tokenHash, RevocationReason reason, Instant now);

    /**
     * Revokes every live token of a user for reuse. A bulk update: sets {@code updated_at} and bumps
     * {@code version} itself.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revokedAt = :now, t.revocationReason = com.example.app.user.domain.RevocationReason.REUSE,
                   t.updatedAt = :now, t.version = t.version + 1
             where t.user.id = :userId and t.revokedAt is null""")
    int revokeAllOfUserForReuse(UUID userId, Instant now);

    @Modifying
    @Query(value = """
            delete from refresh_tokens
             where id in (select id from refresh_tokens where expires_at < :cutoff limit :batchSize)""",
            nativeQuery = true)
    int deleteExpiredBefore(Instant cutoff, int batchSize);
}
