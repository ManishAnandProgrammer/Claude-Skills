package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.authentication.password.CompromisedPasswordDecision;
import org.springframework.stereotype.Component;

/**
 * The bulkhead bounds the lookups in flight: a slow service would otherwise hold an admission permit per signup
 * for up to the read timeout and fill the API's cap.
 */
@Slf4j
@Component
class BreachedPasswordCheck {

    static final String UNAVAILABLE_METRIC = "breached_password.check.unavailable";
    static final String BULKHEAD = "breached-passwords";

    private final CompromisedPasswordChecker checker;
    private final BreachedPasswordProperties properties;
    private final Bulkhead bulkhead;
    private final Counter unavailable;

    // Hand-written: the bulkhead is looked up once (a missing instance stops the startup rather than run on the
    // library's defaults) and the counter registered, so its series reads 0 before the first failed lookup.
    BreachedPasswordCheck(CompromisedPasswordChecker checker, BreachedPasswordProperties properties,
                          BulkheadRegistry bulkheads, MeterRegistry meterRegistry) {
        this.checker = checker;
        this.properties = properties;
        this.bulkhead = bulkheads.find(BULKHEAD).orElseThrow(() -> new IllegalStateException(
                "The breached-password check needs resilience4j.bulkhead.instances.%s".formatted(BULKHEAD)));
        this.unavailable = unavailableCounter(meterRegistry);
    }

    static Counter unavailableCounter(MeterRegistry meterRegistry) {
        return Counter.builder(UNAVAILABLE_METRIC)
                .description("Breached-password lookups that failed or were skipped; signup proceeded without the check")
                .register(meterRegistry);
    }

    void requireNotBreached(String password) {
        if (!properties.enabled()) {
            return;
        }
        if (!bulkhead.tryAcquirePermission()) {
            unavailable.increment();
            log.warn("Breached-password check skipped, {} lookups already in flight: the password is accepted",
                    bulkhead.getBulkheadConfig().getMaxConcurrentCalls());
            return;
        }
        CompromisedPasswordDecision decision;
        try {
            decision = checker.check(password);
        } catch (RuntimeException e) {
            unavailable.increment();
            log.warn("Breached-password check unavailable, the password is accepted: {}", e.toString());
            return;
        } finally {
            bulkhead.onComplete();
        }
        if (decision.isCompromised()) {
            throw AppException.of(ErrorCode.USER_PASSWORD_BREACHED);
        }
    }
}
