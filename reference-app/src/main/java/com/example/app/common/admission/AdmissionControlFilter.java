package com.example.app.common.admission;

import com.example.app.common.exception.ErrorCode;
import com.example.app.common.web.ProblemResponses;
import com.example.app.common.web.RetryAfterProperties;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Caps the API requests in flight and refuses the excess at once with 503 {@code SERVER_BUSY} and a
 * jittered {@code Retry-After}.
 *
 * <p><b>Why here:</b> with virtual threads every connection gets its own thread, so
 * {@code server.tomcat.threads.max} bounds nothing; overload would queue on the connection pool for
 * its whole {@code connection-timeout}, and p99 would become that timeout for everyone. A
 * Resilience4j <em>semaphore</em> bulkhead decides at the door instead (never the thread-pool
 * bulkhead, which would undo virtual threads). The permit is released in {@code finally}.</p>
 *
 * <p>Runs before the security chain, so refused requests cost nothing more, and after the
 * log-context filters, so a refusal is still one line in the log. The actuator is never capped:
 * health must answer under overload.</p>
 *
 * <p><b>Signals:</b> {@value #REJECTED_METRIC} (registered at zero), Resilience4j's
 * {@code resilience4j_bulkhead_available_concurrent_calls}, and one WARN per ten seconds at most.</p>
 */
@Slf4j
@Component
class AdmissionControlFilter extends OncePerRequestFilter implements Ordered {

    static final String REJECTED_METRIC = "admission.rejected";
    private static final String BULKHEAD_NAME = "api";
    /** At most one WARN per this many nanoseconds: under overload a line per refusal would itself be load. */
    private static final long WARN_EVERY_NANOS = Duration.ofSeconds(10).toNanos();

    private final Bulkhead bulkhead;
    private final Counter rejected;
    private final ProblemResponses problemResponses;
    private final RetryAfterProperties.Range retryAfter;
    private final String actuatorBasePath;
    private final AtomicLong lastWarnAt = new AtomicLong(System.nanoTime() - WARN_EVERY_NANOS);

    AdmissionControlFilter(AdmissionProperties properties, MeterRegistry meterRegistry, ProblemResponses problemResponses,
                           RetryAfterProperties retryAfter, WebEndpointProperties actuator) {
        this.problemResponses = problemResponses;
        this.retryAfter = retryAfter.serverBusy();
        this.actuatorBasePath = actuator.getBasePath();
        BulkheadRegistry registry = BulkheadRegistry.ofDefaults();
        this.bulkhead = registry.bulkhead(BULKHEAD_NAME, BulkheadConfig.custom()
                .maxConcurrentCalls(properties.maxConcurrent())
                .maxWaitDuration(properties.maxWait())
                .build());
        this.rejected = Counter.builder(REJECTED_METRIC)
                .description("Requests refused at the door because the in-flight cap was reached")
                .register(meterRegistry);
        TaggedBulkheadMetrics.ofBulkheadRegistry(registry).bindTo(meterRegistry);
    }

    @Override
    public int getOrder() {
        return SecurityFilterProperties.DEFAULT_FILTER_ORDER - 10;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(actuatorBasePath);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!bulkhead.tryAcquirePermission()) {
            refuse(request, response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            bulkhead.onComplete();
        }
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        rejected.increment();
        warnAtMostEveryTenSeconds();
        response.setHeader(HttpHeaders.RETRY_AFTER, retryAfter.pickSeconds());
        problemResponses.write(request, response, ErrorCode.SERVER_BUSY);
    }

    private void warnAtMostEveryTenSeconds() {
        long now = System.nanoTime();
        long previous = lastWarnAt.get();
        if (now - previous >= WARN_EVERY_NANOS && lastWarnAt.compareAndSet(previous, now)) {
            log.warn("Refusing requests at the door: {} in flight is the cap ({} refused since start)",
                    bulkhead.getBulkheadConfig().getMaxConcurrentCalls(), (long) rejected.count());
        }
    }
}
