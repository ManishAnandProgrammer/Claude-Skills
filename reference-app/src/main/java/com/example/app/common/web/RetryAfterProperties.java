package com.example.app.common.web;

import com.example.app.common.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
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
                                   // media:begin
                                   @Valid @NotNull Range mediaStorageUnavailable,
                                   // media:end
                                   @Valid @NotNull Range databaseUnavailable) {

    /**
     * The range for a code a client should come back from: every 503 has one
     * ({@code ErrorCodeContractTest}), so no refusal of that kind goes out without {@code Retry-After}.
     */
    public Optional<Range> rangeFor(ErrorCode code) {
        return switch (code) {
            case SERVER_BUSY -> Optional.of(serverBusy);
            case DATABASE_CONTENTION -> Optional.of(databaseContention);
            case QUERY_TIMEOUT -> Optional.of(queryTimeout);
            case DATABASE_UNAVAILABLE -> Optional.of(databaseUnavailable);
            // media:begin
            case MEDIA_STORAGE_UNAVAILABLE -> Optional.of(mediaStorageUnavailable);
            // media:end
            default -> Optional.empty();
        };
    }

    public record Range(@NotNull Duration min, @NotNull Duration max) {

        // Retry-After is whole seconds: below one second the header would read 0, "come back at once",
        // which is what the refusal exists to prevent.
        private static final Duration SHORTEST = Duration.ofSeconds(1);

        public Range {
            if (Objects.nonNull(min) && Objects.nonNull(max) && (min.compareTo(SHORTEST) < 0 || max.compareTo(min) < 0)) {
                throw new IllegalArgumentException("a Retry-After range needs 1s <= min <= max");
            }
        }

        /** Whole seconds within the range, jittered, as the {@code Retry-After} header wants them. */
        public String pickSeconds() {
            return String.valueOf(ThreadLocalRandom.current().nextLong(min.toSeconds(), max.toSeconds() + 1));
        }
    }
}
