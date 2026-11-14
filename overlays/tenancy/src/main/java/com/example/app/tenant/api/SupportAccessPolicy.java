package com.example.app.tenant.api;

/**
 * The gate in front of impersonation, asked before a token is issued. The default
 * ({@code AllowAllSupportAccess}) allows every request: staff may act as any tenant (owner decision
 * O6). A strict application replaces it with its own bean, without changing the endpoint: a consent
 * the tenant's admin gave for a time box, or a required ticket reference.
 */
public interface SupportAccessPolicy {

    /** False refuses the impersonation: 403 {@code SUPPORT_ACCESS_DENIED}, and no token. */
    boolean allows(SupportAccessRequest request);
}
