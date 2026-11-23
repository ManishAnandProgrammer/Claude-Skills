package com.example.app.tenant.api;

import java.util.UUID;

/**
 * Published inside the creating transaction: a listener's failure rolls the creation back. The tenant's own tables
 * may not exist yet, so tenant defaults wait for {@link TenantProvisioned}.
 */
public record TenantCreated(UUID tenantId, String ownerEmail) {
}
