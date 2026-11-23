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
import org.springframework.beans.factory.annotation.Value;
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
 * Tomcat's {@code threads.max} bounds the total, but the excess waits unseen in the accept queue and one slow
 * route group takes the threads every other group needs; a <em>semaphore</em> bulkhead per group refuses at the
 * door instead (never the thread-pool bulkhead: a second pool and a hand-off in front of Tomcat's).
 *
 * <p>Runs before the security chain, so refused requests cost nothing more, and after the log-context
 * filters and CORS ({@code SecurityConfig.corsFilter}), so a refusal is still one line in the log and a
 * browser can read it. The actuator is never capped: health must answer under overload.</p>
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

    private record Group(List<PathPattern> paths, Bulkhead bulkhead, Counter rejected, AtomicLong lastWarnAt) {

        boolean admits(PathContainer path) {
            return paths.stream().anyMatch(pattern -> pattern.matches(path));
        }
    }

    // Hand-written: each group's bulkhead is looked up once (a group without one stops the startup) and its
    // rejection counter registered, so the series reads 0 before the first refusal.
    AdmissionControlFilter(AdmissionGroups groups, BulkheadRegistry bulkheads, MeterRegistry meterRegistry,
                           ProblemResponses problemResponses, WebEndpointProperties actuator,
                           @Value("${server.tomcat.threads.max}") int requestThreads) {
        this.problemResponses = problemResponses;
        this.actuatorBasePath = actuator.getBasePath();
        this.warnEveryNanos = groups.warnInterval().toNanos();
        this.defaultGroup = group(DEFAULT_GROUP, List.of(MATCH_ALL), bulkheads, meterRegistry);
        for (Map.Entry<String, List<String>> entry : groups.paths().entrySet()) {
            prefixGroups.add(group(entry.getKey(), entry.getValue(), bulkheads, meterRegistry));
        }
        requireThreadsBeyondTheCaps(requestThreads);
    }

    private void requireThreadsBeyondTheCaps(int requestThreads) {
        int caps = defaultGroup.bulkhead().getBulkheadConfig().getMaxConcurrentCalls() + prefixGroups.stream()
                .mapToInt(group -> group.bulkhead().getBulkheadConfig().getMaxConcurrentCalls()).sum();
        if (requestThreads <= caps) {
            throw new IllegalStateException(("server.tomcat.threads.max (%d) must exceed the admission caps together (%d): "
                    + "a refusal needs a thread too").formatted(requestThreads, caps));
        }
    }

    private Group group(String name, List<String> paths, BulkheadRegistry bulkheads, MeterRegistry meterRegistry) {
        Bulkhead bulkhead = bulkheads.find(name).orElseThrow(() -> new IllegalStateException(
                "Admission group '%s' needs resilience4j.bulkhead.instances.%s".formatted(name, name)));
        Counter rejected = Counter.builder(REJECTED_METRIC)
                .description("Requests refused at the door because their group's in-flight cap was reached")
                .tag(GROUP_TAG, name)
                .register(meterRegistry);
        List<PathPattern> patterns = paths.stream().map(PathPatternParser.defaultInstance::parse).toList();
        return new Group(patterns, bulkhead, rejected,
                new AtomicLong(System.nanoTime() - warnEveryNanos));
    }

    @Override
    public int getOrder() {
        return SecurityFilterProperties.DEFAULT_FILTER_ORDER - 10;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals(actuatorBasePath) || path.startsWith(actuatorBasePath + "/");
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
            if (group.admits(path)) {
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
