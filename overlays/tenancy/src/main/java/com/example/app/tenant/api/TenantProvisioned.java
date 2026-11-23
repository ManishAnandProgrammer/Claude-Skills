package com.example.app.tenant.api;

import java.util.UUID;

/** Published in the tenant's scope, in the activating transaction: a listener's failure keeps the tenant PENDING. */
public record TenantProvisioned(UUID tenantId) {
}
