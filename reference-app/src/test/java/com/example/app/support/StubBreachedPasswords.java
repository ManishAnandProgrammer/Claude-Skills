package com.example.app.support;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.authentication.password.CompromisedPasswordDecision;

/**
 * Replaces Have I Been Pwned in the tests, which must not depend on the internet: a password
 * containing {@link #BREACHED} is compromised; one containing {@link #UNREACHABLE} behaves like the
 * real wiring during an outage, which counts the failure and answers "not compromised" (the real
 * checker never throws for a failed request). The real wiring itself is tested against a local
 * range API in {@code BreachedPasswordConfigTest}.
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
