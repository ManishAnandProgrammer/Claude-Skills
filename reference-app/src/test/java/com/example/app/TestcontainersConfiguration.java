package com.example.app;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
// sign-in:begin
import org.testcontainers.containers.GenericContainer;
// sign-in:end
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL (and Redis, with the sign-in piece) in Docker for the integration tests, the same major
 * versions and image family as production (glibc images, not alpine: collation and time-zone
 * behaviour must match).
 * Liquibase builds the schema as in a real deployment. Started once and shared by every test class.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:18");
    }

    // sign-in:begin
    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>("redis:8").withExposedPorts(6379);
    }
    // sign-in:end
}
