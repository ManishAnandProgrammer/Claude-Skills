package com.example.app.user.service;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Objects;

/**
 * The session version's Redis copy ({@code app.session-version.*}).
 *
 * @param cacheTtl how long Redis keeps a session version. It bounds two windows: a bump whose new number
 *                 didn't reach Redis (Redis unreachable right after the commit) leaves the old one
 *                 accepted this long, and so does a change an operator makes in SQL without updating
 *                 Redis. Shorter costs one indexed database read per signed-in account and TTL.
 */
@Validated
@ConfigurationProperties(prefix = "app.session-version")
record SessionVersionProperties(@NotNull Duration cacheTtl) {

    SessionVersionProperties {
        if (Objects.nonNull(cacheTtl) && !cacheTtl.isPositive()) {
            throw new IllegalArgumentException("app.session-version.cache-ttl must be positive");
        }
    }
}
