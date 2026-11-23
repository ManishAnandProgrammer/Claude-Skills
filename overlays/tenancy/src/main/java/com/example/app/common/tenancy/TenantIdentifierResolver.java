package com.example.app.common.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

import java.util.UUID;

public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContext.current();
    }

    /** False: the tenant is fixed when the session opens; a later switch is ignored, never an error mid-transaction. */
    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
