package com.example.app.tenant.api;

import java.util.UUID;

public record ImpersonatedMember(UUID tenantId, UUID userId) {
}
