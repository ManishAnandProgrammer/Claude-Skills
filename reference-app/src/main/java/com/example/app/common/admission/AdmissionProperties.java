package com.example.app.common.admission;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code app.admission.*}: the cap on API requests in flight on this instance.
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
public record AdmissionProperties(@Positive int maxConcurrent, @NotNull Duration maxWait) {
}
