package com.example.app.user.service;

import com.example.app.common.scheduling.MultiInstanceSafe;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Deletes expired refresh tokens so the table doesn't grow forever: in batches, each its own short
 * transaction, until a batch comes back short. Settings: {@code app.jobs.refresh-token-purge.*}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RefreshTokenPurgeJob {

    private final TokenService tokenService;
    private final Settings settings;

    @Scheduled(cron = "${app.jobs.refresh-token-purge.cron}")
    @MultiInstanceSafe("Idempotent batched deletes: instances running together delete disjoint or already-deleted rows")
    void purgeExpired() {
        int total = 0;
        int deleted;
        do {
            deleted = tokenService.deleteExpiredBatch(settings.keepAfterExpiry(), settings.batchSize());
            total += deleted;
        } while (deleted == settings.batchSize());
        log.info("Refresh token purge: deleted={}", total);
    }

    @Validated
    @ConfigurationProperties(prefix = "app.jobs.refresh-token-purge")
    record Settings(@NotNull String cron, @NotNull Duration keepAfterExpiry, @Positive int batchSize) {
    }
}
