package com.example.app.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.TenantId;

import java.util.UUID;

/**
 * Base of every entity whose table belongs to one tenant (row mode): its {@code tenant_id}.
 * Hibernate fills it from the session's tenant on insert, refuses an entity that carries another
 * tenant's id, and adds {@code tenant_id = ?} to every query of the entity, so repositories and
 * services never name the tenant. The table's row-level security policy is the barrier behind it, for
 * what Hibernate doesn't filter (native SQL).
 *
 * <p>Platform entities (the tenant register, users, memberships, staff) extend {@link AuditableEntity}
 * directly.</p>
 */
@MappedSuperclass
@Getter
@SuperBuilder
@NoArgsConstructor
public abstract class TenantOwnedEntity extends AuditableEntity {

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;
}
