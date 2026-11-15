package com.example.app.tenant.api;

import java.util.UUID;

/**
 * An ACTIVE tenant changed to another status (staff suspended it), inside that change's transaction: its
 * members' tokens there must stop at once (the session-version piece), not one access-token lifetime later.
 */
public record TenantDeactivated(UUID tenantId) {
}
