package com.example.app.common.ratelimit;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Duration;

/** A burst of at most {@code requests}, refilled gradually over {@code window}. */
public record RateLimit(@Positive int requests, @NotNull Duration window) {
}
