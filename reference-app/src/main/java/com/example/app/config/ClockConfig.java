package com.example.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Ticks in microseconds, PostgreSQL's precision: a moment the API answers right after a write is then the one a
 * later read returns, not one with nanoseconds the column dropped.
 */
@Configuration
class ClockConfig {

    private static final Duration DATABASE_PRECISION = Duration.ofNanos(1_000);

    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), DATABASE_PRECISION);
    }
}
