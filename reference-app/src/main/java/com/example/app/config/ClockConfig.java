package com.example.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * The one source of "now", so time-dependent rules can be tested with a fixed clock. It ticks in
 * microseconds, PostgreSQL's precision: a moment the API answers right after a write ({@code completedAt},
 * {@code updatedAt}) is then the moment a later read returns, not one with nanoseconds the column dropped.
 */
@Configuration
class ClockConfig {

    private static final Duration DATABASE_PRECISION = Duration.ofNanos(1_000);

    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), DATABASE_PRECISION);
    }
}
