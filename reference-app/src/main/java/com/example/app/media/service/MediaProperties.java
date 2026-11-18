package com.example.app.media.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * {@code app.media.*} (config/media.yml).
 *
 * @param region          bound through the SDK's {@code Region.of}, which takes any name: a wrong one fails at the first call
 * @param endpoint        null (empty in the file) for AWS; an S3-compatible store's absolute URL with a host otherwise
 * @param pendingLifetime how long after its upload URL expired a file can still be attached
 */
@Validated
@ConfigurationProperties(prefix = "app.media")
record MediaProperties(@NotBlank String bucket,
                       @NotNull Region region,
                       URI endpoint,
                       boolean pathStyleAccess,
                       @NotNull Duration uploadUrlExpiry,
                       @NotNull Duration readUrlExpiry,
                       @NotNull @DurationMin(days = 0) Duration pendingLifetime,
                       @Valid @NotNull Client client) {

    // SigV4 signs a URL for at least one second and at most seven days: the SDK refuses any other expiry at each
    // presign, so every upload or read URL would fail.
    private static final Duration SHORTEST_SIGNATURE = Duration.ofSeconds(1);
    private static final Duration LONGEST_SIGNATURE = Duration.ofDays(7);

    MediaProperties {
        checkSignable("app.media.upload-url-expiry", uploadUrlExpiry);
        checkSignable("app.media.read-url-expiry", readUrlExpiry);
        if (Objects.nonNull(endpoint) && (!endpoint.isAbsolute() || Objects.isNull(endpoint.getHost()))) {
            throw new IllegalArgumentException("app.media.endpoint must be empty (AWS) or an absolute URL with a host");
        }
    }

    private static void checkSignable(String key, Duration expiry) {
        if (Objects.nonNull(expiry) && (expiry.compareTo(SHORTEST_SIGNATURE) < 0 || expiry.compareTo(LONGEST_SIGNATURE) > 0)) {
            throw new IllegalArgumentException(key + " must be between 1s and 7d (SigV4)");
        }
    }

    /**
     * {@code app.media.client.*}: the SDK sets neither by default, so a hung store would hold the thread.
     *
     * @param apiCallTimeout        the whole call, retries included
     * @param apiCallAttemptTimeout one attempt
     */
    record Client(@NotNull @DurationMin(millis = 1) Duration apiCallTimeout,
                  @NotNull @DurationMin(millis = 1) Duration apiCallAttemptTimeout) {

        Client {
            if (Objects.nonNull(apiCallTimeout) && Objects.nonNull(apiCallAttemptTimeout)
                    && apiCallAttemptTimeout.compareTo(apiCallTimeout) > 0) {
                throw new IllegalArgumentException("app.media.client.api-call-attempt-timeout must not exceed api-call-timeout");
            }
        }
    }
}
