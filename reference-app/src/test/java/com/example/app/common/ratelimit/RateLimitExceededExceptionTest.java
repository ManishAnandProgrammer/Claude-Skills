package com.example.app.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitExceededExceptionTest {

    @ParameterizedTest(name = "{0} ms → Retry-After {1}")
    @CsvSource({"0, 1", "1, 1", "1000, 1", "1001, 2", "2999, 3", "3000, 3"})
    @DisplayName("Retry-After is the wait rounded up to whole seconds, never below 1: a client never comes back early")
    void roundsTheWaitUp(long waitMillis, long expectedSeconds) {
        assertThat(new RateLimitExceededException(Duration.ofMillis(waitMillis)).getRetryAfterSeconds())
                .isEqualTo(expectedSeconds);
    }
}
