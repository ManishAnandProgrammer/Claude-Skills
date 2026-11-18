package com.example.app.user.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImpersonationPropertiesTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"PT0.999S", "PT16M", "PT8H"})
    @DisplayName("An impersonation token lives between 1 second and 15 minutes: nothing ends it early")
    void tokenLifetimeIsBounded(Duration tokenTtl) {
        assertThatThrownBy(() -> new ImpersonationProperties(tokenTtl))
                .hasMessageContaining("app.tenancy.impersonation.token-ttl");
    }

    @Test
    @DisplayName("15 minutes, as configured, is accepted")
    void fifteenMinutesIsAccepted() {
        assertThatCode(() -> new ImpersonationProperties(Duration.ofMinutes(15))).doesNotThrowAnyException();
    }
}
