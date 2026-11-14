package com.example.app.tenant.api;

/** Who changed a tenant's status, recorded on every change ({@code tenant_status_changes.source}). */
public enum TenantChangeSource {

    /** A platform staff member, named in the row's {@code created_by}. */
    STAFF,
    /** Self-registration: the public endpoint created the tenant, PENDING until staff approve it. */
    REGISTRATION,
    /** Provisioning, activating an approved tenant once its tables exist. */
    PROVISIONING
}
