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

    /*
     * PostgreSQL computes the ARRAY(...) once (an init plan); as "id IN (SELECT ... LIMIT ...)" the planner may run
     * the subquery again per outer row and delete more than one batch in one transaction.
     */
    String DELETE_EXPIRED = """
            DELETE FROM refresh_tokens
            WHERE id = ANY (ARRAY(SELECT id
                                    FROM refresh_tokens
                                   WHERE expires_at < :expiredBefore
                                   LIMIT :batchSize
                                   FOR UPDATE SKIP LOCKED))""";

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Of two writers racing on one token (two refreshes, or a refresh and a logout) exactly one gets 1: the other
     * waits for its row lock, re-checks {@code revoked_at} and gets 0.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revokedAt = :now, t.revocationReason = :reason,
                   t.updatedAt = :now, t.version = t.version + 1
             where t.tokenHash = :tokenHash and t.revokedAt is null""")
    int revokeIfLive(String tokenHash, RevocationReason reason, Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revokedAt = :now, t.revocationReason = com.example.app.user.domain.RevocationReason.REUSE,
                   t.updatedAt = :now, t.version = t.version + 1
             where t.user.id = :userId and t.revokedAt is null""")
    int revokeAllOfUserForReuse(UUID userId, Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revokedAt = :now, t.revocationReason = :reason,
                   t.updatedAt = :now, t.version = t.version + 1
             where t.user.id = :userId and t.revokedAt is null""")
    int revokeAllOfUser(UUID userId, RevocationReason reason, Instant now);

    @Modifying
    @Query(nativeQuery = true, value = DELETE_EXPIRED)
    int deleteExpiredBefore(Instant expiredBefore, int batchSize);
}
