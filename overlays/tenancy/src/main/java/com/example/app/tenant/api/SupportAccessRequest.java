package com.example.app.tenant.api;

import java.util.UUID;

/**
 * A platform staff member asking to act as a tenant's member.
 *
 * @param reason the staff member's reason, a ticket reference for example; null when none was given
 */
public record SupportAccessRequest(UUID staffId, UUID tenantId, UUID userId, String reason) {
}
