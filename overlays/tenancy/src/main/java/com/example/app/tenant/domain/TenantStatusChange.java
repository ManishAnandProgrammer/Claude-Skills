package com.example.app.tenant.domain;

import com.example.app.common.persistence.AuditableEntity;
import com.example.app.tenant.api.TenantChangeSource;
import com.example.app.tenant.api.TenantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * One change of a tenant's status: the audit trail of the lifecycle, a member of the tenant's
 * aggregate. {@code created_by} names the staff member; it is null for a job.
 */
@Entity
@Table(name = "tenant_status_changes")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TenantStatusChange extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private Tenant tenant;

    /** Null for the creation. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private TenantStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false)
    private TenantStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TenantChangeSource source;

    public static TenantStatusChange of(Tenant tenant, TenantStatus fromStatus, TenantChangeSource source) {
        return TenantStatusChange.builder()
                .tenant(tenant)
                .fromStatus(fromStatus)
                .toStatus(tenant.getStatus())
                .source(source)
                .build();
    }
}
