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
 * Refuses passwords known from breaches. Only the first five hex characters of the password's SHA-1
 * leave the server (the range API's k-anonymity).
 *
 * <p>Fail-open by the owner's decision: while the service is unreachable, signup proceeds with the
 * composition rules alone. Spring's checker answers "not compromised" on a failed request (counted
 * by {@link FailureCountingInterceptor}); the catch below covers anything else it throws.</p>
 *
 * <p>A semaphore bulkhead ({@code resilience4j.bulkhead.instances.breached-passwords}) bounds the
 * lookups in flight: a slow service would otherwise hold an admission permit per signup for up to
 * the read timeout and fill the API's cap. When it is full the check is skipped, counted like an
 * outage. The instance is the Resilience4j starter's; one missing from the configuration stops startup
 * rather than run on the library's defaults.</p>
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
