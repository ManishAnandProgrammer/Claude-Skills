package com.example.app.user.service;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * {@code app.security.breached-passwords.*} (config/security.yml).
 *
 * @param apiUrl the range API's base, absolute with a host: without, every lookup would fail, and the check fails open
 */
@Validated
@ConfigurationProperties(prefix = "app.security.breached-passwords")
record BreachedPasswordProperties(boolean enabled,
                                  @NotNull URI apiUrl,
                                  @NotNull @DurationMin(millis = 1) Duration connectTimeout,
                                  @NotNull @DurationMin(millis = 1) Duration readTimeout) {

    BreachedPasswordProperties {
        if (Objects.nonNull(apiUrl) && (!apiUrl.isAbsolute() || Objects.isNull(apiUrl.getHost()))) {
            throw new IllegalArgumentException("app.security.breached-passwords.api-url must be an absolute URL with a host");
        }
    }
}
