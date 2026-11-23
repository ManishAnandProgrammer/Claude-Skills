package com.example.app.common.tenancy;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A request's tenant is its verified token's claim, never a header, a path or a body: a client can't pick another. */
public final class TenantContext {

    /** Hibernate refuses to open a session without a tenant; this one matches none, so no tenant data is reachable. */
    public static final UUID NO_TENANT = new UUID(0, 0);

    public static final String TENANT_CLAIM = "tenant_id";
    public static final String TENANT_AUDIENCE = "tenant";
    public static final String PLATFORM_AUDIENCE = "platform";

    private TenantContext() {
    }

    public static UUID current() {
        return TenantScope.scoped().or(TenantContext::fromToken).orElse(NO_TENANT);
    }

    public static UUID id() {
        return findId().orElseThrow(() -> new IllegalStateException("No tenant in this unit of work"));
    }

    public static Optional<UUID> findId() {
        UUID tenantId = current();
        return NO_TENANT.equals(tenantId) ? Optional.empty() : Optional.of(tenantId);
    }

    private static Optional<UUID> fromToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (Objects.nonNull(authentication) && authentication.getPrincipal() instanceof Jwt jwt) {
            return Optional.ofNullable(jwt.getClaimAsString(TENANT_CLAIM)).map(UUID::fromString);
        }
        return Optional.empty();
    }
}
