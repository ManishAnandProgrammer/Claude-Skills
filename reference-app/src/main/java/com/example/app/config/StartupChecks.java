package com.example.app.config;

import com.example.app.common.admission.AdmissionProperties;
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
 * </ul>
 */
@Component
class StartupChecks {

    private static final List<String> SHARED_PORT_PROFILES = List.of("test");

    StartupChecks(Environment environment, AdmissionProperties admission) {
        checkProfiles(environment);
        checkAdmissionLeavesRoomForThePoolQueue(environment, admission);
        checkManagementPortIsSeparate(environment);
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
        if (Arrays.asList(environment.getActiveProfiles()).equals(SHARED_PORT_PROFILES)) {
            return;
        }
        String serverPort = environment.getProperty("server.port");
        String managementPort = environment.getProperty("management.server.port");
        if (Objects.isNull(managementPort) || managementPort.equals(serverPort)) {
            throw new IllegalStateException("management.server.port (%s) must be set and differ from server.port (%s)"
                    .formatted(managementPort, serverPort));
        }
    }
}
