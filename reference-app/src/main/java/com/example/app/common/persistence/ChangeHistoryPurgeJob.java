package com.example.app.common.persistence;

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
 * Deletes change history past its retention, the only rows that ever leave {@code audit_log}: the table only grows
 * otherwise (house-review OPS-03). The cutoff is taken once per run; the rows go in batches, each its own short
 * transaction, until a batch comes back short, and the run logs how many went. Settings:
 * {@code app.jobs.change-history-purge.*}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ChangeHistoryPurgeJob {

    private final ChangeHistoryRetention retention;
    private final Settings settings;
    private final Clock clock;

    @Scheduled(cron = "${app.jobs.change-history-purge.cron}", zone = "UTC")
    @MultiInstanceSafe("Each batch locks its rows FOR UPDATE SKIP LOCKED: instances running at once delete disjoint rows")
    void purgeOld() {
        Instant changedBefore = clock.instant().minus(settings.keepFor());
        int total = 0;
        int deleted;
        do {
            deleted = retention.deleteChangedBefore(changedBefore, settings.batchSize());
            total += deleted;
        } while (deleted == settings.batchSize());
        log.info("Change history purge: changedBefore={}, deleted={}", changedBefore, total);
    }

    /**
     * {@code app.jobs.change-history-purge.*}.
     *
     * @param cron      when it runs, in UTC; {@code "-"} switches it off (the test profile, where tests call it)
     * @param keepFor   how long a change is kept: at least 90 days, so a slip such as {@code 7d} (or a bare number,
     *                  read as milliseconds) stops the startup instead of deleting the history the next night
     * @param batchSize rows deleted per statement, each statement its own transaction
     */
    @Validated
    @ConfigurationProperties(prefix = "app.jobs.change-history-purge")
    record Settings(@NotNull Cron cron, @NotNull @DurationMin(days = 90) Duration keepFor, @Positive int batchSize) {
    }
}
