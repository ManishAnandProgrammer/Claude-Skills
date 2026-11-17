package com.example.app.config;

import com.example.app.common.admission.AdmissionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Refuses to start in a configuration that is unsafe or inert, naming the setting:
 * <ul>
 *   <li>an active profile outside {@code app.allowed-profiles}, or none: a forgotten
 *       {@code SPRING_PROFILES_ACTIVE} must not run production on development defaults;</li>
 *   <li>{@code app.admission.max-concurrent} below twice the pool: readiness only trips when a pool's
 *       worth of requests waits for a connection, which admission must leave room for;</li>
 *   <li>a management port that is missing or equal to the application port: the prometheus scrape
 *       has no token, so on the application port it would be public. Only the {@code test}
 *       profile alone may share the port (MockMvc reaches the actuator through the application);
 *       there is deliberately no property to allow it, so no deployment can switch it on.</li>
 *   <li>{@code app.security.password-hash-strength} other than 12: bcrypt's cost is a security decision
 *       taken with a load test (it sets sign-in CPU and the {@code sign-in} admission cap), not a
 *       per-deployment setting. Only the {@code test} profile alone may lower it (its accounts are
 *       throwaway, and each costs two hashes); no property allows it elsewhere.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class StartupChecks implements InitializingBean {

    /** The test profile alone: the only one allowed the shortcuts below. */
    private static final List<String> TEST_ONLY_PROFILES = List.of("test");
    /** A security decision, not a tunable: lower, a stolen hash is cheap to crack; higher, sign-in outgrows its CPU. */
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
