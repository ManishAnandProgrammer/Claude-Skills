package com.example.app.tenant.domain;

/** Where a tenant's schema stands against the tenant changelog. */
public enum TenantMigrationStatus {

    /** Queued by the tenant's approval; the schema and its first migration are the provisioning job's. */
    PROVISIONING,
    /** The tenant was rejected (or otherwise left PENDING) before its schema was created: it never gets one. */
    CANCELLED,
    /** At {@code last_changeset}, the newest changeset of the last successful run. */
    MIGRATED,
    /** The last deploy-time run failed ({@code error}); the next run retries the missing changesets. */
    FAILED
}
