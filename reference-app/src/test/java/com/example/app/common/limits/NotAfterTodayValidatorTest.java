package com.example.app.common.limits;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotAfterTodayValidatorTest {

    /** 23:30 UTC on 2001-01-01: already 2001-01-02 in every zone east of UTC+00:30. */
    private static final Clock LATE_IN_THE_UTC_DAY = Clock.fixed(Instant.parse("2001-01-01T23:30:00Z"), ZoneOffset.UTC);

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"2000-12-31, true", "2001-01-01, true", "2001-01-02, true", "2001-01-03, false", ", true"})
    @DisplayName("A date up to UTC today plus the configured tolerance is valid; the day after is not; none is not this rule's")
    void acceptsUpToTodayPlusTheTolerance(LocalDate day, boolean valid) {
        NotAfterTodayValidator validator = new NotAfterTodayValidator(new NotAfterTodayProperties(Period.ofDays(1)), LATE_IN_THE_UTC_DAY);

        assertThat(validator.isValid(day, null)).isEqualTo(valid);
    }

    @ParameterizedTest(name = "tolerance {0} days: 2001-01-02 → {1}")
    @CsvSource({"0, false", "1, true"})
    @DisplayName("The tolerance comes from configuration")
    void toleranceComesFromConfiguration(int toleranceDays, boolean valid) {
        NotAfterTodayValidator validator = new NotAfterTodayValidator(new NotAfterTodayProperties(Period.ofDays(toleranceDays)),
                LATE_IN_THE_UTC_DAY);

        assertThat(validator.isValid(LocalDate.of(2001, 1, 2), null)).isEqualTo(valid);
    }

    @Test
    @DisplayName("A negative tolerance stops startup: it would refuse today itself")
    void aNegativeToleranceIsRefused() {
        assertThatThrownBy(() -> new NotAfterTodayProperties(Period.ofDays(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.limits.not-after-today.tolerance");
    }
}
