package com.example.app.common.tenancy;

import java.util.UUID;

public final class TenantSchemas {

    /** The platform's tables: the application role's default {@code search_path}. */
    public static final String PLATFORM = "platform";
    /** Hibernate validates the entities against it, since it validates one schema only. */
    public static final String TEMPLATE = "tenant_template";

    private static final String TENANT_PREFIX = "t_";

    private TenantSchemas() {
    }

    /** A valid unquoted identifier, 34 characters. */
    public static String of(UUID tenantId) {
        return TENANT_PREFIX + tenantId.toString().replace("-", "");
    }
}
