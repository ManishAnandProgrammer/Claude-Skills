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
        this.retryAfterSeconds = Math.max(1, retryAfter.toSeconds());
    }
}
