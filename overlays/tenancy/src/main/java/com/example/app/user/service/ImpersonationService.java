package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.security.StaffAccounts;
// roles:begin
import com.example.app.common.tenancy.TenantScope;
// roles:end
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.tenant.api.PlatformAudit;
import com.example.app.tenant.api.SupportAccessPolicy;
import com.example.app.tenant.api.SupportAccessRequest;
import com.example.app.tenant.api.Tenants;
import com.example.app.user.domain.TenantMembership;
import com.example.app.user.dto.request.ImpersonationRequest;
import com.example.app.user.dto.response.TokenResponse;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// roles:begin
import java.util.Objects;
// roles:end
import java.util.UUID;

/** The audit row is written before the policy is asked, so a refused attempt is on record too. */
@Service
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ImpersonationService {

    static final String AUDIT_ACTION = "IMPERSONATION";

    private final MembershipService memberships;
    // roles:begin
    private final PersonAccessService access;
    // roles:end
    private final TokenService tokenService;
    private final Tenants tenants;
    private final SupportAccessPolicy policy;
    private final PlatformAudit audit;
    private final CurrentUser currentUser;
    private final StaffAccounts staffAccounts;

    public ObjectWrapper<TokenResponse> impersonate(UUID tenantId, ImpersonationRequest request) {
        UUID staffId = currentUser.id();
        // Read again at the issue, like every other token path: the request's filter read it a moment ago.
        if (!staffAccounts.isActive(staffId)) {
            throw AppException.of(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        tenants.requireActive(tenantId);
        // roles:begin
        // Who manages the tenant is the tenant's own data, read in its scope.
        UUID member = Objects.isNull(request.userId())
                ? TenantScope.callAs(tenantId, access::defaultToImpersonate)
                        .orElseThrow(AppException.supply(ErrorCode.MEMBERSHIP_NOT_FOUND, tenantId))
                : request.userId();
        TenantMembership membership = memberships.forImpersonation(tenantId, member);
        // roles:else
        // TenantMembership membership = memberships.forImpersonation(tenantId, request.userId());
        // roles:end
        UUID userId = membership.getUser().getId();
        audit.record(tenantId, AUDIT_ACTION, "userId=%s, reason=%s".formatted(userId, request.reason()));
        if (!policy.allows(new SupportAccessRequest(staffId, tenantId, userId, request.reason()))) {
            throw AppException.of(ErrorCode.SUPPORT_ACCESS_DENIED);
        }
        log.info("Impersonation issued: platformUserId={}, tenantId={}, userId={}", staffId, tenantId, userId);
        return ObjectWrapper.withObject(tokenService.issueImpersonation(membership, staffId));
    }
}
