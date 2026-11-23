package com.example.app.tenant.service;

import com.example.app.tenant.api.TenantApproved;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class TenantProvisioningTest {

    private final TenantService tenantService = mock(TenantService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final TenantProvisioning provisioning = new TenantProvisioning(tenantService, mock(PlatformTransactionManager.class), meters);

    @Test
    @DisplayName("A failed activation is counted on tenant.provisioning.failed and doesn't reach the approving caller")
    void aFailureIsCounted() {
        doThrow(new QueryTimeoutException("statement timeout")).when(tenantService).activateProvisioned(any());

        assertThatCode(() -> provisioning.provision(new TenantApproved(UUID.randomUUID()))).doesNotThrowAnyException();

        assertThat(meters.counter(TenantProvisioning.FAILED_METRIC).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("A provisioning that succeeds counts nothing")
    void aSuccessCountsNothing() {
        provisioning.provision(new TenantApproved(UUID.randomUUID()));

        assertThat(meters.counter(TenantProvisioning.FAILED_METRIC).count()).isZero();
    }
}
