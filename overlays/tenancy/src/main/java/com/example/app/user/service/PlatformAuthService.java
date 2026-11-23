package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.validation.Emails;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.domain.PlatformUser;
import com.example.app.user.dto.request.LoginRequest;
import com.example.app.user.dto.response.TokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class PlatformAuthService {

    private final PlatformStaffService staffAccounts;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuthRateLimits rateLimits;
    private final String unknownUserHash;

    // Hand-written: the decoy hash costs one bcrypt run, paid once at startup.
    PlatformAuthService(PlatformStaffService staffAccounts, TokenService tokenService, PasswordEncoder passwordEncoder,
                        AuthRateLimits rateLimits) {
        this.staffAccounts = staffAccounts;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.rateLimits = rateLimits;
        this.unknownUserHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public ObjectWrapper<TokenResponse> login(LoginRequest request, String clientIp) {
        rateLimits.login(clientIp, request.email());
        Optional<PlatformUser> found = staffAccounts.findByEmail(Emails.normalize(request.email()));
        boolean passwordMatches = passwordEncoder.matches(request.password(),
                found.map(PlatformUser::getPasswordHash).orElse(unknownUserHash));
        PlatformUser staff = found.filter(account -> passwordMatches).orElseThrow(this::invalidCredentials);
        if (!staff.isActive()) {
            // Like an unknown email: staff are told by the operator, never by the login.
            log.info("Platform login refused, the staff member is deactivated: platformUserId={}", staff.getId());
            throw AppException.of(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        log.info("Platform login succeeded: platformUserId={}", staff.getId());
        return ObjectWrapper.withObject(tokenService.issuePlatform(staff));
    }

    private AppException invalidCredentials() {
        log.info("Platform login refused: invalid credentials");
        return AppException.of(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }
}
