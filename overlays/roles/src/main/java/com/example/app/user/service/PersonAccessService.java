package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.Permission;
// tenancy:begin
import com.example.app.common.tenancy.TenantContext;
// tenancy:end
import com.example.app.common.wrapper.ObjectWrapper;
// tenancy:begin
import com.example.app.tenant.api.Tenants;
// tenancy:end
import com.example.app.user.domain.AccessRole;
import com.example.app.user.domain.EffectiveAccess;
import com.example.app.user.domain.GrantEffect;
import com.example.app.user.domain.PersonalGrant;
import com.example.app.user.domain.Role;
import com.example.app.user.domain.RoleAssignment;
import com.example.app.user.domain.AccessRow;
// tenancy:begin
// tenancy:else
// import com.example.app.user.dto.internal.AccountCreated;
// tenancy:end
import com.example.app.user.dto.request.PersonalGrantRequest;
import com.example.app.user.dto.request.RoleAssignmentRequest;
import com.example.app.user.dto.response.PersonAccessResponse;
import com.example.app.user.mapper.PersonAccessMapper;
import com.example.app.user.repository.PersonalGrantRepository;
import com.example.app.user.repository.RoleAssignmentRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// tenancy:begin
// tenancy:else
// import org.springframework.context.event.EventListener;
// tenancy:end
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Transactional(readOnly = true)
public class PersonAccessService {

    private static final Logger AUTHORIZATION_LOG = LoggerFactory.getLogger("com.example.app.authorization");

    // tenancy:begin
    /**
     * Without one of them held for good, nobody could ever change access, or the members, again: you give only what
     * you hold.
     */
    static final Set<Permission> KEPT_HELD = EnumSet.of(Permission.ROLES_MANAGE, Permission.ACCESS_GRANT,
            Permission.TENANT_MEMBERS_MANAGE);
    // tenancy:else
    // /** Without either held for good, nobody could ever change access again: you give only what you hold. */
    // static final Set<Permission> KEPT_HELD = EnumSet.of(Permission.ROLES_MANAGE, Permission.ACCESS_GRANT);
    // tenancy:end

    private final RoleAssignmentRepository assignments;
    private final PersonalGrantRepository grants;
    private final AccessRoleService roles;
    // tenancy:begin
    private final MembershipService memberships;
    private final Tenants tenants;
    // tenancy:else
    // private final UserService users;
    // tenancy:end
    private final PersonAccessMapper mapper;
    private final Clock clock;

    public Set<Permission> effectivePermissions(UUID userId) {
        EffectiveAccess effective = accessOf(userId);
        if (AUTHORIZATION_LOG.isTraceEnabled()) {
            AUTHORIZATION_LOG.trace("Effective access: {}", effective.describe());
        }
        return effective.permissions();
    }

    public ObjectWrapper<PersonAccessResponse> access(UUID userId) {
        requirePerson(userId);
        return ObjectWrapper.withObject(view(userId));
    }

    @Transactional
    public ObjectWrapper<PersonAccessResponse> assignRole(UUID callerId, UUID userId, UUID roleId, RoleAssignmentRequest request) {
        EffectiveAccess caller = lockForChange(callerId, userId);
        AccessRole role = roles.getRole(roleId);
        requireHeld(caller, role.permissions());
        requireHeldForGoodWhenForGood(callerId, role.permissions(), request.validUntil());
        Optional<RoleAssignment> existing = assignments.findByUserIdAndRoleId(userId, roleId);
        if (existing.isPresent()) {
            existing.get().changeValidUntil(request.validUntil());
        } else {
            assignments.save(RoleAssignment.of(userId, role, request.validUntil()));
        }
        assignments.flush();
        requireWithinTheCaller(caller, userId);
        requireManagersRemain();
        log.info("Role given: userId={}, roleId={}, validUntil={}", userId, roleId, request.validUntil());
        return ObjectWrapper.withObject(view(userId));
    }

