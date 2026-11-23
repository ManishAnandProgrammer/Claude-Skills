package com.example.app.user.service;

import com.example.app.common.ratelimit.RateLimitProperties;
import com.example.app.common.ratelimit.RedisRateLimiter;
import com.example.app.common.security.PersonalDataHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** An add answers the person's name for an email: unthrottled, any tenant's ADMIN could map emails to people. */
@Component
@RequiredArgsConstructor
class MemberRateLimits {

    private final RedisRateLimiter limiter;
    private final RateLimitProperties limiterProperties;
    private final MemberRateLimitProperties limits;
    private final PersonalDataHasher hasher;

    void add(UUID callerId) {
        if (limiterProperties.enabled()) {
            limiter.acquire("member-add:account:" + hasher.hash(callerId.toString()), limits.addPerAccount());
        }
    }
}
