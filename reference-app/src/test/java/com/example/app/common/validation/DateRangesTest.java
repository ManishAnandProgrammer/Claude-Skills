package com.example.app.common.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DateRangesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    @Test
    @DisplayName("A range is ordered when its last day is its first or later, or a day is missing; inverted, it isn't")
    void orderedRanges() {
        assertThat(DateRanges.isOrdered(DAY, DAY)).isTrue();
        assertThat(DateRanges.isOrdered(DAY, DAY.plusDays(1))).isTrue();
        assertThat(DateRanges.isOrdered(DAY, DAY.minusDays(1))).isFalse();
        assertThat(DateRanges.isOrdered(null, DAY)).isTrue();
        assertThat(DateRanges.isOrdered(DAY, null)).isTrue();
    }

    @Test
    @DisplayName("The length counts both ends; null for a missing day or an inverted range")
    void lengthCountsBothEnds() {
        assertThat(DateRanges.lengthInDays(DAY, DAY)).isEqualTo(1L);
        assertThat(DateRanges.lengthInDays(DAY, DAY.plusDays(2))).isEqualTo(3L);
        assertThat(DateRanges.lengthInDays(DAY, DAY.minusDays(1))).isNull();
        assertThat(DateRanges.lengthInDays(null, DAY)).isNull();
    }
}
