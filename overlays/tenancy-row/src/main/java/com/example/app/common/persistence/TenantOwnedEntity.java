package com.example.app.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.TenantId;

import java.util.UUID;

/**
 * Hibernate fills {@code tenant_id} from the session's tenant on insert, refuses an entity that carries another
 * tenant's id, and adds {@code tenant_id = ?} to every query of the entity. The table's row-level security policy is
 * the barrier behind it, for what Hibernate doesn't filter (native SQL).
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
