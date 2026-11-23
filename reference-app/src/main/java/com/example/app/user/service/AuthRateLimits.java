package com.example.app.user.service;

import com.example.app.common.ratelimit.RateLimitProperties;
import com.example.app.common.ratelimit.RedisRateLimiter;
import com.example.app.common.security.PersonalDataHasher;
import com.example.app.common.validation.Emails;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class AuthRateLimits {

    private final RedisRateLimiter limiter;
    private final RateLimitProperties limiterProperties;
    private final SignInRateLimitProperties limits;
    private final PersonalDataHasher hasher;

    void login(String clientIp, String email) {
        if (limiterProperties.enabled()) {
            limiter.acquire("login:ip:" + hasher.hash(clientIp), limits.loginPerIp());
            limiter.acquire("login:account:" + hasher.hash(Emails.normalize(email)), limits.loginPerAccount());
        }
    }

    void signup(String clientIp) {
        if (limiterProperties.enabled()) {
            limiter.acquire("signup:ip:" + hasher.hash(clientIp), limits.signupPerIp());
        }
    }

    void refresh(String clientIp) {
        if (limiterProperties.enabled()) {
            limiter.acquire("refresh:ip:" + hasher.hash(clientIp), limits.refreshPerIp());
        }
    }

    void logout(String clientIp) {
        if (limiterProperties.enabled()) {
            limiter.acquire("logout:ip:" + hasher.hash(clientIp), limits.logoutPerIp());
        }
    }
}
