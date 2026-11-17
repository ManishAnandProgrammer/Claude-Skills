package com.example.app.media.service;

import com.example.app.common.scheduling.MultiInstanceSafe;
import com.example.app.media.dto.internal.MediaFileKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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

/**
 * Confirms attached files once their upload URL has expired: present in the store, UPLOADED; absent,
 * MISSING (final, counted on {@code media.files.missing}). Only after the expiry is the answer final:
 * no bytes can reach the key any more. It never detaches a file: a MISSING one keeps its slot until
 * the record's own write replaces it.
 *
 * <p>Each batch: leased in a short transaction, checked with HEAD outside any transaction, its results
 * written in a second short one. A store that doesn't answer ends the run; the leased rows return to
 * the next run when their lease ends. Settings: {@code app.jobs.media-verification.*}.</p>
 */
@Slf4j
@Component
class MediaVerificationJob {

    private final MediaFileService mediaFiles;
    private final MediaStorage storage;
    private final Settings settings;
    private final Counter missingFiles;

    // Hand-written (JAVA-26): the counter is registered at startup, so its series reads 0 before the first missing file.
    MediaVerificationJob(MediaFileService mediaFiles, MediaStorage storage, Settings settings, MeterRegistry meterRegistry) {
        this.mediaFiles = mediaFiles;
        this.storage = storage;
        this.settings = settings;
        this.missingFiles = meterRegistry.counter("media.files.missing");
    }

    @Scheduled(cron = "${app.jobs.media-verification.cron}")
    @MultiInstanceSafe("""
            Each instance leases its own batch (FOR UPDATE SKIP LOCKED, then lease_until), so instances never \
            check the same file; a crashed instance's batch returns when its lease ends, and every result is \
            written only while the file is still IN_REVIEW""")
    void verify() {
        int uploaded = 0;
        int missing = 0;
        List<MediaFileKey> batch;
        do {
            batch = mediaFiles.leaseForVerification(settings.batchSize(), settings.lease());
            List<UUID> present = new ArrayList<>();
            List<UUID> absent = new ArrayList<>();
            boolean storageAnswered = check(batch, present, absent);
            int markedMissing = mediaFiles.recordVerification(present, absent);
            missingFiles.increment(markedMissing);
            uploaded += present.size();
            missing += markedMissing;
            if (!storageAnswered) {
                break;
            }
        } while (batch.size() == settings.batchSize());
        log.info("Media verification: uploaded={}, missing={}", uploaded, missing);
    }

    /** Sorts the batch into present and absent; false when the store stopped answering (the rest stays leased). */
    private boolean check(List<MediaFileKey> batch, List<UUID> present, List<UUID> absent) {
        for (MediaFileKey file : batch) {
            try {
                if (storage.exists(file.key())) {
                    present.add(file.id());
                } else {
                    absent.add(file.id());
                }
            } catch (SdkException failure) {
                log.warn("Media verification stopped, the store did not answer: {}", failure.toString());
                return false;
            }
        }
        return true;
    }

    /**
     * {@code app.jobs.media-verification.*}.
     *
     * @param lease how long a leased batch is this instance's; longer than a batch takes
     */
    @Validated
    @ConfigurationProperties(prefix = "app.jobs.media-verification")
    record Settings(@NotNull String cron, @Positive int batchSize, @NotNull Duration lease) {
    }
}
