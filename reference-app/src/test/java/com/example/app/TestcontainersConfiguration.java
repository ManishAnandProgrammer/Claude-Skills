package com.example.app;

// media:begin
import com.example.app.support.MediaStorageContainer;
import org.springframework.beans.factory.annotation.Value;
// media:end
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
// media:begin
import org.springframework.test.context.DynamicPropertyRegistrar;
// media:end
// sign-in:begin
import org.testcontainers.containers.GenericContainer;
// sign-in:end
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL (and Redis with the sign-in piece, an S3-compatible store with the media piece) in Docker for the integration tests, the same major
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
    // media:begin

    @Bean
    MediaStorageContainer mediaStorageContainer() {
        return new MediaStorageContainer();
    }

    /**
     * Points the application at the container and creates its bucket. The credentials reach the SDK the
     * way production's do, through its default chain (here the {@code aws.*} system properties; an IAM
     * role there): the application has no setting for keys.
     */
    @Bean
    DynamicPropertyRegistrar mediaStorageProperties(MediaStorageContainer mediaStorageContainer, @Value("${app.media.bucket}") String bucket) {
        mediaStorageContainer.start();
        mediaStorageContainer.createBucket(bucket);
        System.setProperty("aws.accessKeyId", MediaStorageContainer.ACCESS_KEY);
        System.setProperty("aws.secretAccessKey", MediaStorageContainer.SECRET_KEY);
        return registry -> registry.add("app.media.endpoint", mediaStorageContainer::endpoint);
    }
    // media:end
}
