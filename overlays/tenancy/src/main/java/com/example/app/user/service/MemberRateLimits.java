package com.example.app.user.service;

import com.example.app.common.ratelimit.RateLimitProperties;
import com.example.app.common.ratelimit.RedisRateLimiter;
import com.example.app.common.security.PersonalDataHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Throttles a tenant ADMIN's member adds: each one looks an email up and, when it has an account, answers the
 * person's name, so an unthrottled add would let any ADMIN of any tenant map emails to people. Per ADMIN
 * account; the id reaches Redis only as a keyed hash, as the sign-in limits' keys do.
 */
@Component
@RequiredArgsConstructor
class MemberRateLimits {

    private final RedisRateLimiter limiter;
    private final RateLimitProperties limiterProperties;
    private final MemberRateLimitProperties limits;
    private final PersonalDataHasher hasher;

    /** 429 {@code RATE_LIMITED} once the caller used up {@code app.rate-limit.members.add-per-account}. */
    void add(UUID callerId) {
        if (limiterProperties.enabled()) {
            limiter.acquire("member-add:account:" + hasher.hash(callerId.toString()), limits.addPerAccount());
        }
    }
}
