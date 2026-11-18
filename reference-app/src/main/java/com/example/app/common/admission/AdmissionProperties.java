package com.example.app.common.admission;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code app.admission.*}: the cap on the {@code api} group, every API request outside the
 * {@link AdmissionGroups} of their own. {@code resilience4j.bulkhead.instances.api} reads both values,
 * so they are bound here only to be validated and checked against the pool at startup.
 *
 * <p>Sizing: about twice the connection pool. A request holds a connection for only part of its
 * time, so that keeps the pool busy without a queue forming on it (Little's law). Tune with a load
 * test.</p>
 *
 * @param maxConcurrent requests in flight at once on this instance
 * @param maxWait       how long one past the cap waits for a permit before it is refused
 */
@Validated
@ConfigurationProperties(prefix = "app.admission")
public record AdmissionProperties(@Positive int maxConcurrent, @NotNull @DurationMin(days = 0) Duration maxWait) {
}
