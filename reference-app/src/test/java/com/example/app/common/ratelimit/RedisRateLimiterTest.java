package com.example.app.common.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Fail-open against a Redis that isn't there (nothing listens on port 1). */
class RedisRateLimiterTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final LettuceConnectionFactory unreachable = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
    private final RedisRateLimiter limiter;

    RedisRateLimiterTest() {
        unreachable.afterPropertiesSet();
        unreachable.start();
        RateLimitProperties properties = new RateLimitProperties(true, Duration.ofMillis(200), Duration.ofSeconds(10),
                Duration.ofSeconds(5));
        limiter = new RedisRateLimiter(unreachable, properties, meterRegistry);
    }

    @AfterEach
    void stop() {
        limiter.destroy();
        unreachable.destroy();
    }

    @Test
    @DisplayName("Without Redis a request is let through and counted; the reconnect failure is counted apart")
    void failsOpenAndCountsSeparately() {
        limiter.connectIfNeeded();

        assertThatCode(() -> limiter.acquire("login:ip:test", new RateLimit(1, Duration.ofMinutes(1)))).doesNotThrowAnyException();
        assertThatCode(() -> limiter.acquire("login:ip:test", new RateLimit(1, Duration.ofMinutes(1)))).doesNotThrowAnyException();

        assertThat(meterRegistry.counter(RedisRateLimiter.LET_THROUGH_METRIC).count()).isEqualTo(2.0);
        assertThat(meterRegistry.counter(RedisRateLimiter.RECONNECT_FAILURE_METRIC).count()).isEqualTo(1.0);
    }
}
