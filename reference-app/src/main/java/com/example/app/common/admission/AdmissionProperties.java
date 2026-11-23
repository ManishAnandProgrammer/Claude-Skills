package com.example.app.common.admission;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code resilience4j.bulkhead.instances.api} reads both values, so they are bound here only to be validated
 * and checked against the pool at startup.
 */
@Validated
@ConfigurationProperties(prefix = "app.admission")
public record AdmissionProperties(@Positive int maxConcurrent, @NotNull @DurationMin(days = 0) Duration maxWait) {
}
