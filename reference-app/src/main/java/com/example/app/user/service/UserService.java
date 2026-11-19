package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.Permission;
import com.example.app.common.validation.Emails;
import com.example.app.common.wrapper.ObjectWrapper;
// tenancy:begin
// tenancy:else
import com.example.app.user.api.Users;
// tenancy:end
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
public class UserService implements Users {
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
    // /** The account and its first role (the built-in USER, PersonAccessService) in one short transaction. */
    // private final TransactionTemplate transactions;
    // private final ApplicationEventPublisher accountEvents;
    // tenancy:end
    // roles:end

    /**
     * Runs without a transaction: hashing the password takes about a quarter of a second of CPU and
     * the breached-password check is a remote call; neither may hold a pooled connection meanwhile.
     * The existence check and the insert each run in their own short transaction; a concurrent
     * signup with the same email that slips between them is refused by {@code uq_users_email} with
     * the same {@code USER_EMAIL_TAKEN}.
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
    // /** The account, and its first role in the same transaction (AccountCreated's listener, PersonAccessService). */
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

    /** The caller's areas and permissions, from their token's (no database read): {@code GET /users/me/access}. */
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

    // tenancy:end
    Optional<User> findByEmail(String email) {
        return repository.findByEmail(Emails.normalize(email));
    }

    /** 404 {@code USER_NOT_FOUND}. */
    User getUser(UUID userId) {
        return repository.findById(userId).orElseThrow(AppException.supply(ErrorCode.USER_NOT_FOUND));
    }

    /**
     * The account a token is about to be issued for, share-locked until the issue commits
     * ({@code UserRepository.findForTokenIssue}): 403 {@code AUTH_ACCOUNT_SUSPENDED} unless it is ACTIVE.
     * Every path that issues a token calls it before anything else touches the account's rows. A refusal
     * leaves the caller's transaction committable: a refresh commits its own writes even when it refuses.
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = AppException.class)
    User activeForTokenIssue(UUID userId) {
        User user = repository.findForTokenIssue(userId).orElseThrow(AppException.supply(ErrorCode.USER_NOT_FOUND));
        if (!user.isActive()) {
            log.info("Token refused, the account is not active: userId={}, status={}", userId, user.getStatus());
            throw AppException.of(ErrorCode.AUTH_ACCOUNT_SUSPENDED);
        }
        return user;
    }

    /** In the password change's transaction (hence MANDATORY), which also ends the account's sessions. */
    @Transactional(propagation = Propagation.MANDATORY)
    void changePassword(UUID userId, String newPasswordHash) {
        User user = getUser(userId);
        user.changePassword(newPasswordHash);
        repository.saveAndFlush(user);
    }

    // roles:begin
    // roles:else
    /**
     * The accounts with these ids, locked for update in id order until the caller's transaction ends (an
     * account status change: the caller and the target). A missing one is simply not in the list.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<User> lockForStatusChange(Collection<UUID> userIds) {
        return repository.findAllForUpdate(userIds);
    }
    // roles:end

    // roles:begin
    // tenancy:begin
    // tenancy:else
    // /**
    //  * The accounts with these ids, locked for update in id order until the caller's transaction ends: every change
    //  * of someone's access (a role, a grant, a suspension, a role's edit) locks the rows of the people it may change,
    //  * so two such changes of one person queue (PersonAccessService).
    //  */
    // @Transactional(propagation = Propagation.MANDATORY)
    // void lockForAccessChange(Collection<UUID> userIds) {
    //     repository.findAllForUpdate(userIds);
    // }
    //
    // tenancy:end
    // roles:end
    /** The status change's write, after its checks; its caller ends the sessions in the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    UserResponse changeStatus(User user, UserStatus status) {
        user.changeStatus(status);
        return mapper.toResponse(repository.saveAndFlush(user));
    }
    // session-version:begin
    // tenancy:begin
    // tenancy:else

    /** The account's session version (SessionVersionService's check, on a Redis miss); empty without an account. */
    Optional<Integer> findSessionVersion(UUID userId) {
        return repository.findSessionVersion(userId);
    }

    /**
     * Raises the account's session version in the access change's own transaction (hence MANDATORY): two
     * transactions could commit the change without the bump. Every access token issued before it, on every
     * device, is refused from the commit on; Redis gets the new number after the commit.
     */
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
