package com.example.app.config;

import com.example.app.common.admission.AdmissionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
class StartupChecks implements InitializingBean {

    private static final List<String> TEST_ONLY_PROFILES = List.of("test");
    /**
     * A security decision taken with a load test, not a tunable: lower, a stolen hash is cheap to crack; higher,
     * sign-in outgrows its CPU and its admission cap.
     */
    private static final int PASSWORD_HASH_STRENGTH = 12;

    private final Environment environment;
    private final AdmissionProperties admission;
    private final PasswordHashProperties passwordHash;

    @Override
    public void afterPropertiesSet() {
        checkProfiles(environment);
        checkAdmissionLeavesRoomForThePoolQueue(environment, admission);
        checkManagementPortIsSeparate(environment);
        checkPasswordHashStrength(environment, passwordHash);
    }

    private static boolean isTestProfileAlone(Environment environment) {
        return Arrays.asList(environment.getActiveProfiles()).equals(TEST_ONLY_PROFILES);
    }

    /** A forgotten {@code SPRING_PROFILES_ACTIVE} must not run production on development defaults. */
    private static void checkProfiles(Environment environment) {
        List<String> allowed = List.of(environment.getRequiredProperty("app.allowed-profiles", String[].class));
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        if (active.isEmpty() || !allowed.containsAll(active)) {
            throw new IllegalStateException("SPRING_PROFILES_ACTIVE must be one of %s, but is %s".formatted(allowed, active));
        }
    }

    private static void checkAdmissionLeavesRoomForThePoolQueue(Environment environment, AdmissionProperties admission) {
        int poolSize = environment.getRequiredProperty("spring.datasource.hikari.maximum-pool-size", Integer.class);
        if (admission.maxConcurrent() < 2 * poolSize) {
            throw new IllegalStateException("app.admission.max-concurrent (%d) must be at least twice the pool size (%d)"
                    .formatted(admission.maxConcurrent(), poolSize));
        }
    }

    /**
     * The prometheus scrape has no token, so on the application port it would be public. No property allows sharing
     * the port, so no deployment can switch it on.
     */
    private static void checkManagementPortIsSeparate(Environment environment) {
        if (isTestProfileAlone(environment)) {
            return;
        }
        String serverPort = environment.getProperty("server.port");
        String managementPort = environment.getProperty("management.server.port");
        if (Objects.isNull(managementPort) || managementPort.equals(serverPort)) {
            throw new IllegalStateException("management.server.port (%s) must be set and differ from server.port (%s)"
                    .formatted(managementPort, serverPort));
        }
    }

    private static void checkPasswordHashStrength(Environment environment, PasswordHashProperties passwordHash) {
        if (isTestProfileAlone(environment)) {
            return;
        }
        int strength = passwordHash.passwordHashStrength();
        if (strength != PASSWORD_HASH_STRENGTH) {
            throw new IllegalStateException("app.security.password-hash-strength (%d) must be %d outside the test profile"
                    .formatted(strength, PASSWORD_HASH_STRENGTH));
        }
    }
}
