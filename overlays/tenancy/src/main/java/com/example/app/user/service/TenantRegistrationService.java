package com.example.app.user.service;

import com.example.app.tenant.api.Tenants;
import com.example.app.user.domain.User;
import com.example.app.user.dto.request.SignupRequest;
import com.example.app.user.dto.request.TenantRegistrationRequest;
import com.example.app.user.dto.response.UserResponse;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Self-registration of an organisation: a PENDING tenant owned by the registering account, which staff
 * approve or reject. The answer is the same whether or not the email has an account, so the endpoint
 * reveals nothing about who is registered:
 * <ul>
 *   <li>a new email gets a new account (signup's own path: its rate limit, breached-password check and
 *       hash), which owns the tenant;</li>
 *   <li>an existing account owns the tenant only when the password is its own; otherwise nothing
 *       happens, so no one can attach a tenant to someone else's account. This path takes the same
 *       rate limit, check and one hash, so its timing matches too.</li>
 * </ul>
 * Runs without a transaction, like signup: hashing and the remote check must not hold a connection.
 */
@Service
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class TenantRegistrationService {

    private final UserService userService;
    private final Tenants tenants;
    private final PasswordEncoder passwordEncoder;
    private final AuthRateLimits rateLimits;
    private final BreachedPasswordCheck breachedPasswordCheck;

    public void register(TenantRegistrationRequest request, String clientIp) {
        Optional<User> existing = userService.findByEmail(request.email());
        if (existing.isPresent()) {
            registerForExistingAccount(existing.get(), request, clientIp);
        } else {
            registerWithNewAccount(request, clientIp);
        }
    }

    private void registerForExistingAccount(User account, TenantRegistrationRequest request, String clientIp) {
        rateLimits.signup(clientIp);
        breachedPasswordCheck.requireNotBreached(request.password());
        if (!passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            log.info("Tenant registration ignored: the password is not the existing account's: userId={}", account.getId());
            return;
        }
        UUID tenantId = tenants.register(request.organisationName(), account.getEmail());
        log.info("Tenant registered: tenantId={}, ownerId={}", tenantId, account.getId());
    }

    private void registerWithNewAccount(TenantRegistrationRequest request, String clientIp) {
        UserResponse account = userService.signup(new SignupRequest(request.email(), request.fullName(), request.password()), clientIp)
                .getResponse();
        UUID tenantId = tenants.register(request.organisationName(), account.email());
        log.info("Tenant registered with a new account: tenantId={}, ownerId={}", tenantId, account.id());
    }
}
