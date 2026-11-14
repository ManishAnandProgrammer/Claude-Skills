package com.example.app.tenant.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.tenancy.TenantSchemas;
import com.example.app.tenant.api.TenantApproved;
import com.example.app.tenant.api.TenantStatus;
import com.example.app.tenant.domain.TenantMigration;
import com.example.app.tenant.domain.TenantMigrationStatus;
import com.example.app.tenant.repository.TenantMigrationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** The {@code tenant_migrations} records: what provisioning and the deploy-time migrations read and write. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TenantMigrationService {

    private static final Set<TenantMigrationStatus> PROVISIONED = Set.of(TenantMigrationStatus.MIGRATED, TenantMigrationStatus.FAILED);

    private final TenantMigrationRepository repository;
    private final TenantService tenantService;
    private final Clock clock;

    /** In the approving transaction, so the approval and its provisioning record commit together; once per tenant. */
    @EventListener
    @Transactional
    public void queueProvisioning(TenantApproved approved) {
        if (!repository.existsByTenantId(approved.tenantId())) {
            repository.save(TenantMigration.provisioning(approved.tenantId(), TenantSchemas.of(approved.tenantId())));
        }
    }

    public List<UUID> awaitingProvisioning(int limit) {
        return repository.findTenantIdsByStatus(TenantMigrationStatus.PROVISIONING, Limit.of(limit));
    }

    /** Every schema provisioning finished, whether its last deploy-time run failed or not. */
    public List<UUID> provisionedTenantIds() {
        return repository.findTenantIdsByStatusIn(PROVISIONED);
    }

    /**
     * Asked right before the schema is created: false, and the record CANCELLED, when the tenant is no
     * longer PENDING (staff rejected it after approving), so a rejected tenant never gets a schema.
     */
    @Transactional
    public boolean stillToProvision(UUID tenantId) {
        if (tenantService.getTenant(tenantId).getStatus() == TenantStatus.PENDING) {
            return true;
        }
        getMigration(tenantId).cancelled();
        return false;
    }

    /**
     * Run under {@code TenantScope.runAs(tenantId, ...)}, once the schema has its tables: the record and
     * the tenant's activation (its modules' defaults seeded first) commit together.
     */
    @Transactional
    public void completeProvisioning(UUID tenantId, String lastChangeset) {
        getMigration(tenantId).migrated(lastChangeset, clock.instant());
        tenantService.activateProvisioned(tenantId);
    }

    @Transactional
    public void recordMigrated(UUID tenantId, String lastChangeset) {
        getMigration(tenantId).migrated(lastChangeset, clock.instant());
    }

    @Transactional
    public void recordFailure(UUID tenantId, String failure) {
        getMigration(tenantId).failed(failure);
    }

    private TenantMigration getMigration(UUID tenantId) {
        return repository.findByTenantId(tenantId).orElseThrow(AppException.supply(ErrorCode.TENANT_NOT_FOUND, tenantId));
    }
}
