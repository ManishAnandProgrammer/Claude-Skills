package com.example.app.common.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * How long a refused client is told to wait ({@code app.retry-after.*}). Each answer picks a fresh
 * value within the range, so clients refused by one outage don't all come back in the same second.
 */
@Validated
@ConfigurationProperties(prefix = "app.retry-after")
public record RetryAfterProperties(@Valid @NotNull Range serverBusy,
                                   @Valid @NotNull Range databaseContention,
                                   @Valid @NotNull Range queryTimeout,
                                   @Valid @NotNull Range databaseUnavailable) {

    public record Range(@NotNull Duration min, @NotNull Duration max) {

        public Range {
            if (Objects.nonNull(min) && Objects.nonNull(max) && (min.isNegative() || max.compareTo(min) < 0)) {
                throw new IllegalArgumentException("a Retry-After range needs 0 <= min <= max");
            }
        }

        /** Whole seconds within the range, jittered, as the {@code Retry-After} header wants them. */
        public String pickSeconds() {
            return String.valueOf(ThreadLocalRandom.current().nextLong(min.toSeconds(), max.toSeconds() + 1));
        }
    }
}
