package com.example.app.tenant.api;

import java.util.UUID;

/**
 * Published inside the transaction that creates the tenant: a listener's failure (an owner without an
 * account) rolls the creation back. The platform's data only: the tenant's own tables may not exist
 * yet, so tenant defaults wait for {@link TenantProvisioned}.
 */
public record TenantCreated(UUID tenantId, String ownerEmail) {
}
