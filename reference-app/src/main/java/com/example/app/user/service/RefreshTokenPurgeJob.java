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
 * By expiry, never by revocation: a rotated token stays until it expires, so presenting it again is still seen as
 * reuse and still ends the user's sessions ({@link TokenService#refresh}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RefreshTokenPurgeJob {

    private final TokenService tokenService;
    private final Settings settings;
    private final Clock clock;

    @Scheduled(cron = "${app.jobs.refresh-token-purge.cron}", zone = "UTC")
    @MultiInstanceSafe(reason = "Each batch locks its rows FOR UPDATE SKIP LOCKED: instances running at once delete disjoint rows")
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

    @Validated
    @ConfigurationProperties(prefix = "app.jobs.refresh-token-purge")
    record Settings(@NotNull Cron cron, @NotNull @DurationMin(days = 0) Duration keepAfterExpiry, @Positive int batchSize) {
    }
}
