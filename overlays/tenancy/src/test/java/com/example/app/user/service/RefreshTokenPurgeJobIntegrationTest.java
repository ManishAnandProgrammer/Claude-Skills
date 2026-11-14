package com.example.app.user.service;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// A platform job: it runs outside any tenant, as the scheduler runs it.
@WithoutTestTenant
class RefreshTokenPurgeJobIntegrationTest extends IntegrationTest {

    @Autowired
    private RefreshTokenPurgeJob job;

    /** Inserts a token directly; {@code expiresAt} is an SQL expression relative to now. */
    private UUID insertToken(UUID userId, String expiresAt, boolean revoked) {
        UUID id = UUID.randomUUID();
        sql("""
                insert into refresh_tokens (id, user_id, tenant_id, token_hash, expires_at, revoked_at, revocation_reason,
                                            created_at, updated_at, version)
                values (?, ?, ?, ?, %s, %s, %s, now(), now(), 0)
                """.formatted(expiresAt, revoked ? "now()" : "null", revoked ? "'ROTATED'" : "null"),
                id, userId, testTenant().id(), UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        return id;
    }

    private boolean exists(UUID tokenId) {
        Integer rows = jdbc.queryForObject("select count(*) from refresh_tokens where id = ?", Integer.class, tokenId);
        return rows == 1;
    }

    @Test
    @DisplayName("The purge deletes tokens expired over a day ago and keeps recent, live and revoked-but-unexpired ones")
    void purgeDeletesOnlyTokensExpiredOverADayAgo() throws Exception {
        UUID userId = newUser().id();
        UUID oldExpired = insertToken(userId, "now() - interval '2 days'", false);
        UUID recentlyExpired = insertToken(userId, "now() - interval '1 hour'", false);
        UUID live = insertToken(userId, "now() + interval '10 days'", false);
        UUID rotatedButUnexpired = insertToken(userId, "now() + interval '10 days'", true);
        assertThat(exists(oldExpired)).isTrue();

        job.purgeExpired();

        assertThat(exists(oldExpired)).isFalse();
        assertThat(exists(recentlyExpired)).isTrue();
        assertThat(exists(live)).isTrue();
        assertThat(exists(rotatedButUnexpired)).isTrue();
    }

    @Test
    @DisplayName("The purge keeps going past one batch of 1,000")
    void purgeLoopsOverBatches() throws Exception {
        UUID userId = newUser().id();
        int inserted = sql("""
                insert into refresh_tokens (id, user_id, tenant_id, token_hash, expires_at, created_at, updated_at, version)
                select gen_random_uuid(), ?, ?, md5(random()::text) || md5(random()::text), now() - interval '3 days',
                       now(), now(), 0
                  from generate_series(1, 1500)
                """, userId, testTenant().id());
        assertThat(inserted).isEqualTo(1500);

        job.purgeExpired();

        Integer left = jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and expires_at < now() - interval '1 day'",
                Integer.class, userId);
        assertThat(left).isZero();
    }
}
