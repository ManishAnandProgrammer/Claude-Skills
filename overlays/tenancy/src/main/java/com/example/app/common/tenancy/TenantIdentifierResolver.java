package com.example.app.common.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

import java.util.UUID;

/**
 * Hibernate's view of {@link TenantContext}: every session opens with the current tenant, or
 * {@link TenantContext#NO_TENANT}, never null (Hibernate refuses to open a session without one).
 */
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContext.current();
    }

    /** The tenant is fixed when the session opens; a later switch is ignored, never an error mid-transaction. */
    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
