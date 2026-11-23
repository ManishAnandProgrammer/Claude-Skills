package com.example.app.media.service;

import com.example.app.common.scheduling.Cron;
import com.example.app.common.scheduling.MultiInstanceSafe;
import com.example.app.media.dto.internal.MediaFileKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import software.amazon.awssdk.core.exception.SdkException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Only after the upload URL expired is the answer final: no bytes can reach the key any more. */
@Slf4j
@Component
class MediaVerificationJob {

    private final MediaFileService mediaFiles;
    private final MediaStorage storage;
    private final Settings settings;
    private final Counter missingFiles;

    // Hand-written: the counter is registered at startup, so its series reads 0 before the first missing file.
    MediaVerificationJob(MediaFileService mediaFiles, MediaStorage storage, Settings settings, MeterRegistry meterRegistry) {
        this.mediaFiles = mediaFiles;
        this.storage = storage;
        this.settings = settings;
        this.missingFiles = meterRegistry.counter("media.files.missing");
    }

    @Scheduled(cron = "${app.jobs.media-verification.cron}")
    @MultiInstanceSafe(reason = """
            Each instance leases its own batch (FOR UPDATE SKIP LOCKED, then lease_until), so instances never \
            check the same file; a crashed instance's batch returns when its lease ends, and every result is \
            written only while the file is still ATTACHED""")
    void verify() {
        int verified = 0;
        int missing = 0;
        List<MediaFileKey> batch;
        do {
            batch = mediaFiles.leaseForVerification(settings.batchSize(), settings.lease());
            List<UUID> present = new ArrayList<>();
            List<UUID> absent = new ArrayList<>();
            boolean storageAnswered = check(batch, present, absent);
            verified += mediaFiles.markVerified(present);
            int markedMissing = mediaFiles.markMissing(absent);
            missingFiles.increment(markedMissing);
            missing += markedMissing;
            if (!storageAnswered) {
                break;
            }
        } while (batch.size() == settings.batchSize());
        log.info("Media verification: verified={}, missing={}", verified, missing);
    }

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

    @Validated
    @ConfigurationProperties(prefix = "app.jobs.media-verification")
    record Settings(@NotNull Cron cron, @Positive int batchSize, @NotNull @DurationMin(millis = 1) Duration lease) {
    }
}
