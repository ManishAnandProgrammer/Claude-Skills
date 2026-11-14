package com.example.app.common.tenancy;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The tenant of the current unit of work (the bootstrap piece {@code tenancy}).
 *
 * <ul>
 *   <li>In a job, a listener or an async task: what {@link TenantScope#runAs} set.</li>
 *   <li>On a request: the verified access token's {@code tenant_id} claim. Never a header, a path
 *       segment or a body field, so a client can't ask for another tenant. With an external identity
 *       provider, {@link #fromToken} is the one place that maps its claim.</li>
 *   <li>Neither (sign-in, platform endpoints, a job between tenants): {@link #NO_TENANT}. A session
 *       opened with it reaches the platform's tables only.</li>
 * </ul>
 */
public final class TenantContext {

    /**
     * "No tenant": Hibernate needs a tenant to open a session at all, and an unknown tenant must never
     * fall back to a real one. It matches no tenant, so tenant data stays out of reach.
     */
    public static final UUID NO_TENANT = new UUID(0, 0);

    /** The access token's claim, written by the token issuer and read here. */
    public static final String TENANT_CLAIM = "tenant_id";
    /** Audience of a tenant token: accepted everywhere but {@code /platform/**}. */
    public static final String TENANT_AUDIENCE = "tenant";
    /** Audience of a platform staff token: accepted under {@code /platform/**} only. */
    public static final String PLATFORM_AUDIENCE = "platform";

    private TenantContext() {
    }

    /** The current tenant, or {@link #NO_TENANT}. Hibernate opens every session with it. */
    public static UUID current() {
        return TenantScope.scoped().or(TenantContext::fromToken).orElse(NO_TENANT);
    }

    /** The current tenant; throws when there is none, which in tenant-plane code is a bug. */
    public static UUID id() {
        return findId().orElseThrow(() -> new IllegalStateException("No tenant in this unit of work"));
    }

    /** The current tenant, empty under {@link #NO_TENANT}. */
    public static Optional<UUID> findId() {
        UUID tenantId = current();
        return NO_TENANT.equals(tenantId) ? Optional.empty() : Optional.of(tenantId);
    }

    private static Optional<UUID> fromToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (Objects.nonNull(authentication) && authentication.getPrincipal() instanceof Jwt jwt
                && jwt.hasClaim(TENANT_CLAIM)) {
            return Optional.of(UUID.fromString(jwt.getClaimAsString(TENANT_CLAIM)));
        }
        return Optional.empty();
    }
}
