package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
// tenancy:begin
// roles:begin
// import com.example.app.common.tenancy.TenantScope;
// roles:end
// tenancy:else
import com.example.app.common.security.Permission;
// tenancy:end
import com.example.app.common.wrapper.BoundedListWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
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

// roles:begin
// tenancy:begin
// import java.util.List;
// tenancy:end
// roles:else
import java.util.List;
// roles:end
import java.util.UUID;

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
    // roles:begin
    // private final PersonAccessService access;
    // roles:end

    public BoundedListWrapper<UserResponse> lookUp(UserFilter filter) {
        return BoundedListWrapper.fromList(users.findByEmail(filter.getEmail()).map(mapper::toResponse).stream().toList());
    }

    // tenancy:begin
    // roles:begin
    // /**
    //  * Each open tenant of the person's is checked under its own scope, in a short transaction of its own, while this
    //  * one holds the tenant's row, which every access change there locks first.
    //  */
    // roles:end
    // @Transactional
    // public ObjectWrapper<UserResponse> changeStatus(UUID userId, UserStatusRequest request) {
    //     User target = users.lockForStatusChange(List.of(userId)).stream().findFirst()
    //             .orElseThrow(AppException.supply(ErrorCode.USER_NOT_FOUND));
    //     target.checkVersion(request.version());
    //     if (request.status() == UserStatus.SUSPENDED && target.isActive()) {
    //         // roles:begin
    //         for (UUID tenantId : memberships.lockOpenTenantsOf(userId)) {
    //             TenantScope.runAs(tenantId, () -> access.requireManagersRemainWithout(userId));
    //         }
    //         // roles:else
    //         memberships.requireAnotherAdminWhereAdmin(userId);
    //         // roles:end
    //     }
    //     return ObjectWrapper.withObject(apply(target, request.status()));
    // }
    // tenancy:else
    @Transactional
    public ObjectWrapper<UserResponse> changeStatus(UUID callerId, UUID userId, UserStatusRequest request) {
        if (userId.equals(callerId)) {
            throw AppException.of(ErrorCode.USER_OWN_STATUS_CHANGE);
        }
        // roles:begin
        // access.lockToManage(callerId, userId, Permission.ACCOUNT_STATUS_CHANGE);
        // User target = users.getUser(userId);
        // target.checkVersion(request.version());
        // UserResponse changed = apply(target, request.status());
        // access.requireManagersRemain();
        // return ObjectWrapper.withObject(changed);
        // roles:else
        // The caller's row read under the lock, whatever their token says: of two ADMINs suspending each other at
        // once, the second finds itself suspended.
        List<User> locked = users.lockForStatusChange(List.of(callerId, userId));
        boolean callerMayChangeStatus = locked.stream()
                .anyMatch(user -> user.getId().equals(callerId) && user.isActive()
                        && user.getRole().grants(Permission.ACCOUNT_STATUS_CHANGE));
        if (!callerMayChangeStatus) {
            throw AppException.of(ErrorCode.ACCESS_DENIED);
        }
        User target = locked.stream().filter(user -> user.getId().equals(userId)).findFirst()
                .orElseThrow(AppException.supply(ErrorCode.USER_NOT_FOUND));
        target.checkVersion(request.version());
        return ObjectWrapper.withObject(apply(target, request.status()));
        // roles:end
    }
    // tenancy:end

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
