package com.example.app.common.limits;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Period;
import java.util.Objects;

/**
 * {@code app.limits.not-after-today.*} (config/limits.yml): the {@link NotAfterToday} rule's settings.
 *
 * @param tolerance how far past UTC today a date may be ({@code 1d}): days only, from 0 (which refuses a user east of
 *                  UTC their own today) to 2 (every time zone is at most 14 hours ahead of UTC, so more only lets future
 *                  dates in; {@code 1m} would be a month)
 */
@Validated
@ConfigurationProperties(prefix = "app.limits.not-after-today")
record NotAfterTodayProperties(@NotNull Period tolerance) {

    private static final int MOST_DAYS = 2;

    NotAfterTodayProperties {
        if (Objects.nonNull(tolerance)
                && (tolerance.isNegative() || tolerance.toTotalMonths() != 0 || tolerance.getDays() > MOST_DAYS)) {
            throw new IllegalArgumentException("app.limits.not-after-today.tolerance must be between 0d and 2d");
        }
    }
}
