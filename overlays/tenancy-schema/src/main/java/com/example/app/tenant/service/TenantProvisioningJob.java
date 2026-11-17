package com.example.app.tenant.service;

import com.example.app.common.scheduling.MultiInstanceSafe;
import com.example.app.common.tenancy.TenantScope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Provisions the approved tenants: creates the schema, runs its first migration (the only place that
 * does: two migrators racing on a new schema fail), then seeds and activates the tenant under its own
 * scope. A tenant rejected after its approval gets no schema. Each step is idempotent, so a failed
 * tenant is retried from the start by the next run; every failure is logged at WARN, recorded on its
 * {@code tenant_migrations} row and counted on {@value #FAILED_METRIC} (an alert's signal: a tenant
 * that fails on every run stays PENDING).
 * Settings: {@code app.jobs.tenant-provisioning.*}.
 */
@Slf4j
@Component
public class TenantProvisioningJob {

    static final String FAILED_METRIC = "tenant.provisioning.failed";

    private final TenantSchemaMigrator migrator;
    private final TenantMigrationService migrations;
    private final Settings settings;
    private final Counter failures;

    // Hand-written (JAVA-26): the counter is registered at startup, so its series reads 0 before the first failure.
    TenantProvisioningJob(TenantSchemaMigrator migrator, TenantMigrationService migrations, Settings settings,
                          MeterRegistry meterRegistry) {
        this.migrator = migrator;
        this.migrations = migrations;
        this.settings = settings;
        this.failures = Counter.builder(FAILED_METRIC)
                .description("Provisioning attempts that failed; the next run retries the tenant")
                .register(meterRegistry);
    }

    @Scheduled(cron = "${app.jobs.tenant-provisioning.cron}")
    @MultiInstanceSafe("""
            Runs under the tenant-schema advisory lock (TenantSchemaMigrator.runLocked): while one instance \
            provisions, another's run is skipped, so two migrators never meet on a new schema""")
    public void provisionPending() {
        boolean ran = migrator.runLocked(this::provisionBatch);
        if (!ran) {
            log.info("Tenant provisioning skipped: another instance holds the migrations lock");
        }
    }

    private void provisionBatch() {
        for (UUID tenantId : migrations.awaitingProvisioning(settings.batchSize())) {
            provision(tenantId);
        }
    }

    private void provision(UUID tenantId) {
        try {
            if (!migrations.stillToProvision(tenantId)) {
                log.info("Tenant provisioning cancelled, the tenant is no longer pending: tenantId={}", tenantId);
                return;
            }
            migrator.createSchema(tenantId);
            String lastChangeset = migrator.migrate(tenantId);
            TenantScope.runAs(tenantId, () -> migrations.completeProvisioning(tenantId, lastChangeset));
            log.info("Tenant provisioned: tenantId={}, changeset={}", tenantId, lastChangeset);
        } catch (RuntimeException failure) {
            failures.increment();
            log.warn("Tenant provisioning failed, retried by the next run: tenantId={}", tenantId, failure);
            migrations.recordFailure(tenantId, TenantSchemaMigrator.describe(failure));
        }
    }

    /**
     * {@code app.jobs.tenant-provisioning.*}.
     *
     * @param batchSize tenants provisioned per run
     */
    @Validated
    @ConfigurationProperties(prefix = "app.jobs.tenant-provisioning")
    record Settings(@NotNull String cron, @Positive int batchSize) {
    }
}
