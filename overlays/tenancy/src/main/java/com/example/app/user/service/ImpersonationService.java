package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.security.StaffAccounts;
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

import java.util.UUID;

/**
 * Platform staff acting as a member of a tenant (owner decision O6): a short-lived tenant token for
 * that member, with the staff member as its actor ({@code act.sub}, RFC 8693). The tenant plane takes
 * it like any tenant token; {@code CurrentUser.actorId} and the logs name the real person. Every
 * issuance is a {@code platform_audit} row, written before the policy is asked, so a refused attempt
 * is on record too.
 */
@Service
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ImpersonationService {

    static final String AUDIT_ACTION = "IMPERSONATION";

    private final MembershipService memberships;
    private final TokenService tokenService;
    private final Tenants tenants;
    private final SupportAccessPolicy policy;
    private final PlatformAudit audit;
    private final CurrentUser currentUser;
    private final StaffAccounts staffAccounts;

    /**
     * 401 {@code AUTHENTICATION_REQUIRED} for a staff member deactivated since their token's check, 403
     * {@code TENANT_NOT_ACTIVE}, 404 {@code MEMBERSHIP_NOT_FOUND}, 403 {@code SUPPORT_ACCESS_DENIED}, 403
     * {@code AUTH_ACCOUNT_SUSPENDED} (the member's account).
     */
    public ObjectWrapper<TokenResponse> impersonate(UUID tenantId, ImpersonationRequest request) {
        UUID staffId = currentUser.id();
        // Read again at the issue, like every other token path: the request's filter read it a moment ago.
        if (!staffAccounts.isActive(staffId)) {
            throw AppException.of(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        tenants.requireActive(tenantId);
        TenantMembership membership = memberships.forImpersonation(tenantId, request.userId());
        UUID userId = membership.getUser().getId();
        audit.record(tenantId, AUDIT_ACTION, "userId=%s, reason=%s".formatted(userId, request.reason()));
        if (!policy.allows(new SupportAccessRequest(staffId, tenantId, userId, request.reason()))) {
            throw AppException.of(ErrorCode.SUPPORT_ACCESS_DENIED);
        }
        log.info("Impersonation issued: platformUserId={}, tenantId={}, userId={}", staffId, tenantId, userId);
        return ObjectWrapper.withObject(tokenService.issueImpersonation(membership, staffId));
    }
}
