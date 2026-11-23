package com.example.app.common.logging;

import com.example.app.common.apiversion.RequestApiVersion;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@ConditionalOnBooleanProperty(name = "app.logging.request-completion.enabled")
@RequiredArgsConstructor
class RequestCompletionLogFilter extends OncePerRequestFilter {

    private final LoggingProperties properties;
    private final WebEndpointProperties actuator;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(actuator.getBasePath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException escaped) {
            // The container answers 500, but the response object still says 200 at this point.
            log.error("Request failed: status=500, durationMs={}{}",
                    Duration.ofNanos(System.nanoTime() - startedAt).toMillis(), apiVersion(request));
            throw escaped;
        }
        logCompletion(request, response, Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }

    private void logCompletion(HttpServletRequest request, HttpServletResponse response, long durationMillis) {
        long slowThresholdMillis = properties.requestCompletion().slowThreshold().toMillis();
        if (durationMillis > slowThresholdMillis) {
            log.warn("Request slow: status={}, durationMs={}, thresholdMs={}{}",
                    response.getStatus(), durationMillis, slowThresholdMillis, apiVersion(request));
        } else {
            log.info("Request done: status={}, durationMs={}{}", response.getStatus(), durationMillis, apiVersion(request));
        }
    }

    private static String apiVersion(HttpServletRequest request) {
        return RequestApiVersion.of(request).map(version -> ", apiVersion=" + version).orElse("");
    }
}
