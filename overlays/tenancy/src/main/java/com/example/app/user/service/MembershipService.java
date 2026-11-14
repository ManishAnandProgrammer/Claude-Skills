package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.tenancy.TenantContext;
import com.example.app.common.wrapper.ListWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.tenant.api.TenantCreated;
import com.example.app.tenant.api.TenantStatus;
import com.example.app.tenant.api.TenantView;
import com.example.app.tenant.api.Tenants;
import com.example.app.user.domain.Role;
import com.example.app.user.domain.TenantMembership;
import com.example.app.user.domain.User;
import com.example.app.user.dto.request.TenantMemberAddRequest;
import com.example.app.user.dto.request.TenantMemberUpdateRequest;
import com.example.app.user.dto.response.MembershipResponse;
import com.example.app.user.dto.response.TenantMemberResponse;
import com.example.app.user.mapper.MembershipMapper;
import com.example.app.user.repository.TenantMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Who belongs to which tenant, with which role. Tokens are issued for one membership at a time. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MembershipService {

    private final TenantMembershipRepository repository;
    private final UserService users;
    private final Tenants tenants;
    private final MembershipMapper mapper;

    /**
     * The signed-in user's tenants, most recently used first: what a tenant picker shows, and the ids
     * switch-tenant takes. A user belongs to a handful of tenants, so it is one list, not a page.
     */
    public ListWrapper<MembershipResponse> mine(UUID userId) {
        List<TenantMembership> memberships = repository.findByUserIdMostRecentlyUsedFirst(userId);
        Map<UUID, TenantView> byId = tenants.findAllById(memberships.stream().map(TenantMembership::getTenantId).toList())
                .stream().collect(Collectors.toMap(TenantView::id, Function.identity()));
        UUID currentTenantId = TenantContext.current();
        return ListWrapper.fromList(memberships.stream()
                .map(membership -> mapper.toResponse(membership, byId.get(membership.getTenantId()), currentTenantId))
                .toList());
    }

    /**
     * A tenant ADMIN adds an existing account to the caller's tenant: 404
     * {@code TENANT_MEMBER_ACCOUNT_NOT_FOUND} without one, 409 {@code TENANT_MEMBER_EXISTS} (the unique
     * pair) when it is already a member. Adding never removes an ADMIN, so it takes no lock.
     */
    @Transactional
    public ObjectWrapper<TenantMemberResponse> addMember(TenantMemberAddRequest request) {
        User user = users.findByEmail(request.email())
                .orElseThrow(AppException.supply(ErrorCode.TENANT_MEMBER_ACCOUNT_NOT_FOUND));
        TenantMembership membership = repository.saveAndFlush(TenantMembership.of(user, TenantContext.current(), request.role()));
        return ObjectWrapper.withObject(mapper.toMemberResponse(membership));
    }

    /** A tenant ADMIN changes another member's role; the tenant keeps at least one ADMIN. */
    @Transactional
    public ObjectWrapper<TenantMemberResponse> changeRole(UUID callerId, UUID userId, TenantMemberUpdateRequest request) {
        TenantMembership membership = otherMemberUnderTenantLock(callerId, userId);
        membership.checkVersion(request.version());
        if (membership.getRole() == Role.ADMIN && request.role() != Role.ADMIN) {
            requireAnotherAdmin(membership.getTenantId());
        }
        membership.changeRole(request.role());
        repository.flush();
        return ObjectWrapper.withObject(mapper.toMemberResponse(membership));
    }

    /** A tenant ADMIN removes another member; the account stays, the tenant keeps at least one ADMIN. */
    @Transactional
    public void removeMember(UUID callerId, UUID userId) {
        TenantMembership membership = otherMemberUnderTenantLock(callerId, userId);
        if (membership.getRole() == Role.ADMIN) {
            requireAnotherAdmin(membership.getTenantId());
        }
        repository.delete(membership);
        repository.flush();
    }

    /**
     * 409 {@code TENANT_MEMBER_OWN_CHANGE} for the caller's own membership, 404
     * {@code TENANT_MEMBER_NOT_FOUND} for a user who isn't a member here. The tenant is locked first:
     * two ADMINs demoting each other at once (one with a token that predates their own demotion) would
     * otherwise each count the other and leave none.
     */
    private TenantMembership otherMemberUnderTenantLock(UUID callerId, UUID userId) {
        if (userId.equals(callerId)) {
            throw AppException.of(ErrorCode.TENANT_MEMBER_OWN_CHANGE);
        }
        UUID tenantId = TenantContext.current();
        tenants.lockForMemberChange(tenantId);
        return repository.findByUserIdAndTenantId(userId, tenantId)
                .orElseThrow(AppException.supply(ErrorCode.TENANT_MEMBER_NOT_FOUND, userId));
    }

    /** 409 {@code TENANT_MEMBER_LAST_ADMIN} unless the tenant has an ADMIN besides the one changing. */
    private void requireAnotherAdmin(UUID tenantId) {
        if (repository.countByTenantIdAndRole(tenantId, Role.ADMIN) < 2) {
            throw AppException.of(ErrorCode.TENANT_MEMBER_LAST_ADMIN);
        }
    }

    /**
     * The new tenant's owner becomes its ADMIN, in the creating transaction: an email without an account
     * is 404 {@code TENANT_OWNER_NOT_FOUND} and no tenant is created (invitations come later).
     */
    @EventListener
    @Transactional
    public void addOwner(TenantCreated created) {
        User owner = users.findByEmail(created.ownerEmail())
                .orElseThrow(AppException.supply(ErrorCode.TENANT_OWNER_NOT_FOUND));
        repository.save(TenantMembership.of(owner, created.tenantId(), Role.ADMIN));
    }

    /**
     * Login's membership: the most recently used one whose tenant is ACTIVE, so a suspended or closed
     * tenant never locks its member out of their other tenants; without an active one, the most recently
     * used (its issue answers 403 {@code TENANT_NOT_ACTIVE}). 403 {@code TENANT_MEMBERSHIP_REQUIRED}
     * without any. A refusal here never dooms the caller's transaction (nor in {@link #forTenant}): a
     * refresh commits its token's rotation even when it refuses.
     */
    @Transactional(readOnly = true, noRollbackFor = AppException.class)
    TenantMembership forLogin(User user) {
        List<TenantMembership> memberships = repository.findByUserIdMostRecentlyUsedFirst(user.getId());
        if (memberships.isEmpty()) {
            throw AppException.of(ErrorCode.TENANT_MEMBERSHIP_REQUIRED);
        }
        Set<UUID> activeTenantIds = tenants.findAllById(memberships.stream().map(TenantMembership::getTenantId).toList())
                .stream()
                .filter(tenant -> tenant.status() == TenantStatus.ACTIVE)
                .map(TenantView::id)
                .collect(Collectors.toSet());
        return memberships.stream()
                .filter(membership -> activeTenantIds.contains(membership.getTenantId()))
                .findFirst()
                .orElse(memberships.getFirst());
    }

    /** The member staff act as: the given user, else the tenant's oldest ADMIN. 404 {@code MEMBERSHIP_NOT_FOUND}. */
    @Transactional(readOnly = true, noRollbackFor = AppException.class)
    TenantMembership forImpersonation(UUID tenantId, UUID userId) {
        Optional<TenantMembership> membership = Objects.isNull(userId)
                ? repository.findFirstByTenantIdAndRoleOrderById(tenantId, Role.ADMIN)
                : repository.findByUserIdAndTenantId(userId, tenantId);
        return membership.orElseThrow(AppException.supply(ErrorCode.MEMBERSHIP_NOT_FOUND, tenantId));
    }

    /** 404 {@code MEMBERSHIP_NOT_FOUND}: the user isn't a member of that tenant (or it doesn't exist). */
    @Transactional(readOnly = true, noRollbackFor = AppException.class)
    TenantMembership forTenant(User user, UUID tenantId) {
        return repository.findByUserIdAndTenantId(user.getId(), tenantId)
                .orElseThrow(AppException.supply(ErrorCode.MEMBERSHIP_NOT_FOUND, tenantId));
    }
}
