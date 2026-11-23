package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.pagination.Paging;
// roles:begin
// roles:else
// import com.example.app.common.security.Permission;
// roles:end
import com.example.app.common.tenancy.TenantContext;
import com.example.app.common.wrapper.BoundedListWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.PaginationWrapper;
import com.example.app.tenant.api.TenantCreated;
// session-version:begin
import com.example.app.tenant.api.ImpersonatedMember;
import com.example.app.tenant.api.TenantDeactivated;
// session-version:end
// roles:begin
// roles:else
// import com.example.app.tenant.api.TenantProvisioned;
// roles:end
import com.example.app.tenant.api.TenantStatus;
import com.example.app.tenant.api.TenantView;
import com.example.app.tenant.api.Tenants;
import com.example.app.user.api.MemberView;
import com.example.app.user.api.Members;
import com.example.app.user.domain.Member;
// roles:begin
// roles:else
// import com.example.app.user.domain.Role;
// roles:end
import com.example.app.user.domain.TenantMembership;
import com.example.app.user.domain.User;
// session-version:begin
import com.example.app.user.dto.internal.MembershipSessionVersion;
// session-version:end
// roles:begin
// roles:else
// import com.example.app.user.dto.request.TenantMemberAddRequest;
// roles:end
import com.example.app.user.dto.request.TenantMemberFilter;
// roles:begin
// roles:else
// import com.example.app.user.dto.request.TenantMemberUpdateRequest;
// roles:end
import com.example.app.user.dto.response.MembershipResponse;
import com.example.app.user.dto.response.TenantMemberResponse;
import com.example.app.user.mapper.MembershipMapper;
import com.example.app.user.repository.MemberRepository;
import com.example.app.user.repository.TenantMembershipRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
// session-version:begin
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
// session-version:end
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
// roles:begin
import java.util.ArrayList;
// roles:end
import java.util.Collection;
import java.util.List;
import java.util.Map;
// roles:begin
// roles:else
// import java.util.Objects;
// roles:end
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
// session-version:begin
@Slf4j
// session-version:end
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Transactional(readOnly = true)
public class MembershipService implements Members {

    private final TenantMembershipRepository repository;
    private final MemberRepository members;
    private final UserService users;
    private final Tenants tenants;
    private final MembershipMapper mapper;
    private final Paging paging;
    private final Clock clock;
    // roles:begin
    // roles:else
    // private final MemberRateLimits rateLimits;
    // roles:end
    // session-version:begin
    private final ApplicationEventPublisher events;
    // session-version:end

    /** A user belongs to a handful of tenants: one list, not a page. */
    public BoundedListWrapper<MembershipResponse> mine(UUID userId) {
        List<TenantMembership> memberships = repository.findByUserIdMostRecentlyUsedFirst(userId);
        Map<UUID, TenantView> byId = tenants.findAllById(memberships.stream().map(TenantMembership::getTenantId).toList())
                .stream().collect(Collectors.toMap(TenantView::id, Function.identity()));
        UUID currentTenantId = TenantContext.current();
        return BoundedListWrapper.fromList(memberships.stream()
                .map(membership -> mapper.toResponse(membership, byId.get(membership.getTenantId()), currentTenantId))
                .toList());
    }

    public PaginationWrapper<TenantMemberResponse> findMembers(TenantMemberFilter filter) {
        return PaginationWrapper.fromPage(repository.findByFilter(filter, TenantContext.current(),
                paging.offset(filter)));
    }

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

