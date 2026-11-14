package com.example.app.config;

import com.example.app.common.admission.AdmissionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StartupChecksTest {

    private static MockEnvironment environment(String... profiles) {
        return withoutManagementPort(profiles).withProperty("management.server.port", "8081");
    }

    private static MockEnvironment withoutManagementPort(String... profiles) {
        MockEnvironment environment = new MockEnvironment().withProperty("app.allowed-profiles", "dev,test,prod")
                .withProperty("spring.datasource.hikari.maximum-pool-size", "10")
                .withProperty("server.port", "8080");
        environment.setActiveProfiles(profiles);
        return environment;
    }

    private static final AdmissionProperties ENOUGH = new AdmissionProperties(20, Duration.ofMillis(50));
    private static final PasswordHashProperties PRODUCTION_HASH = new PasswordHashProperties(12);

    @Test
    @DisplayName("No active profile, or one outside the allow-list, stops startup")
    void refusesAMissingOrUnknownProfile() {
        assertThatThrownBy(() -> new StartupChecks(environment(), ENOUGH, PRODUCTION_HASH)).hasMessageContaining("SPRING_PROFILES_ACTIVE");
        assertThatThrownBy(() -> new StartupChecks(environment("staging"), ENOUGH, PRODUCTION_HASH)).hasMessageContaining("[staging]");
        assertThatCode(() -> new StartupChecks(environment("prod"), ENOUGH, PRODUCTION_HASH)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Admission below twice the pool stops startup")
    void refusesAdmissionBelowTwiceThePool() {
        assertThatThrownBy(() -> new StartupChecks(environment("prod"), new AdmissionProperties(19, Duration.ofMillis(50)), PRODUCTION_HASH))
                .hasMessageContaining("twice the pool size");
    }

    @Test
    @DisplayName("A management port that is missing or equal to the application port stops startup, in every profile but test alone")
    void refusesAManagementPortThatIsNotSeparate() {
        assertThatThrownBy(() -> new StartupChecks(environment("prod").withProperty("management.server.port", "8080"), ENOUGH, PRODUCTION_HASH))
                .hasMessageContaining("management.server.port (8080)");
        assertThatThrownBy(() -> new StartupChecks(withoutManagementPort("prod"), ENOUGH, PRODUCTION_HASH))
                .hasMessageContaining("management.server.port (null)");
        assertThatThrownBy(() -> new StartupChecks(environment("test", "prod").withProperty("management.server.port", "8080"), ENOUGH, PRODUCTION_HASH))
                .hasMessageContaining("management.server.port (8080)");
        assertThatThrownBy(() -> new StartupChecks(environment("dev").withProperty("management.server.port", "8080")
                .withProperty("app.management.shared-port-allowed", "true"), ENOUGH, PRODUCTION_HASH))
                .hasMessageContaining("management.server.port (8080)");
        assertThatCode(() -> new StartupChecks(environment("test").withProperty("management.server.port", "8080"), ENOUGH, PRODUCTION_HASH))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A password hash strength other than 12 stops startup, in every profile but test alone")
    void refusesAnotherPasswordHashStrengthOutsideTests() {
        assertThatThrownBy(() -> new StartupChecks(environment("prod"), ENOUGH, new PasswordHashProperties(11)))
                .hasMessageContaining("app.security.password-hash-strength (11)");
        assertThatThrownBy(() -> new StartupChecks(environment("prod"), ENOUGH, new PasswordHashProperties(14)))
                .hasMessageContaining("app.security.password-hash-strength (14)");
        assertThatThrownBy(() -> new StartupChecks(environment("test", "dev"), ENOUGH, new PasswordHashProperties(4)))
                .hasMessageContaining("app.security.password-hash-strength (4)");
        assertThatCode(() -> new StartupChecks(environment("test"), ENOUGH, new PasswordHashProperties(4)))
                .doesNotThrowAnyException();
    }
}
