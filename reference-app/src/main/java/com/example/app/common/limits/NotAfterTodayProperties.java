package com.example.app.common.limits;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Period;
import java.util.Objects;

/**
 * {@code app.limits.not-after-today.*} (config/limits.yml): the {@link NotAfterToday} rule's settings.
 *
 * @param tolerance how far past UTC today a date may be ({@code 1d}): a span of calendar days, never negative,
 *                  which would refuse today itself
 */
@Validated
@ConfigurationProperties(prefix = "app.limits.not-after-today")
record NotAfterTodayProperties(@NotNull Period tolerance) {

    NotAfterTodayProperties {
        if (Objects.nonNull(tolerance) && tolerance.isNegative()) {
            throw new IllegalArgumentException("app.limits.not-after-today.tolerance must not be negative");
        }
    }
}
