package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.StaffAccounts;
import com.example.app.common.validation.Emails;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.domain.PlatformUser;
import com.example.app.user.dto.request.LoginRequest;
import com.example.app.user.dto.response.TokenResponse;
import com.example.app.user.repository.PlatformUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Platform staff's login (control plane): an access token with audience {@code platform}, accepted
 * only under {@code /api/v1/platform/**}. No refresh token: staff sign in again after the access
 * token's lifetime. Rate limited like the tenant login, and as slow for an unknown email. A deactivated
 * staff member is refused like an unknown email (staff are told by the operator, never by the login), and
 * every platform request re-reads the row ({@link #isActive}, ActiveStaffFilter), so the tokens they hold
 * die at their next request.
 */
@Service
@Slf4j
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class PlatformAuthService implements StaffAccounts {

    private final PlatformUserRepository repository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuthRateLimits rateLimits;
    private final String unknownUserHash;

    public PlatformAuthService(PlatformUserRepository repository, TokenService tokenService, PasswordEncoder passwordEncoder,
                               AuthRateLimits rateLimits) {
        this.repository = repository;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.rateLimits = rateLimits;
        this.unknownUserHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public ObjectWrapper<TokenResponse> login(LoginRequest request, String clientIp) {
        rateLimits.login(clientIp, request.email());
        Optional<PlatformUser> found = repository.findByEmail(Emails.normalize(request.email()));
        boolean passwordMatches = passwordEncoder.matches(request.password(),
                found.map(PlatformUser::getPasswordHash).orElse(unknownUserHash));
        PlatformUser staff = found.filter(account -> passwordMatches).orElseThrow(this::invalidCredentials);
        if (!staff.isActive()) {
            log.info("Platform login refused, the staff member is deactivated: platformUserId={}", staff.getId());
            throw AppException.of(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        log.info("Platform login succeeded: platformUserId={}", staff.getId());
        return ObjectWrapper.withObject(tokenService.issuePlatform(staff));
    }

    /** One read by primary key, so a deactivation reaches the next request. */
    @Override
    public boolean isActive(UUID staffId) {
        return repository.findById(staffId).filter(PlatformUser::isActive).isPresent();
    }

    private AppException invalidCredentials() {
        log.info("Platform login refused: invalid credentials");
        return AppException.of(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }
}
