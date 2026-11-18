package com.example.app.archunit.fixture.settings;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Each value but the count is bound as a number or text that code converts or parses later. Like every
 * {@code @ConfigurationProperties} record under the root package, the application's scan binds it (empty) in the
 * integration tests' context too: harmless, and the rule's proof needs the annotation.
 */
@ConfigurationProperties(prefix = "fixture.untyped")
public record UntypedSettings(long leaseMinutes, double timeoutSeconds, Integer seconds, String apiUrl, String cron,
                              int batchSize, Client client, Map<String, Store> stores) {

    public record Client(long readTimeoutMs, String endpoint) {
    }

    /** Reached only through the map's values. */
    public record Store(String baseUri) {
    }
}
