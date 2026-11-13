package com.example.app.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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
    @DisplayName("A one-second range always answers 1")
    void aOneSecondRangeAnswersOne() {
        assertThat(new RetryAfterProperties.Range(Duration.ofSeconds(1), Duration.ofSeconds(1)).pickSeconds()).isEqualTo("1");
    }
}
