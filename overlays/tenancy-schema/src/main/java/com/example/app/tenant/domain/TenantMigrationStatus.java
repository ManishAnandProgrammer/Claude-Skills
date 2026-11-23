package com.example.app.tenant.domain;

public enum TenantMigrationStatus {

    PROVISIONING,
    /** The tenant was rejected (or otherwise left PENDING) before its schema was created: it never gets one. */
    CANCELLED,
    MIGRATED,
    FAILED
}
