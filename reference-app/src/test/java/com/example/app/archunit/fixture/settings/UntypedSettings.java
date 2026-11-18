package com.example.app.archunit.fixture.settings;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Each value but the count is bound as a number or text that code converts or parses later. */
@ConfigurationProperties(prefix = "fixture.untyped")
public record UntypedSettings(long leaseMinutes, int timeoutMs, String apiUrl, String cron, int batchSize, Client client) {

    public record Client(long readTimeoutSeconds, String endpointUri) {
    }
}
