package com.example.app.user.repository;

import com.example.app.user.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Locks the row: of two refreshes with the same token, the second waits and then sees it revoked. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

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