    @Transactional
    public void revokeRole(UUID callerId, UUID userId, UUID roleId) {
        lockForChange(callerId, userId);
        Optional<RoleAssignment> existing = assignments.findByUserIdAndRoleId(userId, roleId);
        if (existing.isEmpty()) {
            return;
        }
        assignments.delete(existing.get());
        assignments.flush();
        requireManagersRemain();
        log.info("Role taken: userId={}, roleId={}", userId, roleId);
    }

    @Transactional
    public ObjectWrapper<PersonAccessResponse> grant(UUID callerId, UUID userId, Permission permission, PersonalGrantRequest request) {
        EffectiveAccess caller = lockForChange(callerId, userId);
        requireHeld(caller, EnumSet.of(permission));
        if (request.effect() == GrantEffect.ALLOW) {
            requireHeldForGoodWhenForGood(callerId, EnumSet.of(permission), request.validUntil());
        }
        Optional<PersonalGrant> existing = grants.findByUserIdAndPermission(userId, permission.name());
        if (existing.isPresent()) {
            existing.get().change(request.effect(), request.validUntil());
        } else {
            grants.save(PersonalGrant.of(userId, permission.name(), request.effect(), request.validUntil()));
        }
        grants.flush();
        requireWithinTheCaller(caller, userId);
        requireManagersRemain();
        log.info("Personal grant set: userId={}, permission={}, effect={}, validUntil={}", userId, permission, request.effect(),
                request.validUntil());
        return ObjectWrapper.withObject(view(userId));
    }

    @Transactional
    public void removeGrant(UUID callerId, UUID userId, Permission permission) {
        EffectiveAccess caller = lockForChange(callerId, userId);
        requireHeld(caller, EnumSet.of(permission));
        Optional<PersonalGrant> existing = grants.findByUserIdAndPermission(userId, permission.name());
        if (existing.isEmpty()) {
            return;
        }
        grants.delete(existing.get());
        grants.flush();
        requireWithinTheCaller(caller, userId);
        requireManagersRemain();
        log.info("Personal grant removed: userId={}, permission={}", userId, permission);
    }

    // tenancy:begin
    // tenancy:else
    // @EventListener
    // @Transactional(propagation = Propagation.MANDATORY)
    // public void giveTheFirstRole(AccountCreated created) {
    //     giveBuiltIn(created.userId(), Role.USER);
    // }
    //
    // tenancy:end
    @Transactional(propagation = Propagation.MANDATORY)
    void giveBuiltIn(UUID userId, Role role) {
        AccessRole builtIn = roles.getBuiltIn(role);
        if (assignments.findByUserIdAndRoleId(userId, builtIn.getId()).isEmpty()) {
            assignments.save(RoleAssignment.of(userId, builtIn, null));
        }
    }
    // tenancy:begin

