package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.domain.User;
import com.example.app.user.dto.request.SignupRequest;
import com.example.app.user.dto.response.UserResponse;
import com.example.app.user.mapper.UserMapper;
import com.example.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository repository;
    private final UserMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthRateLimits rateLimits;

    /**
     * Runs without a transaction: hashing the password takes about a quarter of a second of CPU, and
     * must not hold a pooled connection meanwhile. The existence check and the insert each run in
     * their own short transaction; a concurrent signup with the same email that slips between them is
     * refused by {@code uq_users_email} with the same {@code USER_EMAIL_TAKEN}.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ObjectWrapper<UserResponse> signup(SignupRequest request, String clientIp) {
        rateLimits.signup(clientIp);
        String email = User.normalizeEmail(request.email());
        if (repository.existsByEmail(email)) {
            throw AppException.of(ErrorCode.USER_EMAIL_TAKEN);
        }
        User user = User.register(email, request.fullName(), passwordEncoder.encode(request.password()));
        User saved = repository.saveAndFlush(user);
        log.info("Account created: userId={}", saved.getId());
        return ObjectWrapper.withObject(mapper.toResponse(saved));
    }

    public ObjectWrapper<UserResponse> me(UUID userId) {
        User user = repository.findById(userId).orElseThrow(AppException.supply(ErrorCode.USER_NOT_FOUND));
        return ObjectWrapper.withObject(mapper.toResponse(user));
    }

    Optional<User> findByEmail(String email) {
        return repository.findByEmail(User.normalizeEmail(email));
    }
}
