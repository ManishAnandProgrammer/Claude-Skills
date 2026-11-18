package com.example.app.tenant.service;

import com.example.app.common.scheduling.Cron;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Every failed provisioning attempt is recorded on the tenant's row and counted, so an alert can see it. */
class TenantProvisioningJobTest {

    private final TenantSchemaMigrator migrator = mock(TenantSchemaMigrator.class);
    private final TenantMigrationService migrations = mock(TenantMigrationService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final TenantProvisioningJob job = new TenantProvisioningJob(migrator, migrations,
            new TenantProvisioningJob.Settings(Cron.of("* * * * * *"), 10), meters);
    private final UUID failing = UUID.randomUUID();
    private final UUID next = UUID.randomUUID();

    @BeforeEach
    void runTheWorkUnderTheLock() {
        when(migrator.runLocked(any())).thenAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return true;
        });
        when(migrations.awaitingProvisioning(10)).thenReturn(List.of(failing, next));
        when(migrations.stillToProvision(any())).thenReturn(true);
    }

    @Test
    @DisplayName("A tenant whose schema fails is counted on tenant.provisioning.failed and recorded; the next one is provisioned")
    void aFailureIsCountedAndTheNextTenantGoesOn() {
        doThrow(new QueryTimeoutException("statement timeout")).when(migrator).createSchema(failing);

        job.provisionPending();

        assertThat(meters.counter(TenantProvisioningJob.FAILED_METRIC).count()).isEqualTo(1);
        verify(migrations).recordFailure(eq(failing), anyString());
        verify(migrator).migrate(next);
    }
}
