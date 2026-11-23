package com.example.app.media.service;

import com.example.app.common.scheduling.Cron;
import com.example.app.tenant.api.Tenants;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaJobsPerTenantTest {

    private static final Duration LEASE = Duration.ofMinutes(5);

    private final MediaFileService mediaFiles = mock(MediaFileService.class);
    private final MediaStorage storage = mock(MediaStorage.class);
    private final Tenants tenants = mock(Tenants.class);
    private final UUID failing = UUID.randomUUID();
    private final UUID next = UUID.randomUUID();

    @Test
    @DisplayName("Verification goes on with the next tenant after one fails")
    void verificationSurvivesAFailingTenant() {
        when(tenants.activeIds()).thenReturn(List.of(failing, next));
        when(mediaFiles.leaseForVerification(anyInt(), any()))
                .thenThrow(new QueryTimeoutException("statement timeout"))
                .thenReturn(List.of());
        MediaVerificationJob job = new MediaVerificationJob(mediaFiles, storage,
                new MediaVerificationJob.Settings(new Cron("-"), 10, LEASE), new SimpleMeterRegistry(), tenants);

        assertThatCode(job::verify).doesNotThrowAnyException();
        verify(mediaFiles, times(2)).leaseForVerification(10, LEASE);
    }

    @Test
    @DisplayName("Cleanup goes on with the next tenant after one fails, and visits every tenant that holds data")
    void cleanupSurvivesAFailingTenant() {
        when(tenants.idsHoldingData()).thenReturn(List.of(failing, next));
        when(mediaFiles.leaseReleased(anyInt(), any()))
                .thenThrow(new QueryTimeoutException("statement timeout"))
                .thenReturn(List.of());
        when(mediaFiles.leaseExpiredUnclaimed(anyInt(), any())).thenReturn(List.of());
        MediaCleanupJob job = new MediaCleanupJob(mediaFiles, storage, new MediaCleanupJob.Settings(new Cron("-"), 10, LEASE), tenants);

        assertThatCode(job::cleanUp).doesNotThrowAnyException();
        verify(mediaFiles, times(2)).leaseReleased(10, LEASE);
        verify(mediaFiles, times(1)).leaseExpiredUnclaimed(10, LEASE);
    }
}
