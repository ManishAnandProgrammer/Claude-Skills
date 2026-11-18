package com.example.app.common.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NumberRangesTest {

    @Test
    @DisplayName("A range is ordered when its maximum is its minimum or more, or a bound is missing; inverted, it isn't")
    void orderedRanges() {
        assertThat(NumberRanges.isOrdered(30, 30)).isTrue();
        assertThat(NumberRanges.isOrdered(30, 31)).isTrue();
        assertThat(NumberRanges.isOrdered(31, 30)).isFalse();
        assertThat(NumberRanges.isOrdered(5_000_000_000L, 4_999_999_999L)).isFalse();
        assertThat(NumberRanges.isOrdered(null, 30)).isTrue();
        assertThat(NumberRanges.isOrdered(30, null)).isTrue();
    }
}
