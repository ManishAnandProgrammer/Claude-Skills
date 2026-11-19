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

/**
 * A person's access (the roles piece): their role assignments and personal grants, one aggregate written only here,
 * and what they give ({@link EffectiveAccess}), read in one statement on every authenticated request.
 *
 * <p>A change of someone's access, in one transaction: locked first ({@link #lockForAccessChange}: two admins changing
 * one person queue; a second change finds the first one's result), then the safety rules on what the database holds
 * now, whatever the caller's request read a moment before:</p>
 * <ul>
 *   <li>the caller holds {@code ACCESS_GRANT} (403 {@code ACCESS_DENIED});</li>
 *   <li>safety rule 2: never one's own access (403 {@code ACCESS_OWN_CHANGE});</li>
 *   <li>safety rule 3: the person holds nothing the caller doesn't, a suspended person's grants counted (403
 *       {@code ACCESS_TARGET_HOLDS_MORE}): nobody manages someone above them, which a DENY could otherwise sabotage;</li>
 *   <li>safety rule 1: a role or a grant gives only what the caller holds, and what the person would hold afterwards
 *       too (403 {@code ACCESS_NOT_HELD});</li>
 *   <li>safety rule 6: afterwards an active account still holds each of {@link #KEPT_HELD} for good (409
 *       {@code ACCESS_LAST_MANAGER}).</li>
 * </ul>
 *   <!-- tenancy:begin -->
 * <p>Safety rule 7, the lock: the tenant's row (DATA-29: the one row every access change in the tenant shares), so
 * any two changes that could each take the last holder away queue, and the holders a change counts can't change
 * before it commits. The person is a member of the caller's tenant, the rules count the tenant's members, the roles
 * are the tenant's, and with tenancy {@link #KEPT_HELD} holds {@code TENANT_MEMBERS_MANAGE} too (the tenant's last
 * ADMIN of Level 1).</p>
 *   <!-- tenancy:else -->
 <!-- * <p>Safety rule 7, the lock: the account rows (the narrowest every such change shares, DATA-29) of the caller, the -->
 <!-- * person and every account that holds {@link #KEPT_HELD} for good, in id order, never the role: a role's edit takes -->
 <!-- * the same set ({@link #lockManagersWith}), so any two changes that could each take the last holder away queue. The -->
 <!-- * holders are read again once locked, until no new one appears, so a holder who arrived meanwhile is locked too.</p> -->
 *   <!-- tenancy:end -->
 */
@Service
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Transactional(readOnly = true)
public class PersonAccessService {

    // tenancy:begin
    /**
     * What must stay held for good by an active member of the tenant: without one of them, nobody could ever change
     * access, or its members, again (safety rule 1 forbids giving what you don't hold).
     */
    static final Set<Permission> KEPT_HELD = EnumSet.of(Permission.ROLES_MANAGE, Permission.ACCESS_GRANT,
            Permission.TENANT_MEMBERS_MANAGE);
    // tenancy:else
    // /** What must stay held for good by an active account: without either, nobody could ever change access again. */
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

    /** What {@code userId} holds now, in one statement: the security chain's read on every request. */
    public Set<Permission> effectivePermissions(UUID userId) {
        return accessOf(userId).permissions();
    }

    /** A person's roles, grants and what they give, with where each permission comes from. 404 for nobody. */
    public ObjectWrapper<PersonAccessResponse> access(UUID userId) {
        requirePerson(userId);
        return ObjectWrapper.withObject(view(userId));
    }

    /**
     * Gives {@code userId} the role (or changes its end): the role's every permission is one the caller holds.
     * 404 {@code ROLE_NOT_FOUND}; a role deleted meanwhile, 404 {@code ROLE_GONE} at the flush.
     */
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

    /** Takes the role away; one the person doesn't hold changes nothing. */
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

    /** Gives or takes one permission whatever the person's roles give (or changes the grant): only one the caller holds. */
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

