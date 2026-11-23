package com.example.app.user.service;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Objects;

@Validated
@ConfigurationProperties(prefix = "app.session-version")
record SessionVersionProperties(@NotNull Duration cacheTtl) {

    SessionVersionProperties {
        if (Objects.nonNull(cacheTtl) && !cacheTtl.isPositive()) {
            throw new IllegalArgumentException("app.session-version.cache-ttl must be positive");
        }
    }
}
