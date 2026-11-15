package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.domain.User;
import com.example.app.user.dto.request.LoginRequest;
import com.example.app.user.dto.request.RefreshTokenRequest;
import com.example.app.user.dto.response.TokenResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Sign-in: login, refresh, logout. The methods run without a transaction (the password check is
 * CPU work that must not hold a connection); each step that touches the database runs in its own.
 */
@Service
@Slf4j
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AuthService {

    static final String REFUSED_LOGINS_METRIC = "auth.login.refused";

    private final UserService userService;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuthRateLimits rateLimits;
    /** Refused logins: logged at INFO, which production's WARN hides, so they are counted here. */
    private final Counter refusedLogins;
    /**
     * Compared against when the email is unknown, so an unknown email takes as long as a wrong
     * password and the answer is the same: login doesn't reveal which emails have accounts.
     */
    private final String unknownUserHash;

    public AuthService(UserService userService, TokenService tokenService, PasswordEncoder passwordEncoder,
                       AuthRateLimits rateLimits, MeterRegistry meterRegistry) {
        this.userService = userService;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.rateLimits = rateLimits;
        this.refusedLogins = Counter.builder(REFUSED_LOGINS_METRIC)
                .description("Logins refused for an unknown email or a wrong password")
                .register(meterRegistry);
        this.unknownUserHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public ObjectWrapper<TokenResponse> login(LoginRequest request, String clientIp) {
        rateLimits.login(clientIp, request.email());
        Optional<User> found = userService.findByEmail(request.email());
        boolean passwordMatches = passwordEncoder.matches(request.password(),
                found.map(User::getPasswordHash).orElse(unknownUserHash));
        User user = found.filter(account -> passwordMatches).orElseThrow(this::invalidCredentials);
        log.info("Login succeeded: userId={}", user.getId());
        return ObjectWrapper.withObject(tokenService.issue(user));
    }

    public ObjectWrapper<TokenResponse> refresh(RefreshTokenRequest request, String clientIp) {
        rateLimits.refresh(clientIp);
        return ObjectWrapper.withObject(tokenService.refresh(request.refreshToken()));
    }

    public void logout(RefreshTokenRequest request, String clientIp) {
        rateLimits.logout(clientIp);
        tokenService.revoke(request.refreshToken());
    }
    // session-version:begin

    /**
     * Signs the caller out on every device: their refresh tokens are revoked and their access tokens are
     * refused from the next request on (the session version). Signed in, so not rate limited like logout.
     */
    public void logoutEverywhere(UUID userId) {
        tokenService.endAllSessions(userId);
    }
    // session-version:end

    private AppException invalidCredentials() {
        refusedLogins.increment();
        log.info("Login refused: invalid credentials");
        return AppException.of(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }
}
