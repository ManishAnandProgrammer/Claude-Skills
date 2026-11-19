package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.Permission;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.domain.AccessRole;
import com.example.app.user.domain.EffectiveAccess;
import com.example.app.user.domain.PersonalGrant;
import com.example.app.user.domain.Role;
import com.example.app.user.domain.RoleAssignment;
import com.example.app.user.domain.AccessRow;
import com.example.app.user.dto.internal.AccountCreated;
import com.example.app.user.dto.request.PersonalGrantRequest;
import com.example.app.user.dto.request.RoleAssignmentRequest;
import com.example.app.user.dto.response.PersonAccessResponse;
import com.example.app.user.mapper.PersonAccessMapper;
import com.example.app.user.repository.PersonalGrantRepository;
import com.example.app.user.repository.RoleAssignmentRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A person's access (the roles piece): their role assignments and personal grants, one aggregate written only here,
 * and what they give ({@link EffectiveAccess}), read in one statement on every authenticated request.
 *
 * <p>A change of someone's access, in one transaction: the caller's and the person's rows locked together, in id
 * order (two admins changing one person queue; a second change finds the first one's result), then the safety rules
 * on what the database holds now, whatever the caller's request read a moment before:</p>
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
 * <p>Safety rule 7, the lock: the account rows (the narrowest every such change shares, DATA-29) of the caller, the
 * person and every account that holds {@link #KEPT_HELD} for good, in id order, never the role: a role's edit takes
 * the same set ({@link #lockManagersWith}), so any two changes that could each take the last holder away queue.</p>
 *   <!-- tenancy:begin -->
 * <p>With tenancy the person is a member of the caller's tenant: the rows locked are the memberships, the rules
 * count the tenant's members, and the roles are the tenant's.</p>
 *   <!-- tenancy:end -->
 */
@Service
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Transactional(readOnly = true)
public class PersonAccessService {

    /** What must stay held for good by an active account: without either, nobody could ever change access again. */
    static final Set<Permission> KEPT_HELD = EnumSet.of(Permission.ROLES_MANAGE, Permission.ACCESS_GRANT);

    private final RoleAssignmentRepository assignments;
    private final PersonalGrantRepository grants;
    private final AccessRoleService roles;
    // tenancy:begin
    private final MembershipService memberships;
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

    /** A new account's first role, the built-in {@code USER}, in the transaction that creates it. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void giveTheFirstRole(AccountCreated created) {
        assignBuiltIn(created.userId(), Role.USER);
    }

    /** A built-in role for good, in the listener's transaction (a new account's). */
    private void assignBuiltIn(UUID userId, Role role) {
        assignments.save(RoleAssignment.of(userId, roles.getBuiltIn(role), null));
    }

    /**
     * For a change that may take access from many people (a role's edit): the caller's row and those of every
     * account that holds {@link #KEPT_HELD} for good, locked in id order; then 403 {@code ACCESS_DENIED} unless the
     * caller holds {@code permission} now. Answers what the caller holds.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    EffectiveAccess lockManagersWith(UUID callerId, Permission permission) {
        lockWithTheHoldersForGood(List.of(callerId));
        EffectiveAccess caller = accessOf(callerId);
        if (!caller.holds(permission)) {
            throw AppException.of(ErrorCode.ACCESS_DENIED);
        }
        return caller;
    }

    /**
     * For a change of one person by another (a suspension): their rows locked together, then 403
     * {@code ACCESS_DENIED} unless the caller holds {@code permission} now, and 403 {@code ACCESS_TARGET_HOLDS_MORE}
     * when the person holds something the caller doesn't.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void lockToManage(UUID callerId, UUID userId, Permission permission) {
        lockWithTheHoldersForGood(List.of(callerId, userId));
        requireMayManage(callerId, userId, permission);
    }

    /**
     * Safety rule 6, after the change is flushed: 409 {@code ACCESS_LAST_MANAGER} unless an active account still holds
     * each of {@link #KEPT_HELD} through a grant without an end (an expiring one never counts as the last).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void requireManagersRemain() {
        List<UUID> candidates = assignments.findHoldersForGood(names(KEPT_HELD));
        Map<UUID, List<AccessRow>> rows = assignments.findAccessRows(candidates, clock.instant()).stream()
                .collect(Collectors.groupingBy(AccessRow::userId));
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
        lockWithTheHoldersForGood(List.of(callerId, userId));
        return requireMayManage(callerId, userId, Permission.ACCESS_GRANT);
    }

    /** Both people's access in one statement; the person must exist (404). */
    private EffectiveAccess requireMayManage(UUID callerId, UUID userId, Permission permission) {
        requirePerson(userId);
        EffectiveAccess caller = accessOf(callerId);
        if (!caller.holds(permission)) {
            throw AppException.of(ErrorCode.ACCESS_DENIED);
        }
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
     * The rows of {@code userIds} and of every account holding {@link #KEPT_HELD} for good, locked in id order: any two
     * changes that could each take the last holder away share a locked row, so the second counts after the first.
     */
    private void lockWithTheHoldersForGood(Collection<UUID> userIds) {
        Set<UUID> locked = new TreeSet<>(assignments.findHoldersForGood(names(KEPT_HELD)));
        locked.addAll(userIds);
        lockPeople(locked);
    }

    /** What {@code userId} is given, whatever their account's status (a suspended person still counts as above). */
    private EffectiveAccess grantedTo(UUID userId) {
        return EffectiveAccess.of(assignments.findGrantedRows(List.of(userId), clock.instant()));
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

    /** The people's rows, locked in id order (the set is sorted) until the transaction ends. */
    private void lockPeople(Set<UUID> userIds) {
        // tenancy:begin
        memberships.lockForAccessChange(userIds);
        // tenancy:else
        // users.lockForAccessChange(userIds);
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
