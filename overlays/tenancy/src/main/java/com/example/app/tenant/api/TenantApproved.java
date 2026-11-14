package com.example.app.tenant.api;

import java.util.UUID;

/**
 * Published inside the transaction that approves a PENDING tenant: staff creating one (approved at once),
 * or approving a registration. The tenancy mode's provisioning listens: it prepares the tenant's tables,
 * then activates it.
 */
public record TenantApproved(UUID tenantId) {
}
