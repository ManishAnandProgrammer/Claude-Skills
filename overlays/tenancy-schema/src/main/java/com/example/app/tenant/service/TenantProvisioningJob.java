package com.example.app.tenant.service;

import com.example.app.common.scheduling.MultiInstanceSafe;
import com.example.app.common.tenancy.TenantScope;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
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
 * tenant is retried from the start by the next run.
 * Settings: {@code app.jobs.tenant-provisioning.*}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantProvisioningJob {

    private final TenantSchemaMigrator migrator;
    private final TenantMigrationService migrations;
    private final Settings settings;

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
