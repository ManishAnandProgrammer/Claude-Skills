package com.example.app.user.service;

import com.example.app.common.scheduling.Cron;
import com.example.app.common.scheduling.MultiInstanceSafe;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Deletes refresh tokens a while after they expired, so the table doesn't grow forever (house-review OPS-03). By
 * expiry, never by revocation: a rotated token stays until it expires, so presenting it again is still seen as
 * reuse and still ends the user's sessions ({@link TokenService#refresh}).
 *
 * <p>The cutoff is taken once per run; the rows go in batches, each its own short transaction, until a batch comes
 * back short, and the run logs how many went. Spring's {@code tasks.scheduled.execution} timer counts each run and its
 * outcome, and the run's lines carry its own log context ({@code ScheduledRunLogContext}). Settings:
 * {@code app.jobs.refresh-token-purge.*}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RefreshTokenPurgeJob {

    private final TokenService tokenService;
    private final Settings settings;
    private final Clock clock;

    @Scheduled(cron = "${app.jobs.refresh-token-purge.cron}", zone = "UTC")
    @MultiInstanceSafe("Each batch locks its rows FOR UPDATE SKIP LOCKED: instances running at once delete disjoint rows")
    void purgeExpired() {
        Instant expiredBefore = clock.instant().minus(settings.keepAfterExpiry());
        int total = 0;
        int deleted;
        do {
            deleted = tokenService.deleteExpiredBefore(expiredBefore, settings.batchSize());
            total += deleted;
        } while (deleted == settings.batchSize());
        log.info("Refresh token purge: expiredBefore={}, deleted={}", expiredBefore, total);
    }

    /**
     * {@code app.jobs.refresh-token-purge.*}.
     *
     * @param cron            when it runs, in UTC; {@code "-"} switches it off (the test profile, where tests call it)
     * @param keepAfterExpiry how long a token stays after it expired; never negative, which would delete live tokens
     * @param batchSize       tokens deleted per statement, each statement its own transaction
     */
    @Validated
    @ConfigurationProperties(prefix = "app.jobs.refresh-token-purge")
    record Settings(@NotNull Cron cron, @NotNull @DurationMin(days = 0) Duration keepAfterExpiry, @Positive int batchSize) {
    }
}
