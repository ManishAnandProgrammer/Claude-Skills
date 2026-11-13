package com.example.app.common.admission;

import com.example.app.common.exception.ErrorCode;
import com.example.app.common.web.ProblemResponses;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
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
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * <p><b>Groups:</b> a request under one of {@link AdmissionGroups#paths()} takes its permit from
 * that group's bulkhead, any other from {@value #DEFAULT_GROUP}'s. The bulkheads are the Resilience4j
 * starter's, configured under {@code resilience4j.bulkhead.instances}; a group missing there stops
 * startup rather than run on the library's defaults.</p>
 *
 * <p>Runs before the security chain, so refused requests cost nothing more, and after the
 * log-context filters, so a refusal is still one line in the log. The actuator is never capped:
 * health must answer under overload.</p>
 *
 * <p><b>Signals:</b> {@value #REJECTED_METRIC} tagged by group (registered at zero), Resilience4j's
 * {@code resilience4j_bulkhead_available_concurrent_calls}, and one WARN per group per
 * {@code warn-interval} at most.</p>
 */
@Slf4j
@Component
class AdmissionControlFilter extends OncePerRequestFilter implements Ordered {

    static final String REJECTED_METRIC = "admission.rejected";
    static final String GROUP_TAG = "group";
    static final String DEFAULT_GROUP = "api";

    private final List<Group> prefixGroups = new ArrayList<>();
    private final Group defaultGroup;
    private final ProblemResponses problemResponses;
    private static final String MATCH_ALL = "/**";

    private final String actuatorBasePath;
    private final long warnEveryNanos;

    private record Group(PathPattern path, Bulkhead bulkhead, Counter rejected, AtomicLong lastWarnAt) {
    }

    AdmissionControlFilter(AdmissionGroups groups, BulkheadRegistry bulkheads, MeterRegistry meterRegistry,
                           ProblemResponses problemResponses, WebEndpointProperties actuator) {
        this.problemResponses = problemResponses;
        this.actuatorBasePath = actuator.getBasePath();
        this.warnEveryNanos = groups.warnInterval().toNanos();
        this.defaultGroup = group(DEFAULT_GROUP, MATCH_ALL, bulkheads, meterRegistry);
        for (Map.Entry<String, String> entry : groups.paths().entrySet()) {
            prefixGroups.add(group(entry.getKey(), entry.getValue(), bulkheads, meterRegistry));
        }
    }

    private Group group(String name, String path, BulkheadRegistry bulkheads, MeterRegistry meterRegistry) {
        Bulkhead bulkhead = bulkheads.find(name).orElseThrow(() -> new IllegalStateException(
                "Admission group '%s' needs resilience4j.bulkhead.instances.%s".formatted(name, name)));
        Counter rejected = Counter.builder(REJECTED_METRIC)
                .description("Requests refused at the door because their group's in-flight cap was reached")
                .tag(GROUP_TAG, name)
                .register(meterRegistry);
        return new Group(PathPatternParser.defaultInstance.parse(path), bulkhead, rejected,
                new AtomicLong(System.nanoTime() - warnEveryNanos));
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
        Group group = groupOf(PathContainer.parsePath(request.getRequestURI()));
        if (!group.bulkhead().tryAcquirePermission()) {
            refuse(group, request, response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            group.bulkhead().onComplete();
        }
    }

    private Group groupOf(PathContainer path) {
        for (Group group : prefixGroups) {
            if (group.path().matches(path)) {
                return group;
            }
        }
        return defaultGroup;
    }

    private void refuse(Group group, HttpServletRequest request, HttpServletResponse response) throws IOException {
        group.rejected().increment();
        warnAtMostOncePerInterval(group);
        problemResponses.write(request, response, ErrorCode.SERVER_BUSY);
    }

    private void warnAtMostOncePerInterval(Group group) {
        long now = System.nanoTime();
        long previous = group.lastWarnAt().get();
        if (now - previous >= warnEveryNanos && group.lastWarnAt().compareAndSet(previous, now)) {
            log.warn("Refusing requests at the door: group={}, cap={} in flight, refusedSinceStart={}",
                    group.bulkhead().getName(), group.bulkhead().getBulkheadConfig().getMaxConcurrentCalls(),
                    (long) group.rejected().count());
        }
    }
}
