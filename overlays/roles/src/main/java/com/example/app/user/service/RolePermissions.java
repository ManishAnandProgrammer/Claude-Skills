package com.example.app.user.service;

import com.example.app.common.security.Permission;
import com.example.app.common.security.TokenPermissions;
// tenancy:begin
import com.example.app.common.tenancy.TenantContext;
import com.example.app.common.tenancy.TenantScope;
// tenancy:end
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Read from the tables on every request, without a cache: the token carries nothing about access, so a change of
 * roles or grants, or a suspension, reaches the person's next request.
 */
@Component
@RequiredArgsConstructor
class RolePermissions implements TokenPermissions {

    private final PersonAccessService access;

    @Override
    public Set<Permission> of(Jwt token) {
        String subject = token.getSubject();
        if (Objects.isNull(subject)) {
            return EnumSet.noneOf(Permission.class);
        }
        UUID userId = UUID.fromString(subject);
        // tenancy:begin
        String tenant = token.getClaimAsString(TenantContext.TENANT_CLAIM);
        if (Objects.isNull(tenant)) {
            return EnumSet.noneOf(Permission.class);
        }
        return TenantScope.callAs(UUID.fromString(tenant), () -> access.effectivePermissions(userId));
        // tenancy:else
        // return access.effectivePermissions(userId);
        // tenancy:end
    }
}
