package com.example.app.tenant.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** The tenant register, for the modules that issue tokens or run work per tenant. */
public interface Tenants {

    /**
     * 403 {@code TENANT_NOT_ACTIVE}, with the tenant's {@code tenantStatus}, unless it is ACTIVE; an
     * unknown tenant is refused the same way. Called whenever tokens are issued.
     */
    void requireActive(UUID tenantId);

    /** The ACTIVE tenants' ids, oldest first: a job runs its work once per tenant, each in its own scope. */
    List<UUID> activeIds();

    /**
     * The one way in: a PENDING tenant may be provisioned ({@link TenantApproved}), and becomes ACTIVE once
     * its tables exist. Staff call it now, a billing module's payment webhook later. 409
     * {@code TENANT_STATUS_CHANGE_INVALID} unless the tenant is PENDING.
     */
    void approve(UUID tenantId, TenantChangeSource source);

    /**
     * Self-registration's tenant: PENDING, with {@code ownerEmail}'s account as its ADMIN
     * ({@link TenantCreated}), not approved: staff approve or reject it.
     */
    UUID register(String name, String ownerEmail);

    /**
     * Locks the tenant in the caller's transaction, which must exist: a change to its memberships takes
     * it before reading the caller's role and counting the ADMINs, so a demotion that commits first is
     * seen, and two changes can't each leave the other as the last one. 404 {@code TENANT_NOT_FOUND}.
     */
    void lockForMemberChange(UUID tenantId);

    /** Name and status of each tenant in {@code ids} that exists: a member's list of their tenants. */
    List<TenantView> findAllById(Collection<UUID> ids);
}
