package com.example.app.tenant.api;

import java.util.UUID;

public record TenantView(UUID id, String name, TenantStatus status) {
}
