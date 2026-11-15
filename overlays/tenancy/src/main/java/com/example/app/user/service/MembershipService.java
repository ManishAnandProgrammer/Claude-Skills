package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.pagination.Paging;
import com.example.app.common.tenancy.TenantContext;
import com.example.app.common.wrapper.ListWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.PaginationWrapper;
import com.example.app.tenant.api.TenantCreated;
// session-version:begin
import com.example.app.tenant.api.TenantDeactivated;
// session-version:end
import com.example.app.tenant.api.TenantProvisioned;
import com.example.app.tenant.api.TenantStatus;
import com.example.app.tenant.api.TenantView;
import com.example.app.tenant.api.Tenants;
import com.example.app.user.api.MemberView;
import com.example.app.user.api.Members;
import com.example.app.user.domain.Member;
import com.example.app.user.domain.Role;
import com.example.app.user.domain.TenantMembership;
import com.example.app.user.domain.User;
// session-version:begin
import com.example.app.user.dto.internal.MembershipSessionVersion;
// session-version:end
import com.example.app.user.dto.request.TenantMemberAddRequest;
import com.example.app.user.dto.request.TenantMemberFilter;
import com.example.app.user.dto.request.TenantMemberUpdateRequest;
import com.example.app.user.dto.response.MembershipResponse;
import com.example.app.user.dto.response.TenantMemberResponse;
import com.example.app.user.mapper.MembershipMapper;
import com.example.app.user.repository.MemberRepository;
import com.example.app.user.repository.TenantMembershipRepository;
import lombok.RequiredArgsConstructor;
// session-version:begin
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
// session-version:end
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
// session-version:begin
import org.springframework.transaction.annotation.Propagation;
// session-version:end
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Who belongs to which tenant, with which role. Tokens are issued for one membership at a time. Every
 * membership has its person in the tenant's own {@code members} table, written in the same transaction
 * (house-review TEN-17): the tenant's tables reference that row, never the platform's users. Other
 * modules ask {@link Members} whether a person belongs to the current tenant.
 *   <!-- session-version:begin -->
 *
 * <p>Each membership has a session version, which its tokens carry: every change of a membership's access
 * (role change, removal, the tenant's suspension, the member signing out everywhere) raises it in the
 * change's transaction and publishes {@link SessionVersionsRaised}, so the member's older tokens are
 * refused on their next request.</p>
 *   <!-- session-version:end -->
 */
@Service
// session-version:begin
@Slf4j
// session-version:end
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MembershipService implements Members {

    private final TenantMembershipRepository repository;
    private final MemberRepository members;
    private final UserService users;
    private final Tenants tenants;
    private final MembershipMapper mapper;
    private final Paging paging;
    private final Clock clock;
    // session-version:begin
    private final ApplicationEventPublisher events;
    // session-version:end

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
     * The current tenant's members, for its ADMINs: the rows carry the version a role change sends back.
     * Removed members are not listed (their membership is deleted; their row in the tenant's own data
     * stays, marked left).
     */
    public PaginationWrapper<TenantMemberResponse> findMembers(TenantMemberFilter filter) {
        return PaginationWrapper.fromPage(repository.findByFilter(filter, TenantContext.current(),
                paging.offset(filter, filter.getSort().toSort())));
    }

    /** 404 {@code TENANT_MEMBER_NOT_FOUND} for an account that isn't a member of the current tenant. */
    public ObjectWrapper<TenantMemberResponse> findMember(UUID userId) {
        return ObjectWrapper.withObject(mapper.toMemberResponse(getCurrentMembership(userId)));
    }

    @Override
    public boolean isMember(UUID userId) {
        return repository.existsByUserIdAndTenantId(userId, TenantContext.current());
    }

    @Override
    public MemberView getMemberView(UUID userId) {
        return mapper.toView(getCurrentMembership(userId));
    }

    @Override
    public List<MemberView> getMemberViews(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        return repository.findWithUserByTenantIdAndUserIdIn(TenantContext.current(), userIds).stream()
                .map(mapper::toView)
                .toList();
    }

    private TenantMembership getCurrentMembership(UUID userId) {
        return repository.findWithUserByUserIdAndTenantId(userId, TenantContext.current())
                .orElseThrow(AppException.supply(ErrorCode.TENANT_MEMBER_NOT_FOUND, userId));
    }

    /**
     * A tenant ADMIN adds an existing account to the caller's tenant: 404
     * {@code TENANT_MEMBER_ACCOUNT_NOT_FOUND} without one, 409 {@code TENANT_MEMBER_EXISTS} (the unique
     * pair) when it is already a member.
     */
    @Transactional
    public ObjectWrapper<TenantMemberResponse> addMember(UUID callerId, TenantMemberAddRequest request) {
        UUID tenantId = lockAsCurrentAdmin(callerId);
        User user = users.findByEmail(request.email())
                .orElseThrow(AppException.supply(ErrorCode.TENANT_MEMBER_ACCOUNT_NOT_FOUND));
        TenantMembership membership = repository.saveAndFlush(TenantMembership.of(user, tenantId, request.role()));
        joinTenantData(user.getId());
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
        // session-version:begin
        bumpSessionVersion(membership.getId());
        // session-version:end
        return ObjectWrapper.withObject(mapper.toMemberResponse(membership));
    }

    /**
     * A tenant ADMIN removes another member; the account stays, the tenant keeps at least one ADMIN. The
     * person's row in the tenant's data stays, marked left: their records still reference it.
     */
    @Transactional
    public void removeMember(UUID callerId, UUID userId) {
        TenantMembership membership = otherMemberUnderTenantLock(callerId, userId);
        if (membership.getRole() == Role.ADMIN) {
            requireAnotherAdmin(membership.getTenantId());
        }
        // session-version:begin
        // Raised before the row goes, so Redis gets a number no token carries: a request that read the old
        // one just before the commit can't fill it back.
        bumpSessionVersion(membership.getId());
        // session-version:end
        repository.delete(membership);
        members.findByUserId(userId).ifPresent(member -> member.leave(clock.instant()));
        repository.flush();
    }

    /**
     * 403 {@code TENANT_MEMBER_OWN_CHANGE} for the caller's own membership, 404
     * {@code TENANT_MEMBER_NOT_FOUND} for a user who isn't a member here.
     */
    private TenantMembership otherMemberUnderTenantLock(UUID callerId, UUID userId) {
        if (userId.equals(callerId)) {
            throw AppException.of(ErrorCode.TENANT_MEMBER_OWN_CHANGE);
        }
        UUID tenantId = lockAsCurrentAdmin(callerId);
        return repository.findByUserIdAndTenantId(userId, tenantId)
                .orElseThrow(AppException.supply(ErrorCode.TENANT_MEMBER_NOT_FOUND, userId));
    }

    /**
     * Locks the caller's tenant, then 403 {@code ACCESS_DENIED} unless the caller is an ADMIN there now.
     * The token's role is up to one lifetime old: a removed or demoted ADMIN could otherwise add
     * themselves back, or promote an account of theirs, and undo the change for good. Read under
     * the lock, so of two ADMINs demoting each other at once the second finds itself demoted.
     */
    private UUID lockAsCurrentAdmin(UUID callerId) {
        UUID tenantId = TenantContext.current();
        tenants.lockForMemberChange(tenantId);
        boolean admin = repository.findByUserIdAndTenantId(callerId, tenantId)
                .filter(membership -> membership.getRole() == Role.ADMIN)
                .isPresent();
        if (!admin) {
            throw AppException.of(ErrorCode.ACCESS_DENIED);
        }
        return tenantId;
    }

    /**
     * 409 {@code TENANT_MEMBER_LAST_ADMIN} unless the tenant has an ADMIN besides the one changing. The
     * caller is a current ADMIN and never changes their own membership, so the API can't reach it
     * today; it stays as the rule's own guard for any later path (staff, an invitation flow).
     */
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
     * The owner's row in the new tenant's data, once its tables exist: the membership was created with
     * the tenant, before schema per tenant had a schema to write it to (and row mode, without a tenant,
     * may not write a tenant row). In the activating transaction, under the tenant's scope; approving
     * again finds the row and adds none.
     */
    @EventListener
    @Transactional
    public void addOwnerToTenantData(TenantProvisioned provisioned) {
        TenantMembership owner = repository.findFirstByTenantIdAndRoleOrderById(provisioned.tenantId(), Role.ADMIN)
                .orElseThrow(() -> new IllegalStateException("A provisioned tenant without its owner: " + provisioned.tenantId()));
        joinTenantData(owner.getUser().getId());
    }

    /** The person's row in the current tenant's data: a new one, or theirs from before, no longer marked left. */
    private void joinTenantData(UUID userId) {
        Optional<Member> earlier = members.findByUserId(userId);
        if (earlier.isPresent()) {
            earlier.get().rejoin();
        } else {
            members.save(Member.of(userId));
        }
        members.flush();
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

    /** A token issue for the membership: login's "most recently used" order. The version stays. */
    @Transactional
    void markUsed(TenantMembership membership, Instant now) {
        repository.markUsed(membership.getId(), now);
    }

    /** The member staff act as: the given user, else the ADMIN with the oldest membership. 404 {@code MEMBERSHIP_NOT_FOUND}. */
    @Transactional(readOnly = true, noRollbackFor = AppException.class)
    TenantMembership forImpersonation(UUID tenantId, UUID userId) {
        Optional<TenantMembership> membership = Objects.isNull(userId)
                ? repository.findFirstByTenantIdAndRoleOrderById(tenantId, Role.ADMIN)
                : repository.findByUserIdAndTenantId(userId, tenantId);
        return membership.orElseThrow(AppException.supply(ErrorCode.MEMBERSHIP_NOT_FOUND, tenantId));
    }

    /** The user's membership of that tenant, if any: the tenant a refresh token was issued for (switch-tenant). */
    Optional<TenantMembership> findForTenant(User user, UUID tenantId) {
        return repository.findByUserIdAndTenantId(user.getId(), tenantId);
    }

    /** 404 {@code MEMBERSHIP_NOT_FOUND}: the user isn't a member of that tenant (or it doesn't exist). */
    @Transactional(readOnly = true, noRollbackFor = AppException.class)
    TenantMembership forTenant(User user, UUID tenantId) {
        return repository.findByUserIdAndTenantId(user.getId(), tenantId)
                .orElseThrow(AppException.supply(ErrorCode.MEMBERSHIP_NOT_FOUND, tenantId));
    }
    // session-version:begin

    /** The membership's session version (SessionVersionService's check, on a Redis miss); empty once removed. */
    Optional<Integer> findSessionVersion(UUID membershipId) {
        return repository.findSessionVersion(membershipId);
    }

    /** Signing out everywhere: every one of the user's memberships, in the caller's transaction (hence MANDATORY). */
    @Transactional(propagation = Propagation.MANDATORY)
    void bumpSessionVersionsOfUser(UUID userId) {
        repository.bumpSessionVersionsOfUser(userId);
        publish(repository.findSessionVersionsOfUser(userId));
        log.info("Session versions raised: userId={}", userId);
    }

    /**
     * A tenant left ACTIVE: every member's tokens there stop at once, in one statement, in the status change's
     * own transaction (hence MANDATORY). Their refresh tokens are refused while the tenant isn't ACTIVE
     * (TokenService), and work again once it is.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void endTenantSessions(TenantDeactivated deactivated) {
        int memberships = repository.bumpSessionVersionsOfTenant(deactivated.tenantId());
        publish(repository.findSessionVersionsOfTenant(deactivated.tenantId()));
        log.info("Session versions raised: tenantId={}, memberships={}", deactivated.tenantId(), memberships);
    }

    /** In the access change's own transaction: two transactions could commit the change without the bump. */
    private void bumpSessionVersion(UUID membershipId) {
        repository.bumpSessionVersion(membershipId);
        int raised = repository.findSessionVersion(membershipId)
                .orElseThrow(() -> new IllegalStateException("A bumped membership without its row: " + membershipId));
        events.publishEvent(new SessionVersionsRaised(Map.of(membershipId, raised)));
        log.info("Session version raised: membershipId={}, sv={}", membershipId, raised);
    }

    private void publish(List<MembershipSessionVersion> versions) {
        events.publishEvent(new SessionVersionsRaised(versions.stream()
                .collect(Collectors.toMap(MembershipSessionVersion::membershipId, MembershipSessionVersion::sessionVersion))));
    }
    // session-version:end
}
