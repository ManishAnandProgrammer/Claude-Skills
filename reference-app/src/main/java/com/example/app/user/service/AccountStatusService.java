package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.wrapper.ListWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
// tenancy:begin
// tenancy:else
import com.example.app.user.domain.Role;
// tenancy:end
import com.example.app.user.domain.User;
import com.example.app.user.domain.UserStatus;
import com.example.app.user.dto.request.UserFilter;
import com.example.app.user.dto.request.UserStatusRequest;
import com.example.app.user.dto.response.UserResponse;
import com.example.app.user.mapper.UserMapper;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Finds, suspends and reactivates accounts (the sign-in piece). A suspension, in one transaction: the account's row
 * updated first (so a token issue racing it waits, then refuses: {@code UserService.activeForTokenIssue}),
 * and every refresh token revoked.
 *   <!-- session-version:begin -->
 * The session version is raised too, so the access tokens already out are refused on their next request.
 *   <!-- session-version:else -->
 <!-- * The access tokens already out expire within their lifetime. -->
 *   <!-- session-version:end -->
 * A suspended account gets no token from any path. A reactivation lets it sign in again; it revives no session.
 *   <!-- tenancy:begin -->
 *
 * <p>With tenancy, accounts are global and platform staff suspend them ({@code PATCH /platform/users/{id}/status}):
 * a tenant ADMIN changes memberships, never an account. A person who is the last ADMIN with an ACTIVE account
 * of a tenant isn't suspended (409 {@code TENANT_MEMBER_LAST_ADMIN}): make another member ADMIN first, or
 * suspend the tenant.</p>
 *   <!-- tenancy:else -->
 *
 * <p>An application ADMIN does it ({@code PATCH /users/{id}/status}), never to their own account, and only
 * while they are an ACTIVE ADMIN themselves, read under the lock that serialises these changes, whatever
 * their token says. So the last ADMIN can never be suspended: only another active ADMIN can suspend one.</p>
 *   <!-- tenancy:end -->
 */
@Service
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Transactional(readOnly = true)
public class AccountStatusService {

    private final UserService users;
    private final UserMapper mapper;
    private final TokenService tokens;
    // tenancy:begin
    // private final MembershipService memberships;
    // tenancy:end

    /** The account with this address, or none: the operator's read before a change, which needs its id and version. */
    public ListWrapper<UserResponse> lookUp(UserFilter filter) {
        return ListWrapper.fromList(users.findByEmail(filter.getEmail()).map(mapper::toResponse).stream().toList());
    }

    // tenancy:begin
    // /** Platform staff's change. 404 {@code USER_NOT_FOUND}, 409 {@code OPTIMISTIC_LOCK_CONFLICT}, 409 {@code TENANT_MEMBER_LAST_ADMIN}. */
    // @Transactional
    // public ObjectWrapper<UserResponse> changeStatus(UUID userId, UserStatusRequest request) {
    //     User target = users.lockForStatusChange(List.of(userId)).stream().findFirst()
    //             .orElseThrow(AppException.supply(ErrorCode.USER_NOT_FOUND));
    //     target.checkVersion(request.version());
    //     if (request.status() == UserStatus.SUSPENDED && target.isActive()) {
    //         memberships.requireAnotherAdminWhereAdmin(userId);
    //     }
    //     return ObjectWrapper.withObject(apply(target, request.status()));
    // }
    // tenancy:else
    /**
     * An ADMIN's change of another account: 403 {@code USER_OWN_STATUS_CHANGE} for the caller's own; the
     * caller and the target locked together (id order), then 403 {@code ACCESS_DENIED} unless the caller is
     * an ACTIVE ADMIN now (of two ADMINs suspending each other at once, the second finds itself suspended),
     * 404 {@code USER_NOT_FOUND}, 409 {@code OPTIMISTIC_LOCK_CONFLICT}.
     */
    @Transactional
    public ObjectWrapper<UserResponse> changeStatus(UUID callerId, UUID userId, UserStatusRequest request) {
        if (userId.equals(callerId)) {
            throw AppException.of(ErrorCode.USER_OWN_STATUS_CHANGE);
        }
        List<User> locked = users.lockForStatusChange(List.of(callerId, userId));
        boolean callerIsActiveAdmin = locked.stream()
                .anyMatch(user -> user.getId().equals(callerId) && user.isActive() && user.getRole() == Role.ADMIN);
        if (!callerIsActiveAdmin) {
            throw AppException.of(ErrorCode.ACCESS_DENIED);
        }
        User target = locked.stream().filter(user -> user.getId().equals(userId)).findFirst()
                .orElseThrow(AppException.supply(ErrorCode.USER_NOT_FOUND));
        target.checkVersion(request.version());
        return ObjectWrapper.withObject(apply(target, request.status()));
    }
    // tenancy:end

    /** The current status again changes nothing (no version move, no session ended). */
    private UserResponse apply(User target, UserStatus status) {
        if (target.getStatus() == status) {
            return mapper.toResponse(target);
        }
        UserResponse changed = users.changeStatus(target, status);
        if (status != UserStatus.ACTIVE) {
            int revoked = tokens.revokeForSuspension(target.getId());
            // session-version:begin
            // tenancy:begin
            // memberships.bumpSessionVersionsOfUser(target.getId());
            // tenancy:else
            users.bumpSessionVersion(target.getId());
            // tenancy:end
            // session-version:end
            log.info("Account suspended, every session ended: userId={}, sessions={}", target.getId(), revoked);
        } else {
            log.info("Account reactivated: userId={}", target.getId());
        }
        return changed;
    }
}
