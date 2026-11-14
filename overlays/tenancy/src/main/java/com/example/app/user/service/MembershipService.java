package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.tenancy.TenantContext;
import com.example.app.common.wrapper.ListWrapper;
import com.example.app.tenant.api.TenantCreated;
import com.example.app.tenant.api.TenantView;
import com.example.app.tenant.api.Tenants;
import com.example.app.user.domain.Role;
import com.example.app.user.domain.TenantMembership;
import com.example.app.user.domain.User;
import com.example.app.user.dto.response.MembershipResponse;
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
     * Login's membership: the most recently used one. 403 {@code TENANT_MEMBERSHIP_REQUIRED} without any.
     * A refusal here never dooms the caller's transaction (nor in {@link #forTenant}): a refresh commits
     * its token's rotation even when it refuses.
     */
    @Transactional(readOnly = true, noRollbackFor = AppException.class)
    TenantMembership forLogin(User user) {
        return repository.findMostRecentlyUsed(user.getId())
                .orElseThrow(AppException.supply(ErrorCode.TENANT_MEMBERSHIP_REQUIRED));
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
