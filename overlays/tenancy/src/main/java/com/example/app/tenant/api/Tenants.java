package com.example.app.tenant.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface Tenants {

    void requireActive(UUID tenantId);

    List<UUID> activeIds();

    List<UUID> idsHoldingData();

    void approve(UUID tenantId);

    UUID register(String name, String ownerEmail);

    /**
     *   <!-- roles:begin -->
     * Locks the tenant in the caller's transaction, which must exist: every change of its memberships or of anyone's
     * access there takes it before reading the caller's access and counting who manages the tenant, so a change that
     * commits first is seen, and two changes can't each leave the other as the last one.
     *   <!-- roles:else -->
     <!-- * Locks the tenant in the caller's transaction, which must exist: a change to its memberships takes -->
     <!-- * it before reading the caller's role and counting the ADMINs, so a demotion that commits first is -->
     <!-- * seen, and two changes can't each leave the other as the last one. -->
     *   <!-- roles:end -->
     */
    void lockForMemberChange(UUID tenantId);

    List<TenantView> findAllById(Collection<UUID> ids);
}
