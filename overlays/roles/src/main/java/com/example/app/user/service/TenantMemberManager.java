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

/**
 * The changes of a tenant's members that are also changes of their access (the roles piece with tenancy), across two
 * aggregates: the membership ({@link MembershipService}) and the person's access in the tenant
 * ({@link PersonAccessService}), which therefore never call each other (house-review ARCH-15's Manager). Each runs in
 * one transaction, under the lock every access change in the tenant takes first (its tenant row), and checks the
 * caller's access as the database holds it then, whatever their token's request read before:
 * <ul>
 *   <li>adding a member gives them the tenant's built-in {@code USER} role, which the caller must hold all of (safety
 *       rule 1);</li>
 *   <li>removing one takes their roles and grants away with the membership (added again, they start with USER alone),
 *       never the caller's own, never someone who holds a permission the caller doesn't (rule 3), and never the last
 *       active member holding, for good, a permission that manages the tenant (rule 6);</li>
 *   <li>a new tenant's provisioning gives its owner the tenant's built-in {@code ADMIN} role.</li>
 * </ul>
 */
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

    /**
     * 429 {@code RATE_LIMITED} past the caller's limit ({@link MemberRateLimits}), 403 {@code ACCESS_DENIED} unless the
     * caller holds {@code TENANT_MEMBERS_MANAGE} now, 403 {@code ACCESS_NOT_HELD}, 404
     * {@code TENANT_MEMBER_ACCOUNT_NOT_FOUND}, 409 {@code TENANT_MEMBER_EXISTS}.
     */
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

    /**
     * 403 {@code TENANT_MEMBER_OWN_CHANGE} for the caller's own membership, 403 {@code ACCESS_DENIED}, 403
     * {@code ACCESS_TARGET_HOLDS_MORE}, 404 {@code TENANT_MEMBER_NOT_FOUND}, 409 {@code ACCESS_LAST_MANAGER}.
     */
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
     * The new tenant's tables exist (under its scope, in the activating transaction): the owner's row in its data and
     * the owner's built-in ADMIN role. Approving again after a failure finds what a first try left and adds nothing.
     *   <!-- tenancy-row:begin -->
     * Row mode copies the tenant's built-in roles from the {@link Role} enum first: the roles changeset ran before the
     * tenant existed (schema per tenant seeds them in the tenant changelog, which runs into every new schema).
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
