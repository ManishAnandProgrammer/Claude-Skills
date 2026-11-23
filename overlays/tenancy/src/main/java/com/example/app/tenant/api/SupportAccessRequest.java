package com.example.app.tenant.api;

import java.util.UUID;

public record SupportAccessRequest(UUID staffId, UUID tenantId, UUID userId, String reason) {
}
