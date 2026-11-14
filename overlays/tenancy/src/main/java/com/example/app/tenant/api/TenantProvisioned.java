package com.example.app.tenant.api;

import java.util.UUID;

/**
 * Published once the tenant's tables exist, under {@code TenantScope.runAs(tenantId, ...)} and inside
 * the transaction that activates it: a module's listener seeds the tenant's defaults there, and a
 * failure leaves the tenant PENDING for the next provisioning run.
 */
public record TenantProvisioned(UUID tenantId) {
}
