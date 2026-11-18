package com.example.app.tenant.service;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Brings every provisioned tenant schema to the tenant changelog, at deploy: once an instance is
 * ready, in the background, on whichever instance takes the migrations lock first (the others skip).
 * Not in every instance's startup: Liquibase's own lock would make N instances wait on each of M
 * schemas in turn.
 *
 * <ul>
 *   <li>A failing schema is recorded ({@code tenant_migrations.status = FAILED}, the error) and skipped;
 *       the others go on. The next run applies only what is missing, so fixing the schema and running
 *       again resumes it.</li>
 *   <li>Changesets are expand-then-contract: schemas are migrated one after another, so old code runs
 *       against new schemas and new code against old ones during the rollout.</li>
 *   <li>Schemas still PROVISIONING are the provisioning job's: a schema's first migration runs only there.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class TenantMigrations {

    private final TenantSchemaMigrator migrator;
    private final TenantMigrationService migrations;
    private final TenantSchemaProperties properties;
    private final TaskScheduler taskScheduler;

    /** What one run did; {@code ran} is false when another instance held the lock. */
    public record Run(boolean ran, int migrated, List<UUID> failed) {

        public Run {
            failed = List.copyOf(failed);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void atDeploy() {
        if (properties.migrations().runAtStartup()) {
            taskScheduler.schedule(this::run, Instant.now());
        }
    }

    public Run run() {
        List<UUID> migrated = new ArrayList<>();
        List<UUID> failed = new ArrayList<>();
        boolean ran = migrator.runLocked(() -> migrateAll(migrated, failed));
        Run run = new Run(ran, migrated.size(), failed);
        log.info("Tenant migrations: ran={}, migrated={}, failed={}", run.ran(), run.migrated(), run.failed());
        return run;
    }

    /**
     * Every provisioned schema, one after another: Liquibase keeps its run state in one JVM-wide scope,
     * so two runs in one JVM at once interfere. Failures are collected, never thrown.
     */
    private void migrateAll(List<UUID> migrated, List<UUID> failed) {
        for (UUID tenantId : migrations.provisionedTenantIds()) {
            if (migrateOne(tenantId)) {
                migrated.add(tenantId);
            } else {
                failed.add(tenantId);
            }
        }
    }

    private boolean migrateOne(UUID tenantId) {
        try {
            migrations.recordMigrated(tenantId, migrator.migrate(tenantId));
            return true;
        } catch (RuntimeException failure) {
            log.warn("Tenant schema migration failed, skipped: tenantId={}", tenantId, failure);
            migrations.recordFailure(tenantId, TenantSchemaMigrator.describe(failure));
            return false;
        }
    }
}
