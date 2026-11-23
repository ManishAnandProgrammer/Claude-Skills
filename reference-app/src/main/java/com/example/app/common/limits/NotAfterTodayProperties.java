package com.example.app.common.limits;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Period;
import java.util.Objects;

/**
 * @param tolerance days only, at most 2: every time zone is at most 14 hours ahead of UTC, so more only lets
 *                  future dates in
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