    // roles:begin
    void requireMember(UUID userId) {
        if (!isMember(userId)) {
            throw AppException.of(ErrorCode.TENANT_MEMBER_NOT_FOUND, userId);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    TenantMembership add(String email) {
        User user = users.findByEmail(email).orElseThrow(AppException.supply(ErrorCode.TENANT_MEMBER_ACCOUNT_NOT_FOUND));
        TenantMembership membership = repository.saveAndFlush(TenantMembership.of(user, TenantContext.id()));
        joinTenantData(user.getId());
        return membership;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void remove(UUID userId) {
        TenantMembership membership = repository.findByUserIdAndTenantId(userId, TenantContext.id())
                .orElseThrow(AppException.supply(ErrorCode.TENANT_MEMBER_NOT_FOUND, userId));
        // session-version:begin
        // Raised before the row goes, so Redis gets a number no token carries: a request that read the old
        // one just before the commit can't fill it back.
        bumpSessionVersion(membership.getId());
        // session-version:end
        repository.delete(membership);
        members.findByUserId(userId).ifPresent(member -> member.leave(clock.instant()));
        repository.flush();
    }

    TenantMemberResponse toMemberResponse(TenantMembership membership) {
        return mapper.toMemberResponse(membership);
    }

    /**
     * Each locked in id order, as every access change there locks it first, so that what the caller then counts in
     * each can't change before the suspension commits. A PENDING tenant has no access yet to keep; CLOSED and
     * REJECTED need no manager.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<UUID> lockOpenTenantsOf(UUID userId) {
        List<UUID> tenantIds = repository.findTenantIdsByUserId(userId);
        Set<UUID> openTenantIds = tenants.findAllById(tenantIds).stream()
                .filter(tenant -> tenant.status() == TenantStatus.ACTIVE || tenant.status() == TenantStatus.SUSPENDED)
                .map(TenantView::id)
                .collect(Collectors.toSet());
        List<UUID> locked = new ArrayList<>();
        for (UUID tenantId : tenantIds) {
            if (openTenantIds.contains(tenantId)) {
                tenants.lockForMemberChange(tenantId);
                locked.add(tenantId);
            }
        }
        return locked;
    }

    Optional<UUID> oldestOf(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Optional.empty();
        }
        return repository.findFirstByTenantIdAndUserIdInOrderById(TenantContext.id(), userIds)
                .map(membership -> membership.getUser().getId());
    }
    // roles:else
    // @Transactional
    // public ObjectWrapper<TenantMemberResponse> addMember(UUID callerId, TenantMemberAddRequest request) {
    //     // Before the first statement, so the connection isn't borrowed while Redis answers.
    //     rateLimits.add(callerId);
    //     UUID tenantId = lockAsCurrentAdmin(callerId);
    //     User user = users.findByEmail(request.email())
    //             .orElseThrow(AppException.supply(ErrorCode.TENANT_MEMBER_ACCOUNT_NOT_FOUND));
    //     TenantMembership membership = repository.saveAndFlush(TenantMembership.of(user, tenantId, request.role()));
    //     joinTenantData(user.getId());
    //     return ObjectWrapper.withObject(mapper.toMemberResponse(membership));
    // }
    //
    // @Transactional
    // public ObjectWrapper<TenantMemberResponse> changeRole(UUID callerId, UUID userId, TenantMemberUpdateRequest request) {
    //     TenantMembership membership = otherMemberUnderTenantLock(callerId, userId);
    //     membership.checkVersion(request.version());
    //     if (membership.getRole() == Role.ADMIN && request.role() != Role.ADMIN) {
    //         requireAnotherAdmin(membership.getTenantId());
    //     }
    //     membership.changeRole(request.role());
    //     repository.flush();
    //     // session-version:begin
    //     bumpSessionVersion(membership.getId());
    //     // session-version:end
    //     return ObjectWrapper.withObject(mapper.toMemberResponse(membership));
    // }
    //
    // @Transactional
    // public void removeMember(UUID callerId, UUID userId) {
    //     TenantMembership membership = otherMemberUnderTenantLock(callerId, userId);
    //     if (membership.getRole() == Role.ADMIN) {
    //         requireAnotherAdmin(membership.getTenantId());
    //     }
    //     // session-version:begin
    //     // Raised before the row goes, so Redis gets a number no token carries: a request that read the old
    //     // one just before the commit can't fill it back.
    //     bumpSessionVersion(membership.getId());
    //     // session-version:end
    //     repository.delete(membership);
    //     members.findByUserId(userId).ifPresent(member -> member.leave(clock.instant()));
    //     repository.flush();
    // }
    //
    // private TenantMembership otherMemberUnderTenantLock(UUID callerId, UUID userId) {
    //     if (userId.equals(callerId)) {
    //         throw AppException.of(ErrorCode.TENANT_MEMBER_OWN_CHANGE);
    //     }
    //     UUID tenantId = lockAsCurrentAdmin(callerId);
    //     return repository.findByUserIdAndTenantId(userId, tenantId)
    //             .orElseThrow(AppException.supply(ErrorCode.TENANT_MEMBER_NOT_FOUND, userId));
    // }
    //
    // /**
    //  * The token's role is up to one lifetime old: a removed or demoted ADMIN could otherwise add
    //  * themselves back, or promote an account of theirs, and undo the change for good. Read under
    //  * the lock, so of two ADMINs demoting each other at once the second finds itself demoted.
    //  */
    // private UUID lockAsCurrentAdmin(UUID callerId) {
    //     UUID tenantId = TenantContext.current();
    //     tenants.lockForMemberChange(tenantId);
    //     boolean mayManageMembers = repository.findByUserIdAndTenantId(callerId, tenantId)
    //             .filter(membership -> membership.getRole().grants(Permission.TENANT_MEMBERS_MANAGE))
    //             .isPresent();
    //     if (!mayManageMembers) {
    //         throw AppException.of(ErrorCode.ACCESS_DENIED);
    //     }
    //     return tenantId;
    // }
    //
    // /**
    //  * Unreachable through today's API (the caller is an ADMIN and never changes their own membership): the
    //  * rule's own guard for any later path.
    //  */
    // private void requireAnotherAdmin(UUID tenantId) {
    //     if (repository.countByTenantIdAndRole(tenantId, Role.ADMIN) < 2) {
    //         throw AppException.of(ErrorCode.TENANT_MEMBER_LAST_ADMIN);
    //     }
    // }
    //
    // /**
    //  * Each tenant where they are ADMIN is locked first, in id order, as a member change locks it: of two
    //  * suspensions of a tenant's last two ADMINs, the second counts after the first committed.
    //  */
    // @Transactional(propagation = Propagation.MANDATORY)
    // void requireAnotherAdminWhereAdmin(UUID userId) {
    //     List<UUID> tenantIds = repository.findTenantIdsWhereAdmin(userId);
    //     Set<UUID> openTenantIds = tenants.findAllById(tenantIds).stream()
    //             .filter(tenant -> tenant.status() != TenantStatus.CLOSED && tenant.status() != TenantStatus.REJECTED)
    //             .map(TenantView::id)
    //             .collect(Collectors.toSet());
    //     for (UUID tenantId : tenantIds) {
    //         if (openTenantIds.contains(tenantId)) {
    //             tenants.lockForMemberChange(tenantId);
    //             if (repository.countOtherActiveAdmins(tenantId, userId) == 0) {
    //                 throw AppException.of(ErrorCode.TENANT_MEMBER_LAST_ADMIN);
    //             }
    //         }
    //     }
    // }
    // roles:end

    @EventListener
    @Transactional
    public void addOwner(TenantCreated created) {
        User owner = users.findByEmail(created.ownerEmail())
                .orElseThrow(AppException.supply(ErrorCode.TENANT_OWNER_NOT_FOUND));
        // roles:begin
        repository.save(TenantMembership.of(owner, created.tenantId()));
        // roles:else
        // repository.save(TenantMembership.of(owner, created.tenantId(), Role.ADMIN));
        // roles:end
    }

    /**
     * Not with the membership: schema per tenant had no schema to write the row to yet, and row mode, without a
     * tenant, may not write a tenant row.
     */
    // roles:begin
    // Before the tenant's first activation nobody can add a member (that needs a token of the ACTIVE tenant), so the
    // owner's is the tenant's oldest membership.
    @Transactional(propagation = Propagation.MANDATORY)
    UUID addOwnerToTenantData(UUID tenantId) {
        TenantMembership owner = repository.findFirstByTenantIdOrderById(tenantId)
                .orElseThrow(() -> new IllegalStateException("A provisioned tenant without its owner: " + tenantId));
        joinTenantData(owner.getUser().getId());
        return owner.getUser().getId();
    }
    // roles:else
    // @EventListener
    // @Transactional
    // public void addOwnerToTenantData(TenantProvisioned provisioned) {
    //     TenantMembership owner = repository.findFirstByTenantIdAndRoleOrderById(provisioned.tenantId(), Role.ADMIN)
    //             .orElseThrow(() -> new IllegalStateException("A provisioned tenant without its owner: " + provisioned.tenantId()));
    //     joinTenantData(owner.getUser().getId());
    // }
    // roles:end

    private void joinTenantData(UUID userId) {
        Optional<Member> earlier = members.findByUserId(userId);
        if (earlier.isPresent()) {
            earlier.get().rejoin();
        } else {
            members.save(Member.of(userId));
        }
        members.flush();
    }

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

    @Transactional
    void markUsed(TenantMembership membership, Instant now) {
        repository.markUsed(membership.getId(), now);
    }

    // roles:begin
    @Transactional(readOnly = true, noRollbackFor = AppException.class)
    TenantMembership forImpersonation(UUID tenantId, UUID userId) {
        return repository.findByUserIdAndTenantId(userId, tenantId)
                .orElseThrow(AppException.supply(ErrorCode.MEMBERSHIP_NOT_FOUND, tenantId));
    }
    // roles:else
    // @Transactional(readOnly = true, noRollbackFor = AppException.class)
    // TenantMembership forImpersonation(UUID tenantId, UUID userId) {
    //     Optional<TenantMembership> membership = Objects.isNull(userId)
    //             ? repository.findFirstByTenantIdAndRoleOrderById(tenantId, Role.ADMIN)
    //             : repository.findByUserIdAndTenantId(userId, tenantId);
    //     return membership.orElseThrow(AppException.supply(ErrorCode.MEMBERSHIP_NOT_FOUND, tenantId));
    // }
    // roles:end

    Optional<TenantMembership> findForTenant(User user, UUID tenantId) {
        return repository.findByUserIdAndTenantId(user.getId(), tenantId);
    }

    /** A refusal leaves the caller's transaction committable: a refresh commits its own writes even when it refuses. */
    @Transactional(readOnly = true, noRollbackFor = AppException.class)
    TenantMembership forTenant(User user, UUID tenantId) {
        return repository.findByUserIdAndTenantId(user.getId(), tenantId)
                .orElseThrow(AppException.supply(ErrorCode.MEMBERSHIP_NOT_FOUND, tenantId));
    }

    @Transactional(readOnly = true, noRollbackFor = AppException.class)
    void requireMembership(User user, UUID tenantId) {
        if (!repository.existsByUserIdAndTenantId(user.getId(), tenantId)) {
            throw AppException.of(ErrorCode.MEMBERSHIP_NOT_FOUND, tenantId);
        }
    }
    // session-version:begin

    Optional<Integer> findSessionVersion(UUID membershipId) {
        return repository.findSessionVersion(membershipId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void bumpSessionVersionsOfUser(UUID userId) {
        repository.bumpSessionVersionsOfUser(userId);
        publish(repository.findSessionVersionsOfUser(userId));
        log.info("Session versions raised: userId={}", userId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void bumpSessionVersionsOf(Collection<ImpersonatedMember> impersonated) {
        if (impersonated.isEmpty()) {
            return;
        }
        Set<ImpersonatedMember> wanted = Set.copyOf(impersonated);
        List<UUID> membershipIds = repository.findByTenantIdInAndUserIdIn(
                        wanted.stream().map(ImpersonatedMember::tenantId).collect(Collectors.toSet()),
                        wanted.stream().map(ImpersonatedMember::userId).collect(Collectors.toSet()))
                .stream()
                .filter(membership -> wanted.contains(new ImpersonatedMember(membership.getTenantId(), membership.getUser().getId())))
                .map(TenantMembership::getId)
                .toList();
        if (membershipIds.isEmpty()) {
            return;
        }
        repository.bumpSessionVersions(membershipIds);
        publish(repository.findSessionVersions(membershipIds));
        log.info("Session versions raised: memberships={}", membershipIds.size());
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void endTenantSessions(TenantDeactivated deactivated) {
        int memberships = repository.bumpSessionVersionsOfTenant(deactivated.tenantId());
        publish(repository.findSessionVersionsOfTenant(deactivated.tenantId()));
        log.info("Session versions raised: tenantId={}, memberships={}", deactivated.tenantId(), memberships);
    }

    /** In the access change's own transaction: in two, the change could commit without the bump. */
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