    @Transactional(propagation = Propagation.MANDATORY)
    void lockToAddMember(UUID callerId, Role given) {
        lockForAccessChange(List.of(callerId));
        EffectiveAccess caller = accessOf(callerId);
        if (!caller.holds(Permission.TENANT_MEMBERS_MANAGE)) {
            throw AppException.of(ErrorCode.ACCESS_DENIED);
        }
        // The new member holds it for good.
        requireHeldForGoodWhenForGood(callerId, roles.getBuiltIn(given).permissions(), null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void takeAllAway(UUID userId) {
        assignments.deleteAll(assignments.findByUserId(userId));
        grants.deleteAll(grants.findByUserIdOrderByPermission(userId));
        assignments.flush();
        log.info("Every role and grant taken: userId={}", userId);
    }

    /**
     * Its own transaction: a tenant's tables are read in a transaction that began in the tenant's scope. The
     * suspension's holds the tenant's row, which every access change there locks first, so nothing this counts can
     * change before the suspension commits.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    void requireManagersRemainWithout(UUID userId) {
        Set<UUID> others = new TreeSet<>(assignments.findHoldersForGood(names(KEPT_HELD)));
        // Someone who manages nothing here for good takes nothing away (a tenant left without a manager by hand keeps
        // its members suspendable).
        if (!others.remove(userId)) {
            return;
        }
        // A SUSPENDED tenant's managers count too: they manage it again once it is reactivated.
        requireHeldForGood(assignments.findAccessRowsWhateverTheTenantStatus(others, clock.instant()));
    }

    Optional<UUID> defaultToImpersonate() {
        List<UUID> candidates = assignments.findHoldersForGood(names(EnumSet.of(Permission.TENANT_MEMBERS_MANAGE)));
        Map<UUID, List<AccessRow>> rows = assignments.findAccessRows(candidates, clock.instant()).stream()
                .collect(Collectors.groupingBy(AccessRow::userId));
        List<UUID> managers = new ArrayList<>();
        for (Map.Entry<UUID, List<AccessRow>> person : rows.entrySet()) {
            if (EffectiveAccess.forGoodOf(person.getValue()).holds(Permission.TENANT_MEMBERS_MANAGE)) {
                managers.add(person.getKey());
            }
        }
        return memberships.oldestOf(managers);
    }
    // tenancy:end

    @Transactional(propagation = Propagation.MANDATORY)
    EffectiveAccess lockManagersWith(UUID callerId, Permission permission) {
        lockForAccessChange(List.of(callerId));
        EffectiveAccess caller = accessOf(callerId);
        if (!caller.holds(permission)) {
            throw AppException.of(ErrorCode.ACCESS_DENIED);
        }
        return caller;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void lockToManage(UUID callerId, UUID userId, Permission permission) {
        lockForAccessChange(List.of(callerId, userId));
        requireMayManage(callerId, userId, permission);
    }

    /** Only after the change is flushed: the holders are read through JDBC, past Hibernate's session. */
    @Transactional(propagation = Propagation.MANDATORY)
    void requireManagersRemain() {
        requireHeldForGoodByOneOf(assignments.findHoldersForGood(names(KEPT_HELD)));
    }

    private void requireHeldForGoodByOneOf(Collection<UUID> candidates) {
        requireHeldForGood(assignments.findAccessRows(candidates, clock.instant()));
    }

    private void requireHeldForGood(List<AccessRow> accessRows) {
        Map<UUID, List<AccessRow>> rows = accessRows.stream().collect(Collectors.groupingBy(AccessRow::userId));
        for (Permission kept : KEPT_HELD) {
            boolean held = rows.values().stream().anyMatch(personRows -> EffectiveAccess.forGoodOf(personRows).holds(kept));
            if (!held) {
                throw AppException.of(ErrorCode.ACCESS_LAST_MANAGER, kept);
            }
        }
    }

    private EffectiveAccess lockForChange(UUID callerId, UUID userId) {
        if (userId.equals(callerId)) {
            throw AppException.of(ErrorCode.ACCESS_OWN_CHANGE);
        }
        lockForAccessChange(List.of(callerId, userId));
        return requireMayManage(callerId, userId, Permission.ACCESS_GRANT);
    }

    /** Reads the caller again under the lock, whatever their request read a moment before. */
    private EffectiveAccess requireMayManage(UUID callerId, UUID userId, Permission permission) {
        // The caller first: one without the permission learns nothing about who is a person here.
        EffectiveAccess caller = accessOf(callerId);
        if (!caller.holds(permission)) {
            throw AppException.of(ErrorCode.ACCESS_DENIED);
        }
        requirePerson(userId);
        // Nobody manages someone above them, which a DENY could otherwise sabotage.
        Set<Permission> above = EnumSet.noneOf(Permission.class);
        above.addAll(grantedTo(userId).permissions());
        above.removeAll(caller.permissions());
        if (!above.isEmpty()) {
            throw AppException.of(ErrorCode.ACCESS_TARGET_HOLDS_MORE);
        }
        return caller;
    }

    /**
     * On the flushed result, so taking a DENY away never hands back a permission that needed the denied one and that
     * the caller lacks.
     */
    private void requireWithinTheCaller(EffectiveAccess caller, UUID userId) {
        requireHeld(caller, grantedTo(userId).permissions());
    }

    /**
     * Taken first by every change of someone's access and held until its transaction ends, so any two changes that
     * could each take the last holder away queue, the second counting after the first.
     *   <!-- tenancy:begin -->
     * The tenant's row: one row every access change in the tenant locks first, so the holders a change reads after it
     * are those it commits against ({@code userIds}, the people it changes, need no lock of their own).
     *   <!-- tenancy:else -->
     <!-- * The rows of {@code userIds} and of every account holding {@link #KEPT_HELD} for good, in id order. The holders -->
     <!-- * are read before their lock, so they are read again once it is taken, and any new one locked too, until the -->
     <!-- * set stays the same: a holder who appeared in between (every holder replaced meanwhile) would otherwise leave -->
     <!-- * two changes locking disjoint sets. -->
     *   <!-- tenancy:end -->
     */
    void lockForAccessChange(Collection<UUID> userIds) {
        // tenancy:begin
        tenants.lockForMemberChange(TenantContext.id());
        // tenancy:else
        // Set<UUID> locked = new TreeSet<>();
        // Set<UUID> wanted = withTheHoldersForGood(userIds);
        // while (!locked.containsAll(wanted)) {
        //     locked.addAll(wanted);
        //     users.lockForAccessChange(locked);
        //     wanted = withTheHoldersForGood(userIds);
        // }
        // tenancy:end
    }
    // tenancy:begin
    // tenancy:else
    //
    // private Set<UUID> withTheHoldersForGood(Collection<UUID> userIds) {
    //     Set<UUID> people = new TreeSet<>(assignments.findHoldersForGood(names(KEPT_HELD)));
    //     people.addAll(userIds);
    //     return people;
    // }
    // tenancy:end

    /** A suspended person still counts as above. */
    private EffectiveAccess grantedTo(UUID userId) {
        return EffectiveAccess.of(assignments.findAccessRowsWhateverTheAccountStatus(List.of(userId), clock.instant()));
    }

    /** A grant of the caller's that ends never lives on, for good, in someone else's. */
    private void requireHeldForGoodWhenForGood(UUID callerId, Set<Permission> given, Instant validUntil) {
        if (Objects.isNull(validUntil)) {
            requireHeld(EffectiveAccess.forGoodOf(assignments.findAccessRows(List.of(callerId), clock.instant())), given);
        }
    }

    boolean holdsRole(UUID userId, UUID roleId) {
        return assignments.findByUserIdAndRoleId(userId, roleId).isPresent();
    }

    static void requireHeld(EffectiveAccess caller, Set<Permission> given) {
        Set<Permission> notHeld = EnumSet.noneOf(Permission.class);
        notHeld.addAll(given);
        notHeld.removeAll(caller.permissions());
        if (!notHeld.isEmpty()) {
            throw AppException.of(ErrorCode.ACCESS_NOT_HELD, notHeld);
        }
    }

    EffectiveAccess accessOf(UUID userId) {
        return EffectiveAccess.of(assignments.findAccessRows(List.of(userId), clock.instant()));
    }

    private PersonAccessResponse view(UUID userId) {
        return mapper.toResponse(userId, assignments.findByUserId(userId), grants.findByUserIdOrderByPermission(userId),
                accessOf(userId));
    }

    private void requirePerson(UUID userId) {
        // tenancy:begin
        memberships.requireMember(userId);
        // tenancy:else
        // users.requireUser(userId);
        // tenancy:end
    }

    private static List<String> names(Collection<Permission> permissions) {
        List<String> names = new ArrayList<>();
        for (Permission permission : permissions) {
            names.add(permission.name());
        }
        return names;
    }
}
