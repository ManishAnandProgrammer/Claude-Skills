package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.wrapper.ObjectWrapper;
// tenancy:begin
// tenancy:else
import com.example.app.user.api.Users;
// tenancy:end
import com.example.app.user.domain.User;
import com.example.app.user.dto.request.SignupRequest;
import com.example.app.user.dto.response.UserResponse;
import com.example.app.user.mapper.UserMapper;
import com.example.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
// session-version:begin
// tenancy:begin
// tenancy:else
import org.springframework.context.ApplicationEventPublisher;
// tenancy:end
// session-version:end
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// session-version:begin
// tenancy:begin
// tenancy:else
import java.util.Map;
// tenancy:end
// session-version:end
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
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
        String email = User.normalizeEmail(request.email());
        if (repository.existsByEmail(email)) {
            throw AppException.of(ErrorCode.USER_EMAIL_TAKEN);
        }
        breachedPasswordCheck.requireNotBreached(request.password());
        User user = User.register(email, request.fullName(), passwordEncoder.encode(request.password()));
        User saved = repository.saveAndFlush(user);
        log.info("Account created: userId={}", saved.getId());
        return ObjectWrapper.withObject(mapper.toResponse(saved));
    }

    public ObjectWrapper<UserResponse> me(UUID userId) {
        User user = repository.findById(userId).orElseThrow(AppException.supply(ErrorCode.USER_NOT_FOUND));
        return ObjectWrapper.withObject(mapper.toResponse(user));
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
        return repository.findByEmail(User.normalizeEmail(email));
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
