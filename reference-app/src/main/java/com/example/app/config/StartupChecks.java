package com.example.app.config;

import com.example.app.common.admission.AdmissionProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Refuses to start in a configuration that is unsafe or inert, naming the setting:
 * <ul>
 *   <li>an active profile outside {@code app.allowed-profiles}, or none: a forgotten
 *       {@code SPRING_PROFILES_ACTIVE} must not run production on development defaults;</li>
 *   <li>{@code app.admission.max-concurrent} below twice the pool: readiness only trips when a pool's
 *       worth of requests waits for a connection, which admission must leave room for.</li>
 * </ul>
 */
@Component
class StartupChecks {

    private static final int DEFAULT_POOL_SIZE = 10;

    StartupChecks(Environment environment, AdmissionProperties admission) {
        checkProfiles(environment);
        checkAdmissionLeavesRoomForThePoolQueue(environment, admission);
    }

    private static void checkProfiles(Environment environment) {
        List<String> allowed = List.of(environment.getRequiredProperty("app.allowed-profiles", String[].class));
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        if (active.isEmpty() || !allowed.containsAll(active)) {
            throw new IllegalStateException("SPRING_PROFILES_ACTIVE must be one of %s, but is %s".formatted(allowed, active));
        }
    }

    private static void checkAdmissionLeavesRoomForThePoolQueue(Environment environment, AdmissionProperties admission) {
        int poolSize = environment.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class, DEFAULT_POOL_SIZE);
        if (admission.maxConcurrent() < 2 * poolSize) {
            throw new IllegalStateException("app.admission.max-concurrent (%d) must be at least twice the pool size (%d)"
                    .formatted(admission.maxConcurrent(), poolSize));
        }
    }
}
