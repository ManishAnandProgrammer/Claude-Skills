package com.example.app.common.ratelimit;

import lombok.Getter;

import java.time.Duration;

@Getter
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(Duration retryAfter) {
        super("Rate limit exceeded");
        this.retryAfterSeconds = Math.max(1, ceilSeconds(retryAfter));
    }

    private static long ceilSeconds(Duration duration) {
        return duration.getNano() == 0 ? duration.getSeconds() : duration.getSeconds() + 1;
    }
}
