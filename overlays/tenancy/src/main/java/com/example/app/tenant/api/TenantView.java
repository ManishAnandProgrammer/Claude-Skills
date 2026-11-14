package com.example.app.tenant.api;

import java.util.UUID;

/** A tenant as other modules see it: its name and status, nothing of the register's history. */
public record TenantView(UUID id, String name, TenantStatus status) {
}
