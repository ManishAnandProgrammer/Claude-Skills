package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.Permission;
import com.example.app.common.validation.Emails;
import com.example.app.common.wrapper.ObjectWrapper;
// tenancy:begin
// tenancy:else
// roles:begin
// roles:else
import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.user.api.PermissionHolders;
// roles:end
import com.example.app.user.api.Users;
// tenancy:end
// roles:begin
// roles:else
// tenancy:begin
// tenancy:else
import com.example.app.user.domain.Role;
// tenancy:end
// roles:end
import com.example.app.user.domain.User;
import com.example.app.user.domain.UserStatus;
// roles:begin
// tenancy:begin
// tenancy:else
// import com.example.app.user.dto.internal.AccountCreated;
// tenancy:end
// roles:end
import com.example.app.user.dto.request.SignupRequest;
import com.example.app.user.dto.response.AccessResponse;
import com.example.app.user.dto.response.UserResponse;
import com.example.app.user.mapper.UserMapper;
import com.example.app.user.repository.UserRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
// session-version:begin
// tenancy:begin
// tenancy:else
import org.springframework.context.ApplicationEventPublisher;
// tenancy:end
// session-version:end
// roles:begin
// tenancy:begin
// tenancy:else
// import org.springframework.context.ApplicationEventPublisher;
// tenancy:end
// roles:end
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
// roles:begin
// tenancy:begin
// tenancy:else
// import org.springframework.transaction.support.TransactionTemplate;
// tenancy:end
// roles:end

// session-version:begin
// tenancy:begin
// tenancy:else
import java.util.Map;
// tenancy:end
// session-version:end
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Transactional(readOnly = true)
// tenancy:begin
// public class UserService {
// tenancy:else
// roles:begin
// public class UserService implements Users {
// roles:else
public class UserService implements Users, PermissionHolders {
// roles:end
// tenancy:end

    private final UserRepository repository;
    private final UserMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthRateLimits rateLimits;
    private final BreachedPasswordCheck breachedPasswordCheck;
    // session-version:begin
    // tenancy:begin
    // tenancy:else
    private final ApplicationEventPublisher events;
    // tenancy:end
    // session-version:end
    // roles:begin
    // tenancy:begin
    // tenancy:else
    // private final TransactionTemplate transactions;
    // private final ApplicationEventPublisher accountEvents;
    // tenancy:end
    // roles:end

    /**
     * Without a transaction: hashing the password takes about a quarter of a second of CPU and the breached-password
     * check is a remote call; neither may hold a pooled connection meanwhile. A concurrent signup with the same email
     * that slips past the existence check is refused by {@code uq_users_email} ({@code UserConstraintErrorCodes}).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ObjectWrapper<UserResponse> signup(SignupRequest request, String clientIp) {
        rateLimits.signup(clientIp);
        String email = Emails.normalize(request.email());
        if (repository.existsByEmail(email)) {
            throw AppException.of(ErrorCode.USER_EMAIL_TAKEN);
        }
        breachedPasswordCheck.requireNotBreached(request.password());
        User user = User.register(email, request.fullName(), passwordEncoder.encode(request.password()));
        // roles:begin
        // tenancy:begin
        // User saved = repository.saveAndFlush(user);
        // tenancy:else
        // User saved = transactions.execute(status -> created(user));
        // tenancy:end
        // roles:else
        User saved = repository.saveAndFlush(user);
        // roles:end
        log.info("Account created: userId={}", saved.getId());
        return ObjectWrapper.withObject(mapper.toResponse(saved));
    }

    // roles:begin
    // tenancy:begin
    // tenancy:else
    // private User created(User user) {
    //     User saved = repository.saveAndFlush(user);
    //     accountEvents.publishEvent(new AccountCreated(saved.getId()));
    //     return saved;
    // }
    //
    // tenancy:end
    // roles:end
    public ObjectWrapper<UserResponse> me(UUID userId) {
        return ObjectWrapper.withObject(mapper.toResponse(getUser(userId)));
    }

    public ObjectWrapper<AccessResponse> access(Set<Permission> held) {
        return ObjectWrapper.withObject(mapper.toAccessResponse(held));
    }

    // tenancy:begin
    // tenancy:else
    @Override
    public void requireUser(UUID userId) {
        if (!repository.existsById(userId)) {
            throw AppException.of(ErrorCode.USER_NOT_FOUND);
        }
    }
    // roles:begin
    // roles:else

    @Override
    public boolean holds(UUID userId, Permission permission) {
        return repository.findById(userId).filter(User::isActive).map(user -> user.getRole().grants(permission)).orElse(false);
    }

    @Override
    public CursorBatch<DropdownItem> findHolders(Permission permission, DropdownFilter filter, int size) {
        return repository.findActiveWithRoleIn(Role.holding(permission), filter, size);
    }
    // roles:end

    // tenancy:end
    Optional<User> findByEmail(String email) {
        return repository.findByEmail(Emails.normalize(email));
    }

    User getUser(UUID userId) {
        return repository.findById(userId).orElseThrow(AppException.supply(ErrorCode.USER_NOT_FOUND));
    }

    /** A refusal leaves the caller's transaction committable: a refresh commits its own writes even when it refuses. */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = AppException.class)
    User activeForTokenIssue(UUID userId) {
        User user = repository.findForTokenIssue(userId).orElseThrow(AppException.supply(ErrorCode.USER_NOT_FOUND));
        if (!user.isActive()) {
            log.info("Token refused, the account is not active: userId={}, status={}", userId, user.getStatus());
            throw AppException.of(ErrorCode.AUTH_ACCOUNT_SUSPENDED);
        }
        return user;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void changePassword(UUID userId, String newPasswordHash) {
        User user = getUser(userId);
        user.changePassword(newPasswordHash);
        repository.saveAndFlush(user);
    }

    // roles:begin
    // tenancy:begin
    // @Transactional(propagation = Propagation.MANDATORY)
    // List<User> lockForStatusChange(Collection<UUID> userIds) {
    //     return repository.findAllForUpdate(userIds);
    // }
    // tenancy:end
    // roles:else
    @Transactional(propagation = Propagation.MANDATORY)
    List<User> lockForStatusChange(Collection<UUID> userIds) {
        return repository.findAllForUpdate(userIds);
    }
    // roles:end

    // roles:begin
    // tenancy:begin
    // tenancy:else
    // @Transactional(propagation = Propagation.MANDATORY)
    // void lockForAccessChange(Collection<UUID> userIds) {
    //     repository.findAllForUpdate(userIds);
    // }
    //
    // tenancy:end
    // roles:end
    @Transactional(propagation = Propagation.MANDATORY)
    UserResponse changeStatus(User user, UserStatus status) {
        user.changeStatus(status);
        return mapper.toResponse(repository.saveAndFlush(user));
    }
    // session-version:begin
    // tenancy:begin
    // tenancy:else

    Optional<Integer> findSessionVersion(UUID userId) {
        return repository.findSessionVersion(userId);
    }

    /** In the access change's own transaction (hence MANDATORY): in two, the change could commit without the bump. */
    @Transactional(propagation = Propagation.MANDATORY)
    void bumpSessionVersion(UUID userId) {
        repository.bumpSessionVersion(userId);
        int raised = repository.findSessionVersion(userId).orElseThrow(AppException.supply(ErrorCode.USER_NOT_FOUND));
        events.publishEvent(new SessionVersionsRaised(Map.of(userId, raised)));
        log.info("Session version raised: userId={}, sv={}", userId, raised);
    }
    // tenancy:end
    // session-version:end
}
