package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.exception.RequestError;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.domain.User;
import com.example.app.user.dto.request.LoginRequest;
import com.example.app.user.dto.request.PasswordChangeRequest;
import com.example.app.user.dto.request.RefreshTokenRequest;
import com.example.app.user.dto.response.TokenResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Without a transaction: the password check is CPU work that must not hold a connection. */
@Service
@Slf4j
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AuthService {

    static final String REFUSED_LOGINS_METRIC = "auth.login.refused";
    private static final String CURRENT_PASSWORD = "#/currentPassword";

    private final UserService userService;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuthRateLimits rateLimits;
    private final BreachedPasswordCheck breachedPasswordCheck;
    private final Counter refusedLogins;
    /**
     * Matched when the email is unknown, so that takes as long as a wrong password: login doesn't reveal which
     * emails have accounts.
     */
    private final String unknownUserHash;

    // Hand-written: the decoy hash costs one bcrypt run, paid once at startup, and the counter is registered then,
    // so its series reads 0 before the first refusal.
    AuthService(UserService userService, TokenService tokenService, PasswordEncoder passwordEncoder,
                AuthRateLimits rateLimits, BreachedPasswordCheck breachedPasswordCheck, MeterRegistry meterRegistry) {
        this.userService = userService;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.rateLimits = rateLimits;
        this.breachedPasswordCheck = breachedPasswordCheck;
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
        TokenResponse tokens = tokenService.issue(user);
        log.info("Login succeeded: userId={}", user.getId());
        return ObjectWrapper.withObject(tokens);
    }

    public ObjectWrapper<TokenResponse> refresh(RefreshTokenRequest request, String clientIp) {
        rateLimits.refresh(clientIp);
        return ObjectWrapper.withObject(tokenService.refresh(request.refreshToken()));
    }

    public void logout(RefreshTokenRequest request, String clientIp) {
        rateLimits.logout(clientIp);
        tokenService.revoke(request.refreshToken());
    }

    /** Rate limited as a login of the account: a stolen access token would otherwise guess the password here. */
    public ObjectWrapper<TokenResponse> changePassword(UUID userId, PasswordChangeRequest request, String clientIp) {
        User user = userService.getUser(userId);
        rateLimits.login(clientIp, user.getEmail());
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            log.info("Password change refused, the current password doesn't match: userId={}", userId);
            throw AppException.withErrors(ErrorCode.USER_PASSWORD_INCORRECT,
                    List.of(RequestError.atPointer(CURRENT_PASSWORD, "does not match your current password")));
        }
        breachedPasswordCheck.requireNotBreached(request.newPassword());
        return ObjectWrapper.withObject(tokenService.replacePassword(userId, passwordEncoder.encode(request.newPassword())));
    }
    // session-version:begin

    public void endAllSessions(UUID userId) {
        tokenService.endAllSessions(userId);
    }
    // session-version:end

    private AppException invalidCredentials() {
        refusedLogins.increment();
        log.info("Login refused: invalid credentials");
        return AppException.of(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }
}
