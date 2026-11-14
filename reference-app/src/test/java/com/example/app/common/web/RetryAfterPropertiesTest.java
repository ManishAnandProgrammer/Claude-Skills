package com.example.app.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryAfterPropertiesTest {

    @Test
    @DisplayName("A range starting below one second is refused: the header would tell the client to come back at once")
    void refusesASubSecondMinimum() {
        assertThatThrownBy(() -> new RetryAfterProperties.Range(Duration.ofMillis(500), Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1s <= min <= max");
    }

    @Test
    @DisplayName("A range ending before it starts is refused")
    void refusesAnInvertedRange() {
        assertThatThrownBy(() -> new RetryAfterProperties.Range(Duration.ofSeconds(3), Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A range answers within its bounds, and the value is really jittered")
    void answersAreJitteredWithinTheRange() {
        RetryAfterProperties.Range range = new RetryAfterProperties.Range(Duration.ofSeconds(3), Duration.ofSeconds(8));
        Set<Long> seen = new HashSet<>();
        for (int attempt = 0; attempt < 200; attempt++) {
            seen.add(Long.parseLong(range.pickSeconds()));
        }
        assertThat(seen).hasSizeGreaterThan(1).allSatisfy(seconds -> assertThat(seconds).isBetween(3L, 8L));
    }

    @Test
    @DisplayName("A one-second range always answers 1")
    void aOneSecondRangeAnswersOne() {
        assertThat(new RetryAfterProperties.Range(Duration.ofSeconds(1), Duration.ofSeconds(1)).pickSeconds()).isEqualTo("1");
    }
}
