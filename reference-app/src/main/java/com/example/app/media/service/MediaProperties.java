package com.example.app.media.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Objects;

/**
 * {@code app.media.*} (config/media.yml).
 *
 * @param endpoint        empty for AWS; an S3-compatible store's URL otherwise
 * @param pendingLifetime how long after its upload URL expired a file can still be attached
 */
@Validated
@ConfigurationProperties(prefix = "app.media")
record MediaProperties(@NotBlank String bucket,
                       @NotBlank String region,
                       String endpoint,
                       boolean pathStyleAccess,
                       @NotNull Duration uploadUrlExpiry,
                       @NotNull Duration readUrlExpiry,
                       @NotNull Duration pendingLifetime,
                       @Valid @NotNull Client client) {

    // SigV4 presigned URLs are valid for at most seven days.
    private static final Duration LONGEST_SIGNATURE = Duration.ofDays(7);

    MediaProperties {
        if (Objects.nonNull(readUrlExpiry) && readUrlExpiry.compareTo(LONGEST_SIGNATURE) > 0) {
            throw new IllegalArgumentException("app.media.read-url-expiry must be at most 7 days (SigV4)");
        }
    }

    /**
     * {@code app.media.client.*}: the SDK sets neither by default, so a hung store would hold the thread.
     *
     * @param apiCallTimeout        the whole call, retries included
     * @param apiCallAttemptTimeout one attempt
     */
    record Client(@NotNull Duration apiCallTimeout, @NotNull Duration apiCallAttemptTimeout) {

        Client {
            if (Objects.nonNull(apiCallTimeout) && Objects.nonNull(apiCallAttemptTimeout)
                    && apiCallAttemptTimeout.compareTo(apiCallTimeout) > 0) {
                throw new IllegalArgumentException("app.media.client.api-call-attempt-timeout must not exceed api-call-timeout");
            }
        }
    }
}
