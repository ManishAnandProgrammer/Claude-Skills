package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.authentication.password.CompromisedPasswordDecision;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BreachedPasswordCheckTest {

    private static final String PASSWORD = "Correct-Horse-9";

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final BulkheadRegistry bulkheads = BulkheadRegistry.of(BulkheadConfig.custom()
            .maxConcurrentCalls(1)
            .maxWaitDuration(Duration.ZERO)
            .build());
    private final AtomicInteger checks = new AtomicInteger();

    private BreachedPasswordCheck check(boolean enabled, CompromisedPasswordChecker checker) {
        BreachedPasswordProperties properties = new BreachedPasswordProperties(enabled, "http://unused/",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        CompromisedPasswordChecker counting = password -> {
            checks.incrementAndGet();
            return checker.check(password);
        };
        return new BreachedPasswordCheck(counting, properties, bulkheads, meterRegistry);
    }

    private double unavailable() {
        return meterRegistry.counter(BreachedPasswordCheck.UNAVAILABLE_METRIC).count();
    }

    @Test
    @DisplayName("A compromised password is refused with USER_PASSWORD_BREACHED, and the permit is returned")
    void refusesACompromisedPassword() {
        BreachedPasswordCheck check = check(true, password -> new CompromisedPasswordDecision(true));

        assertThatThrownBy(() -> check.requireNotBreached(PASSWORD))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_PASSWORD_BREACHED));
        assertThat(bulkheads.bulkhead(BreachedPasswordCheck.BULKHEAD).getMetrics().getAvailableConcurrentCalls()).isOne();
    }

    @Test
    @DisplayName("A checker that throws lets the signup proceed, counts it, and returns the permit")
    void aThrowingCheckerFailsOpen() {
        BreachedPasswordCheck check = check(true, password -> {
            throw new IllegalStateException("unexpected");
        });

        assertThatCode(() -> check.requireNotBreached(PASSWORD)).doesNotThrowAnyException();
        assertThat(unavailable()).isOne();
        assertThat(bulkheads.bulkhead(BreachedPasswordCheck.BULKHEAD).getMetrics().getAvailableConcurrentCalls()).isOne();
    }

    @Test
    @DisplayName("Switched off, the check never calls the service")
    void disabledNeverCalls() {
        check(false, password -> new CompromisedPasswordDecision(true)).requireNotBreached(PASSWORD);

        assertThat(checks.get()).isZero();
        assertThat(unavailable()).isZero();
    }
}