    /** Removes the person's grant of one permission the caller holds; none changes nothing. */
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
    // /** A new account's first role, the built-in {@code USER}, in the transaction that creates it. */
    // @EventListener
    // @Transactional(propagation = Propagation.MANDATORY)
    // public void giveTheFirstRole(AccountCreated created) {
    //     giveBuiltIn(created.userId(), Role.USER);
    // }
    //
    // tenancy:end
    /**
     * A built-in role for good, in the caller's transaction (a new account's; with tenancy a new member's, a new
     * tenant's owner): none if the person holds it already, so a call made again adds nothing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void giveBuiltIn(UUID userId, Role role) {
        AccessRole builtIn = roles.getBuiltIn(role);
        if (assignments.findByUserIdAndRoleId(userId, builtIn.getId()).isEmpty()) {
            assignments.save(RoleAssignment.of(userId, builtIn, null));
        }
    }
    // tenancy:begin

    /**
     * Before a member's add ({@code TenantMemberManager}): the tenant locked, then 403 {@code ACCESS_DENIED} unless the
     * caller holds {@code TENANT_MEMBERS_MANAGE} now, and 403 {@code ACCESS_NOT_HELD} unless they hold all of
     * {@code given}'s permissions in the tenant, the role the new member gets (safety rule 1).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void lockToAddMember(UUID callerId, Role given) {
        lockForAccessChange(List.of(callerId));
        EffectiveAccess caller = accessOf(callerId);
        if (!caller.holds(Permission.TENANT_MEMBERS_MANAGE)) {
            throw AppException.of(ErrorCode.ACCESS_DENIED);
        }
        // The new member holds it for good: only of what the caller holds for good (a grant of theirs that ends never
        // lives on in someone else's).
        requireHeldForGoodWhenForGood(callerId, roles.getBuiltIn(given).permissions(), null);
    }

    /** A removed member's roles and grants in the tenant, all of them (added again, they start afresh). */
    @Transactional(propagation = Propagation.MANDATORY)
    void takeAllAway(UUID userId) {
        assignments.deleteAll(assignments.findByUserId(userId));
        grants.deleteAll(grants.findByUserIdOrderByPermission(userId));
        assignments.flush();
        log.info("Every role and grant taken: userId={}", userId);
    }

    /**
     * Safety rule 6 for a suspension of {@code userId}'s account (platform staff), in the current tenant: 409
     * {@code ACCESS_LAST_MANAGER} unless another active member holds each of {@link #KEPT_HELD} for good. Its own
     * short read-only transaction under the tenant's scope (a tenant's tables are read in a transaction that began in
     * it), while the suspension's holds the tenant's row, which every access change there locks first: nothing this
     * counts can change before the suspension commits.
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

    /**
     * The member staff act as when they name none: of the tenant's active members who manage its members for good
     * (safety rule 6 keeps one), the one with the oldest membership, normally its owner.
     */
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

    /**
     * For a change that may take access from many people (a role's edit): locked ({@link #lockForAccessChange}), then 403
     * {@code ACCESS_DENIED} unless the caller holds {@code permission} now. Answers what the caller holds.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    EffectiveAccess lockManagersWith(UUID callerId, Permission permission) {
        lockForAccessChange(List.of(callerId));
        EffectiveAccess caller = accessOf(callerId);
        if (!caller.holds(permission)) {
            throw AppException.of(ErrorCode.ACCESS_DENIED);
        }
        return caller;
    }

    /**
     * For a change of one person by another (a suspension; with tenancy a member's removal): locked
     * ({@link #lockForAccessChange}), then 403
     * {@code ACCESS_DENIED} unless the caller holds {@code permission} now, and 403 {@code ACCESS_TARGET_HOLDS_MORE}
     * when the person holds something the caller doesn't.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void lockToManage(UUID callerId, UUID userId, Permission permission) {
        lockForAccessChange(List.of(callerId, userId));
        requireMayManage(callerId, userId, permission);
    }

    /**
     * Safety rule 6, after the change is flushed: 409 {@code ACCESS_LAST_MANAGER} unless an active account still holds
     * each of {@link #KEPT_HELD} through a grant without an end (an expiring one never counts as the last).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void requireManagersRemain() {
        requireHeldForGoodByOneOf(assignments.findHoldersForGood(names(KEPT_HELD)));
    }

    /** 409 {@code ACCESS_LAST_MANAGER} unless one of {@code candidates}, active, holds each of {@link #KEPT_HELD} for good. */
    private void requireHeldForGoodByOneOf(Collection<UUID> candidates) {
        requireHeldForGood(assignments.findAccessRows(candidates, clock.instant()));
    }

