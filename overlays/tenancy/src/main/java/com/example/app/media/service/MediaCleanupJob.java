package com.example.app.media.service;

import com.example.app.common.scheduling.MultiInstanceSafe;
import com.example.app.common.tenancy.TenantScope;
import com.example.app.media.dto.internal.MediaFileKey;
import com.example.app.tenant.api.Tenants;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import software.amazon.awssdk.core.exception.SdkException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Deletes from the store, then from the table: files removed from their record (RELEASED, once their
 * upload URL expired, so no PUT can recreate the object) and files never attached in time (PENDING
 * past their claim window). A MISSING file still attached is never touched. Removing a file in the
 * request only marks it: deleting the object there would either precede the commit (a rollback leaves
 * a row pointing at nothing) or follow it (a failure leaves an orphan).
 *
 * <p>Each batch: leased in a short transaction, deleted from the store outside any transaction, its
 * rows deleted in a second short one. A store that doesn't answer ends the tenant's run; the leased rows
 * return to the next run when their lease ends. A tenant that fails is logged and the run goes on with
 * the next. Settings: {@code app.jobs.media-cleanup.*}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class MediaCleanupJob {

    private final MediaFileService mediaFiles;
    private final MediaStorage storage;
    private final Settings settings;
    private final Tenants tenants;

    @Scheduled(cron = "${app.jobs.media-cleanup.cron}")
    @MultiInstanceSafe("""
            Each instance leases its own batch (FOR UPDATE SKIP LOCKED, then lease_until), so instances never \
            delete the same file; a crashed instance's batch returns when its lease ends, and S3 deletes are \
            idempotent, so deleting an object twice is harmless""")
    void cleanUp() {
        // Suspended and closed tenants too: their removed and expired uploads cost storage all the same.
        for (UUID tenantId : tenants.idsHoldingData()) {
            try {
                TenantScope.runAs(tenantId, () -> cleanUpAll(tenantId));
            } catch (RuntimeException failure) {
                // One tenant's failure must not skip every tenant after it, run after run: its leased rows
                // return when the lease ends, and the next run retries.
                log.error("Media cleanup failed for tenant {}", tenantId, failure);
            }
        }
    }

    private void cleanUpAll(UUID tenantId) {
        int released = clean(mediaFiles::leaseReleased);
        int expired = clean(mediaFiles::leaseExpiredPending);
        log.info("Media cleanup: tenant={}, released={}, expiredPending={}", tenantId, released, expired);
    }

    /** Batch after batch of one kind until a batch comes back short or the store stops answering. */
    private int clean(BiFunction<Integer, Duration, List<MediaFileKey>> lease) {
        int deleted = 0;
        List<MediaFileKey> batch;
        do {
            batch = lease.apply(settings.batchSize(), settings.lease());
            List<UUID> gone = new ArrayList<>();
            boolean storageAnswered = deleteObjects(batch, gone);
            deleted += mediaFiles.deleteRemoved(gone);
            if (!storageAnswered) {
                break;
            }
        } while (batch.size() == settings.batchSize());
        return deleted;
    }

    private boolean deleteObjects(List<MediaFileKey> batch, List<UUID> gone) {
        for (MediaFileKey file : batch) {
            try {
                storage.delete(file.key());
                gone.add(file.id());
            } catch (SdkException failure) {
                log.warn("Media cleanup stopped, the store did not answer: {}", failure.toString());
                return false;
            }
        }
        return true;
    }

    /**
     * {@code app.jobs.media-cleanup.*}.
     *
     * @param lease how long a leased batch is this instance's; longer than a batch takes
     */
    @Validated
    @ConfigurationProperties(prefix = "app.jobs.media-cleanup")
    record Settings(@NotNull String cron, @Positive int batchSize, @NotNull Duration lease) {
    }
}
