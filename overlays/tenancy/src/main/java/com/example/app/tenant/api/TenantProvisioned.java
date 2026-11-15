package com.example.app.tenant.api;

import java.util.UUID;

/**
 * Published once the tenant's tables exist, under {@code TenantScope.runAs(tenantId, ...)} and inside
 * the transaction that activates it: a module's listener seeds the tenant's defaults there, and a
 * failure leaves the tenant PENDING (schema per tenant: the provisioning job's next run retries it; row
 * mode: approving it again does).
 */
public record TenantProvisioned(UUID tenantId) {
}