    /** 409 {@code ACCESS_LAST_MANAGER} unless the people of {@code accessRows} hold each of {@link #KEPT_HELD} for good. */
    private void requireHeldForGood(List<AccessRow> accessRows) {
        Map<UUID, List<AccessRow>> rows = accessRows.stream().collect(Collectors.groupingBy(AccessRow::userId));
        for (Permission kept : KEPT_HELD) {
            boolean held = rows.values().stream().anyMatch(personRows -> EffectiveAccess.forGoodOf(personRows).holds(kept));
            if (!held) {
                throw AppException.of(ErrorCode.ACCESS_LAST_MANAGER, kept);
            }
        }
    }

    /** Rules 2, 3 and the caller's own permission, under the lock of both rows; what the caller holds. */
    private EffectiveAccess lockForChange(UUID callerId, UUID userId) {
        if (userId.equals(callerId)) {
            throw AppException.of(ErrorCode.ACCESS_OWN_CHANGE);
        }
        lockForAccessChange(List.of(callerId, userId));
        return requireMayManage(callerId, userId, Permission.ACCESS_GRANT);
    }

    /** Both people's access in one statement; the person must exist (404). */
    private EffectiveAccess requireMayManage(UUID callerId, UUID userId, Permission permission) {
        // The caller first: one without the permission learns nothing about who is a person here.
        EffectiveAccess caller = accessOf(callerId);
        if (!caller.holds(permission)) {
            throw AppException.of(ErrorCode.ACCESS_DENIED);
        }
        requirePerson(userId);
        Set<Permission> above = EnumSet.noneOf(Permission.class);
        above.addAll(grantedTo(userId).permissions());
        above.removeAll(caller.permissions());
        if (!above.isEmpty()) {
            throw AppException.of(ErrorCode.ACCESS_TARGET_HOLDS_MORE);
        }
        return caller;
    }

    /**
     * Safety rule 1 on the result, after the change is flushed: what the person would hold now (their status aside)
     * holds nothing the caller doesn't, so taking a DENY away never hands back a permission that needed the denied
     * one and that the caller lacks. 403 {@code ACCESS_NOT_HELD}, naming the difference.
     */
    private void requireWithinTheCaller(EffectiveAccess caller, UUID userId) {
        requireHeld(caller, grantedTo(userId).permissions());
    }

    /**
     * Safety rule 7's lock, taken first by every change of someone's access, held until its transaction ends, so any
     * two changes that could each take the last holder away queue, the second counting after the first.
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
    // /** {@code userIds} and every account holding {@link #KEPT_HELD} for good now, in id order. */
    // private Set<UUID> withTheHoldersForGood(Collection<UUID> userIds) {
    //     Set<UUID> people = new TreeSet<>(assignments.findHoldersForGood(names(KEPT_HELD)));
    //     people.addAll(userIds);
    //     return people;
    // }
    // tenancy:end

    /** What {@code userId} is given, whatever their account's status (a suspended person still counts as above). */
    private EffectiveAccess grantedTo(UUID userId) {
        return EffectiveAccess.of(assignments.findGrantedRows(List.of(userId), clock.instant()));
    }

    /**
     * Safety rule 1 for a grant without an end: only of what the caller holds for good, so a grant of theirs that ends
     * never lives on, for good, in someone else's. 403 {@code ACCESS_NOT_HELD}.
     */
    private void requireHeldForGoodWhenForGood(UUID callerId, Set<Permission> given, Instant validUntil) {
        if (Objects.isNull(validUntil)) {
            requireHeld(EffectiveAccess.forGoodOf(assignments.findAccessRows(List.of(callerId), clock.instant())), given);
        }
    }

    /** Whether {@code userId} holds the role (ended or not), for a role's edit: safety rule 2. */
    boolean holdsRole(UUID userId, UUID roleId) {
        return assignments.findByUserIdAndRoleId(userId, roleId).isPresent();
    }

    /** Safety rule 1: 403 {@code ACCESS_NOT_HELD}, naming what the caller doesn't hold. */
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

    /** 404 for someone who isn't a person here. */
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
