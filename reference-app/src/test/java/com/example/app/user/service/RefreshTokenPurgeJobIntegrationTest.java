package com.example.app.user.service;

import com.example.app.common.scheduling.Cron;
import com.example.app.support.IntegrationTest;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenPurgeJobIntegrationTest extends IntegrationTest {

    @Autowired
    private RefreshTokenPurgeJob job;

    @Autowired
    private Validator validator;

    /** Inserts a token directly; {@code expiresAt} is an SQL expression relative to now. */
    private UUID insertToken(UUID userId, String expiresAt, boolean revoked) {
        UUID id = UUID.randomUUID();
        sql("""
                insert into refresh_tokens (id, user_id, token_hash, expires_at, revoked_at, revocation_reason,
                                            created_at, updated_at, version)
                values (?, ?, ?, %s, %s, %s, now(), now(), 0)
                """.formatted(expiresAt, revoked ? "now()" : "null", revoked ? "'ROTATED'" : "null"),
                id, userId, UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
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
                insert into refresh_tokens (id, user_id, token_hash, expires_at, created_at, updated_at, version)
                select gen_random_uuid(), ?, md5(random()::text) || md5(random()::text), now() - interval '3 days',
                       now(), now(), 0
                  from generate_series(1, 1500)
                """, userId);
        assertThat(inserted).isEqualTo(1500);

        job.purgeExpired();

        Integer left = jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and expires_at < now() - interval '1 day'",
                Integer.class, userId);
        assertThat(left).isZero();
    }

    @Test
    @DisplayName("A negative keep-after-expiry stops startup: it would delete live tokens and, with the rotated ones, the reuse detection")
    void aNegativeKeepAfterExpiryIsRefused() {
        RefreshTokenPurgeJob.Settings negative = new RefreshTokenPurgeJob.Settings(Cron.of("-"), Duration.ofHours(-1), 1000);
        RefreshTokenPurgeJob.Settings none = new RefreshTokenPurgeJob.Settings(Cron.of("-"), Duration.ZERO, 1000);

        assertThat(validator.validate(negative)).extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("keepAfterExpiry");
        assertThat(validator.validate(none)).isEmpty();
    }
}
