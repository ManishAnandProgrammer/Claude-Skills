package com.example.app.support;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.authentication.password.CompromisedPasswordDecision;

/**
 * {@link #UNREACHABLE} behaves like the real wiring during an outage, which counts the failure and answers "not
 * compromised" (the real checker never throws for a failed request).
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubBreachedPasswords {

    public static final String BREACHED = "Breached";
    public static final String UNREACHABLE = "Unreachable";
    public static final String UNAVAILABLE_METRIC = "breached_password.check.unavailable";

    @Bean
    @Primary
    CompromisedPasswordChecker stubCompromisedPasswordChecker(MeterRegistry meterRegistry) {
        return password -> {
            if (password.contains(UNREACHABLE)) {
                meterRegistry.counter(UNAVAILABLE_METRIC).increment();
                return new CompromisedPasswordDecision(false);
            }
            return new CompromisedPasswordDecision(password.contains(BREACHED));
        };
    }
}
