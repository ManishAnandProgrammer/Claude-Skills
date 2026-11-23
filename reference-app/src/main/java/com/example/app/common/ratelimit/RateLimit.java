package com.example.app.common.ratelimit;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;

import java.time.Duration;

public record RateLimit(@Positive int capacity, @NotNull @DurationMin(millis = 1) Duration refillPeriod) {
}
