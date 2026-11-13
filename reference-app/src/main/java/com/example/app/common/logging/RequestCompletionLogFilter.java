package com.example.app.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * One line per request when it ends: its status and duration, at WARN past
 * {@code app.logging.request-completion.slow-threshold}. The request id, endpoint, client and user
 * are already on the line from the log context. Actuator scrapes are skipped; the line is switched
 * off with {@code app.logging.request-completion.enabled=false}.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@ConditionalOnBooleanProperty(name = "app.logging.request-completion.enabled")
class RequestCompletionLogFilter extends OncePerRequestFilter {

    private final long slowThresholdMillis;
    private final String actuatorBasePath;

    RequestCompletionLogFilter(LoggingProperties properties, WebEndpointProperties actuator) {
        this.slowThresholdMillis = properties.requestCompletion().slowThreshold().toMillis();
        this.actuatorBasePath = actuator.getBasePath();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(actuatorBasePath);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException escaped) {
            // The container answers 500, but the response object still says 200 at this point.
            log.error("Request failed: status=500, durationMs={}", Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
            throw escaped;
        }
        logCompletion(response, Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }

    private void logCompletion(HttpServletResponse response, long durationMillis) {
        if (durationMillis > slowThresholdMillis) {
            log.warn("Request slow: status={}, durationMs={}, thresholdMs={}",
                    response.getStatus(), durationMillis, slowThresholdMillis);
        } else {
            log.info("Request done: status={}, durationMs={}", response.getStatus(), durationMillis);
        }
    }
}
