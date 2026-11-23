package com.example.app.tenant.api;

import java.util.UUID;

/** Published inside the status change's transaction: the members' tokens there must stop at once. */
public record TenantDeactivated(UUID tenantId) {
}
