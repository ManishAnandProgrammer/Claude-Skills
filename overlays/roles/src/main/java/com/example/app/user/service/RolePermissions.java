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
 * A token's permissions with the roles piece: what its bearer holds now, read from the tables on every request
 * ({@link PersonAccessService#effectivePermissions}, one statement, joined with the account's status). The token
 * carries the user's id and nothing about access, so a change of roles or grants, or a suspension, reaches the
 * person's next request, and {@code GET /users/me/access} shows the same set every check reads. No cache: the one
 * indexed read is the cost.
 *   <!-- tenancy:begin -->
 * A tenant token is read in its tenant's context: the roles are the tenant's. A platform token (staff, no tenant)
 * holds no tenant permission.
 *   <!-- tenancy:end -->
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
