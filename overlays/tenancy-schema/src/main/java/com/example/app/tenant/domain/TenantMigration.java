package com.example.app.tenant.domain;

import com.example.app.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * One tenant schema's migration record (schema-per-tenant mode): which changeset it reached and
 * whether its last run failed. Hibernate validates the template schema only; these rows are how every
 * other schema is known to be current.
 */
@Entity
@Table(name = "tenant_migrations")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TenantMigration extends AuditableEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "schema_name", nullable = false, updatable = false)
    private String schemaName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantMigrationStatus status;

    @Column(name = "last_changeset")
    private String lastChangeset;

    @Column(name = "migrated_at")
    private Instant migratedAt;

    @Column(columnDefinition = "text")
    private String error;

    public static TenantMigration provisioning(UUID tenantId, String schemaName) {
        return TenantMigration.builder()
                .tenantId(tenantId)
                .schemaName(schemaName)
                .status(TenantMigrationStatus.PROVISIONING)
                .build();
    }

    public void migrated(String changeset, Instant now) {
        status = TenantMigrationStatus.MIGRATED;
        lastChangeset = changeset;
        migratedAt = now;
        error = null;
    }

    public void cancelled() {
        status = TenantMigrationStatus.CANCELLED;
    }

    /** A provisioning run's failure keeps the row PROVISIONING: only provisioning runs a schema's first migration. */
    public void failed(String failure) {
        if (status != TenantMigrationStatus.PROVISIONING) {
            status = TenantMigrationStatus.FAILED;
        }
        error = failure;
    }

    public boolean isProvisioning() {
        return status == TenantMigrationStatus.PROVISIONING;
    }
}
