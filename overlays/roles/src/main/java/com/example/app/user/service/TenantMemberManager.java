package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.Permission;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.tenant.api.TenantProvisioned;
import com.example.app.user.domain.Role;
import com.example.app.user.domain.TenantMembership;
import com.example.app.user.dto.request.TenantMemberAddRequest;
import com.example.app.user.dto.response.TenantMemberResponse;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class TenantMemberManager {

    private final MembershipService memberships;
    private final PersonAccessService access;
    // tenancy-row:begin
    private final AccessRoleService roles;
    // tenancy-row:end
    private final MemberRateLimits rateLimits;

    @Transactional
    public ObjectWrapper<TenantMemberResponse> add(UUID callerId, TenantMemberAddRequest request) {
        // Before the first statement, so the connection isn't borrowed while Redis answers.
        rateLimits.add(callerId);
        access.lockToAddMember(callerId, Role.USER);
        TenantMembership membership = memberships.add(request.email());
        UUID userId = membership.getUser().getId();
        access.giveBuiltIn(userId, Role.USER);
        log.info("Member added: userId={}", userId);
        return ObjectWrapper.withObject(memberships.toMemberResponse(membership));
    }

    @Transactional
    public void remove(UUID callerId, UUID userId) {
        if (userId.equals(callerId)) {
            throw AppException.of(ErrorCode.TENANT_MEMBER_OWN_CHANGE);
        }
        access.lockToManage(callerId, userId, Permission.TENANT_MEMBERS_MANAGE);
        access.takeAllAway(userId);
        memberships.remove(userId);
        access.requireManagersRemain();
        log.info("Member removed: userId={}", userId);
    }

    /**
     * Approving again after a failure finds what a first try left and adds nothing.
     *   <!-- tenancy-row:begin -->
     * Row mode copies the built-in roles from the {@link Role} enum first: the roles changeset ran before the tenant
     * existed.
     *   <!-- tenancy-row:end -->
     */
    @EventListener
    @Transactional
    public void provisioned(TenantProvisioned provisioned) {
        // tenancy-row:begin
        roles.copyBuiltInsFromTheEnum();
        // tenancy-row:end
        UUID ownerId = memberships.addOwnerToTenantData(provisioned.tenantId());
        access.giveBuiltIn(ownerId, Role.ADMIN);
    }
}
