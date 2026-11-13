package com.example.app.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesTest {

    private static final String SECRET = "a-secret-that-is-at-least-thirty-two-bytes";
    private static final Duration GRACE = Duration.ofSeconds(30);

    @Test
    @DisplayName("The access-token lifetime must be positive and at most 15 minutes; the refresh one positive")
    void tokenLifetimesAreBounded() {
        assertThatCode(() -> new JwtProperties(SECRET, "todo", Duration.ofMinutes(15), Duration.ofDays(30), GRACE)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new JwtProperties(SECRET, "todo", Duration.ofMinutes(16), Duration.ofDays(30), GRACE))
                .hasMessageContaining("JWT_ACCESS_TTL");
        assertThatThrownBy(() -> new JwtProperties(SECRET, "todo", Duration.ZERO, Duration.ofDays(30), GRACE))
                .hasMessageContaining("JWT_ACCESS_TTL");
        assertThatThrownBy(() -> new JwtProperties(SECRET, "todo", Duration.ofMinutes(15), Duration.ofDays(-1), GRACE))
                .hasMessageContaining("JWT_REFRESH_TTL");
    }

    @Test
    @DisplayName("A secret under 32 bytes stops startup")
    void shortSecretIsRefused() {
        assertThatThrownBy(() -> new JwtProperties("short", "todo", Duration.ofMinutes(15), Duration.ofDays(30), GRACE))
                .hasMessageContaining("JWT_SECRET");
    }
}
