package com.example.app.common.ratelimit;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The limiter itself ({@code app.rate-limit.*}); the limits belong to the modules that apply them.
 *
 * @param enabled        off only in tests that don't test the limits
 * @param backendTimeout longest a check waits for Redis before letting the request through
 */
@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(boolean enabled, @NotNull Duration backendTimeout) {
}
