package com.example.app.common.tenancy;

import java.util.UUID;

/**
 * Schema names of the schema-per-tenant mode. A tenant's schema is derived from its id, never taken
 * from input, so no client value reaches a schema name; the hook still quotes it.
 */
public final class TenantSchemas {

    /** The platform's tables: the application role's default {@code search_path}. */
    public static final String PLATFORM = "platform";
    /**
     * Kept at the tenant changelog's latest changeset by the migrations; Hibernate validates the
     * entities against it, since it validates one schema only.
     */
    public static final String TEMPLATE = "tenant_template";

    private static final String TENANT_PREFIX = "t_";

    private TenantSchemas() {
    }

    /** {@code t_} and the id's 32 hex digits: a valid unquoted identifier, 34 characters. */
    public static String of(UUID tenantId) {
        return TENANT_PREFIX + tenantId.toString().replace("-", "");
    }
}
