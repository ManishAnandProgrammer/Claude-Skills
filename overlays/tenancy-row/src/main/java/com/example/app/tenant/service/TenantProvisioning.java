package com.example.app.tenant.service;

import com.example.app.common.tenancy.TenantScope;
import com.example.app.tenant.api.TenantApproved;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Runs after the approving transaction commits, in a new transaction opened inside {@code TenantScope.runAs}: the
 * approving one was opened without a tenant, and the tenant is fixed when a transaction begins, so seeding inside it
 * would write as no tenant (refused by row-level security). Before the commit the tenant row isn't visible to a new
 * transaction either.
 */
@Slf4j
@Component
public class TenantProvisioning {

    static final String FAILED_METRIC = "tenant.provisioning.failed";

    private final TenantService tenantService;
    private final TransactionTemplate newTransaction;
    private final Counter failures;

    // Hand-written: the counter is registered at startup, so its series reads 0 before the first failure,
    // and the template is this class's own (a REQUIRES_NEW bean would be injected by mistake elsewhere).
    TenantProvisioning(TenantService tenantService, PlatformTransactionManager transactionManager, MeterRegistry meterRegistry) {
        this.tenantService = tenantService;
        this.failures = Counter.builder(FAILED_METRIC)
                .description("Approved tenants whose provisioning failed: they stay PENDING until approved again")
                .register(meterRegistry);
        this.newTransaction = new TransactionTemplate(transactionManager);
        // The approving transaction's resources are still bound after its commit: joining them would write nothing.
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener
    public void provision(TenantApproved approved) {
        UUID tenantId = approved.tenantId();
        try {
            TenantScope.runAs(tenantId, () -> newTransaction.executeWithoutResult(status -> tenantService.activateProvisioned(tenantId)));
            log.info("Tenant provisioned: tenantId={}", tenantId);
        } catch (RuntimeException failure) {
            failures.increment();
            log.warn("Tenant provisioning failed, the tenant stays PENDING until it is approved again: tenantId={}", tenantId, failure);
        }
    }
}
