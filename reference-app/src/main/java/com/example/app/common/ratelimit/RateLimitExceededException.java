package com.example.app.common.ratelimit;

import lombok.Getter;

import java.time.Duration;

/**
 * A rate limit is used up: answered 429 {@code RATE_LIMITED} with {@code Retry-After}. Its own
 * class (not an {@code AppException}) because the handler must read the wait time for the header.
 */
@Getter
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(Duration retryAfter) {
        super("Rate limit exceeded");
        // Rounded up: a client told to come back before the bucket has refilled is refused again.
        this.retryAfterSeconds = Math.max(1, ceilSeconds(retryAfter));
    }

    private static long ceilSeconds(Duration duration) {
        return duration.getNano() == 0 ? duration.getSeconds() : duration.getSeconds() + 1;
    }
}
