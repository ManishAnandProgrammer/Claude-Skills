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
                       @NotNull @DurationMin(millis = 1) Duration uploadUrlExpiry,
                       @NotNull @DurationMin(millis = 1) Duration readUrlExpiry,
                       @NotNull @DurationMin(days = 0) Duration pendingLifetime,
                       @Valid @NotNull Client client) {

    // SigV4 presigned URLs are valid for at most seven days.
    private static final Duration LONGEST_SIGNATURE = Duration.ofDays(7);

    MediaProperties {
        if (Objects.nonNull(readUrlExpiry) && readUrlExpiry.compareTo(LONGEST_SIGNATURE) > 0) {
            throw new IllegalArgumentException("app.media.read-url-expiry must be at most 7 days (SigV4)");
        }
        if (Objects.nonNull(endpoint) && (!endpoint.isAbsolute() || Objects.isNull(endpoint.getHost()))) {
            throw new IllegalArgumentException("app.media.endpoint must be empty (AWS) or an absolute URL with a host");
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
