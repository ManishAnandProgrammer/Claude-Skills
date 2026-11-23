package com.example.app.common.ratelimit;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param expiryGrace a bucket is deleted from Redis this long after it has refilled completely
 */
@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(boolean enabled,
                                  @NotNull @DurationMin(millis = 1) Duration backendTimeout,
                                  @NotNull @DurationMin(days = 0) Duration expiryGrace,
                                  @NotNull @DurationMin(millis = 1) Duration reconnectInterval) {
}
